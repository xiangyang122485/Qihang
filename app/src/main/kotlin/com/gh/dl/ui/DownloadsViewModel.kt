package com.gh.dl.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.gh.dl.data.AriaEngine
import com.gh.dl.data.Channels
import com.gh.dl.data.DownloadStore
import com.gh.dl.data.Fetch
import com.gh.dl.data.Jsd
import com.gh.dl.data.Net
import com.gh.dl.data.OkEngine
import com.gh.dl.data.Store
import com.gh.dl.data.ZipPack
import com.gh.dl.data.fmtSize
import com.gh.dl.data.parseSlug
import com.gh.dl.DownloadService
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * 下载页的状态与动作。原 [AppState.downloads] 列表与下载 / 打包执行逻辑迁到本 ViewModel。
 *
 * 下载通道配置（channel/custom/accelOn/verifyOn/dlEngine）由 [settings] 持有；
 * 打包进度回写解析页的 [parser].busy / packMsg。
 * 下载任务列表通过 [DownloadStore] 持久化到应用私有目录（进程死亡后可恢复）。
 */
class DownloadsViewModel : ViewModel() {

    lateinit var settings: SettingsViewModel
    lateinit var parser: ParserViewModel

    /** 文件下载任务列表 */
    val downloads = mutableStateListOf<DlJob>()
    private var nextDlId = 1L
    fun newDlId(): Long = nextDlId++

    /** 下载页「批量删除」多选模式 */
    var dlMultiSelect by mutableStateOf(false)
    val dlSel = mutableStateListOf<Long>()

    /** 同时下载上限：公益镜像有限流，疯狂并发会触发 429 */
    private val MAX_CONCURRENT = 3

    /**
     * 下载任务排队队列：并发达上限时入队，完成一个再启一个。
     * 带 job.id 是为了「删除任务」时能把它从队列里一并撤掉——否则用户删掉的排队任务
     * 会在前一个下载结束时自己跑起来。
     */
    private val dlQueue = ArrayDeque<Pair<Long, () -> Unit>>()

    /** 从队列里补足并发，直到达到 MAX_CONCURRENT */
    private fun pumpDownloads() {
        while (downloads.count { it.running } < MAX_CONCURRENT && dlQueue.isNotEmpty()) {
            dlQueue.removeFirst().second()
        }
    }

    /** 直接启动，或并发已满时入队等待 */
    private fun submitDownload(job: DlJob, start: () -> Unit) {
        if (downloads.count { it.running } >= MAX_CONCURRENT) dlQueue.addLast(job.id to start)
        else start()
    }

    /** 任务已从列表移除（用户删除）时，丢弃它的排队入口 */
    private fun queuedIfAlive(job: DlJob, start: () -> Unit) {
        if (downloads.none { it.id == job.id }) return
        start()
    }

    /** 把当前列表落盘（结构变更 / 终态时调用） */
    private fun persist(ctx: Context) {
        DownloadStore.save(ctx, downloads)
    }

    /** 启动时恢复历史任务（进程死亡后 running 态会被复位） */
    fun loadDownloads(ctx: Context) {
        applyLoaded(DownloadStore.load(ctx))
    }

    /**
     * 启动时用的版本：整份下载历史是一个 JSON 文件，读盘与解析放 IO 线程，
     * 只有「写进列表」这一步回主线程 —— SnapshotStateList 的增删必须在主线程做，
     * 否则重组时序没有保证（[loadDownloads] 之所以不整段挪走就是这个原因）。
     */
    suspend fun loadDownloadsAsync(ctx: Context) {
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            DownloadStore.load(ctx)
        }
        applyLoaded(loaded)
    }

    private fun applyLoaded(list: List<DlJob>) {
        downloads.addAll(list)
        // 恢复出来的任务带着旧 id，新任务的 id 必须从最大值之后继续发，否则
        // 两条任务同 id 会让「勾选删除」「队列撤销」按 id 误伤另一条。
        nextDlId = (downloads.maxOfOrNull { it.id } ?: 0L) + 1L
    }

    /**
     * 单个下载任务的真正执行体：在后台线程拉取并把进度/结果写回 [job]。
     * @param resume true=断点续传
     */
    private fun runDownload(
        ctx: Context, job: DlJob, url: String, rawName: String,
        officialSha: String?, resume: Boolean = false, sizeHint: Long = -1L
    ) {
        job.url = url
        job.officialSha = officialSha
        job.paused = false
        job.failed = false
        job.cancelled = false
        val app = ctx.applicationContext
        val name = job.name

        // Task4：未授权 SAF 下载位置时，不静默下到私有目录，改为弹引导并标记失败，等用户授权后重试。
        // 必须在置 running=true 之前检查：这条分支走的是 return，不经过下面线程的 finally { pumpDownloads() }，
        // 先置 running 会虚占一个并发位并让队列里的后续任务永久饿死。
        if (!Store.isSet(ctx)) {
            AppState.needAuthGuide = true
            MAIN.post {
                job.failed = true
                job.msg = "未授权下载位置：请到 设置 → 下载 选择共享文件夹"
                persist(ctx)
                pumpDownloads()
            }
            return
        }

        job.running = true
        job.msg = if (resume) "继续下载…" else "下载中…"
        val real = Channels.file(settings.channel, settings.custom, url)
        val engine = settings.dlEngine
        val effective = if (engine == "aria2" && !AriaEngine.available(app)) {
            MAIN.post { toast(app, "Aria2 未就绪，本次用单线程") }
            "single"
        } else engine
        val cancelHolder = AtomicReference<(() -> Unit)?>(null)
        // 前台服务 + 唤醒锁：切后台/锁屏也能跑完
        AppState.dlNotiBytes = 0L
        AppState.dlNotiTotal = -1L
        DownloadService.begin(app, name)
        Thread {
            // 已知真实大小（来自 GitHub API 的 Release 资产 / jsDelivr 文件）直接作为分母，
            // 跳过 HEAD 探测；未知（-1）才回退到 HEAD/Range 探测（源码 ZIP 经 codeload chunked 拿不到）。
            // 探测必须在后台线程做：runDownload 由主线程（点下载按钮 / 队列 pump）调用，
            // 而 Fetch.headTotal 内部 catch 了所有异常 —— 在主线程发网络请求会被
            // NetworkOnMainThreadException 直接吞掉，永远返回 -1，进度条就一直是「总大小未知」。
            val totalHint = if (sizeHint > 0) sizeHint else Fetch.headTotal(real)
            try {
                val saved = when (effective) {
                    "okdownload" -> OkEngine.download(app, real, name,
                        { b, t, s -> MAIN.post { job.bytes = b; job.total = t; job.speed = s; AppState.dlNotiBytes = b; AppState.dlNotiTotal = t } },
                        cancelHolder, resume, totalHint, jobKey = job.id)
                    "aria2" -> AriaEngine.download(app, real, name,
                        { b, t, s -> MAIN.post { job.bytes = b; job.total = t; job.speed = s; AppState.dlNotiBytes = b; AppState.dlNotiTotal = t } },
                        cancelHolder, resume, totalHint, jobKey = job.id)
                    else -> Fetch.download(app, real, name,
                        { b, t, s -> MAIN.post { job.bytes = b; job.total = t; job.speed = s; AppState.dlNotiBytes = b; AppState.dlNotiTotal = t } },
                        cancelHolder, resume, totalHint, jobKey = job.id)
                }
                MAIN.post { job.sha = saved.sha }
                val verify = if (settings.verifyOn) {
                    if (!officialSha.isNullOrBlank()) {
                        if (saved.sha.equals(officialSha, true))
                            "✓ 与官方校验值一致"
                        else
                            "✗ 与官方校验值不符！文件可能被第三方篡改，建议删除"
                    } else {
                        "已计算 SHA256（该文件无官方校验值，请自行比对）"
                    }
                } else {
                    ""
                }
                MAIN.post {
                    job.verify = verify
                    job.running = false
                    job.done = true
                    job.canceller = null
                    job.where = saved.path
                    job.msg = "完成 · ${fmtSize(saved.bytes)}"
                    AppState.dlNotiBytes = saved.bytes
                    AppState.dlNotiTotal = saved.bytes
                    AppState.dlNotiOk = true
                    DownloadService.end(app, true, name)
                    persist(ctx)
                }
            } catch (e: Fetch.PausedException) {
                MAIN.post {
                    job.running = false
                    job.paused = true
                    job.canceller = null
                    job.msg = "已暂停（点继续可断点续传）"
                    // 暂停时任务已被用户删除 → 这份断点再也不可能续传，直接丢，
                    // 否则每删一个下载中的大文件就在缓存里留一份永久垃圾
                    if (downloads.none { it.id == job.id }) Fetch.dropTemp(app, job.id, job.name)
                    DownloadService.end(app, true, name, suppressDone = true)
                    persist(ctx)
                }
            } catch (e: Exception) {
                MAIN.post {
                    job.running = false
                    job.failed = true
                    job.canceller = null
                    // 重试走的是「从头下载」（resume=false），失败留下的半截文件没有价值，清掉
                    Fetch.dropTemp(app, job.id, job.name)
                    job.msg = "失败：${e.message ?: e.javaClass.simpleName}"
                    AppState.dlNotiOk = false
                    DownloadService.end(app, false, name)
                    persist(ctx)
                }
            } finally {
                MAIN.post { pumpDownloads() }
            }
        }.start()
        job.canceller = { cancelHolder.get()?.invoke() }
    }

    /** 暂停：停止当前传输，保留临时文件，等待「继续」断点续传 */
    fun pauseDownload(job: DlJob) {
        if (!job.running || job.paused) return
        job.cancelled = true
        job.canceller?.invoke()
    }

    /**
     * 继续：从暂停处断点续传（整仓打包任务则重新打包）。
     * 和 start/retry 一样要走 [submitDownload]：原先直接调 runDownload/runPack，
     * 并发满时点「继续」会把并发顶到上限之外，触发镜像限流。
     */
    fun resumeDownload(ctx: Context, job: DlJob) {
        if (!job.paused) return
        if (job.kind == "pack") {
            val files = job.packFiles
            if (files == null || job.packSlug.isBlank()) {
                toast(ctx, "这个打包任务没有可继续的数据")
                return
            }
            submitDownload(job) {
                queuedIfAlive(job) { runPack(ctx, job, job.packSlug, job.packRef, files) }
            }
            return
        }
        if (job.url.isBlank()) {
            toast(ctx, "这个任务没有可继续的链接")
            return
        }
        job.startedAt = System.currentTimeMillis()
        submitDownload(job) {
            queuedIfAlive(job) {
                runDownload(ctx, job, job.url, job.name, job.officialSha, resume = true, sizeHint = job.total)
            }
        }
    }

    /** 交互式下载：限制并发，并自动跳到下载页 */
    fun startDownload(ctx: Context, url: String, rawName: String, officialSha: String? = null, sizeHint: Long = -1L) {
        val job = DlJob(newDlId(), Fetch.safeName(rawName))
        downloads.add(0, job)
        AppState.tab = AppState.TAB_DOWNLOADS
        submitDownload(job) { queuedIfAlive(job) { runDownload(ctx, job, url, rawName, officialSha, sizeHint = sizeHint) } }
        persist(ctx)
    }

    /** 批量下载：不抢焦点跳页，同样受并发上限约束 */
    fun enqueueDownload(ctx: Context, url: String, rawName: String, officialSha: String? = null, sizeHint: Long = -1L) {
        val job = DlJob(newDlId(), Fetch.safeName(rawName))
        downloads.add(0, job)
        submitDownload(job) { queuedIfAlive(job) { runDownload(ctx, job, url, rawName, officialSha, sizeHint = sizeHint) } }
        persist(ctx)
    }

    /** 失败任务重试：复用同一任务对象，重置状态后重新拉取 */
    fun retryDownload(ctx: Context, job: DlJob) {
        if (job.kind == "pack") {
            val files = job.packFiles
            if (files == null || job.packSlug.isBlank()) {
                toast(ctx, "这个打包任务没有可重试的数据")
                return
            }
            submitDownload(job) { queuedIfAlive(job) { runPack(ctx, job, job.packSlug, job.packRef, files) } }
            persist(ctx)
            return
        }
        if (job.url.isBlank()) {
            toast(ctx, "这个任务没有可重试的链接")
            return
        }
        // 先把已知总大小取出来再清零：原写法先 job.total = -1L、后 sizeHint = job.total，
        // 传给引擎的分母永远是 -1，重试后进度条退回「总大小未知」。
        val hint = if (job.total > 0) job.total else -1L
        // 不能在这里预置 running=true：并发满时任务是排队的，
        // 这个 true 会虚占一个并发位，把自己和其它排队任务一起卡住。
        job.done = false
        job.failed = false
        job.bytes = 0L
        job.total = -1L
        job.speed = 0L
        job.where = ""
        job.verify = ""
        job.startedAt = System.currentTimeMillis()
        job.msg = "正在重试…"
        // 走队列：原先直接 runDownload 会绕过 MAX_CONCURRENT，把并发顶到 4+ 触发镜像 429
        submitDownload(job) {
            queuedIfAlive(job) {
                runDownload(ctx, job, job.url, job.name, job.officialSha, sizeHint = hint)
            }
        }
        persist(ctx)
    }

    /**
     * jsDelivr 整仓源码打包执行体：作为一条下载任务跑，支持暂停。
     * 暂停即中止打包（丢弃半成品），「继续」重新打包整仓。
     */
    fun runPack(ctx: Context, job: DlJob, slug: String, ref: String, files: List<Jsd.Entry>) {
        job.kind = "pack"
        job.packSlug = slug
        job.packRef = ref
        job.packFiles = files
        job.running = true
        job.paused = false
        job.failed = false
        job.cancelled = false
        job.done = false
        job.bytes = 0L
        job.total = files.sumOf { it.size }
        job.speed = 0L
        job.where = ""
        job.sha = ""
        job.verify = ""
        job.msg = "正在下载并打包源码…"
        parser.busy = true
        val app = ctx.applicationContext
        val cancelHolder = AtomicReference(false)
        Thread {
            try {
                val msg = ZipPack.pack(app, slug, ref, files,
                    onProgress = { b, t -> MAIN.post {
                        job.bytes = b
                        job.total = t
                        job.msg = "正在下载并打包… ${fmtSize(b)} / ${fmtSize(t)}"
                        parser.packMsg = job.msg
                    } },
                    cancelHolder = cancelHolder)
                MAIN.post {
                    job.running = false
                    job.done = true
                    job.canceller = null
                    job.where = msg.substringAfterLast('\n').trim()
                    job.msg = msg
                    parser.packMsg = msg
                    parser.busy = false
                    persist(ctx)
                }
            } catch (e: Fetch.PausedException) {
                MAIN.post {
                    job.running = false
                    job.paused = true
                    job.canceller = null
                    job.msg = "已暂停（点继续可重新打包）"
                    parser.busy = false
                    persist(ctx)
                }
            } catch (e: Exception) {
                MAIN.post {
                    job.running = false
                    job.failed = true
                    job.canceller = null
                    job.msg = "失败：${e.message ?: e.javaClass.simpleName}"
                    parser.busy = false
                    persist(ctx)
                }
            } finally {
                MAIN.post { pumpDownloads() }
            }
        }.start()
        job.canceller = { cancelHolder.set(true) }
    }

    /** 全选 / 取消全选 切换（仅作用于文件下载任务） */
    fun toggleDlAll() {
        val all = downloads.map { it.id }
        if (dlSel.size >= all.size && all.isNotEmpty()) {
            dlSel.clear()
        } else {
            dlSel.clear()
            dlSel.addAll(all)
        }
    }

    /**
     * 「清除已完成」：移除成功的记录并立刻落盘。
     * 漏 persist 的话，重启后 DownloadStore 又会把这些记录读回来，表现为「删了又活」。
     */
    fun clearCompleted(ctx: Context) {
        val goneJobs = downloads.filter { it.done && !it.failed }
        val gone = goneJobs.map { it.id }.toSet()
        downloads.removeAll { gone.contains(it.id) }
        dlQueue.removeAll { gone.contains(it.first) }
        goneJobs.forEach { Fetch.dropTemp(ctx, it.id, it.name) }
        persist(ctx)
    }

    /** 删除勾选的下载任务条目（仅从列表移除记录，不删磁盘上已下载好的成品文件） */
    fun deleteSelectedDownloads(ctx: Context) {
        val ids = dlSel.toSet()
        if (ids.isEmpty()) {
            toast(ctx, "先勾选要删除的任务")
            return
        }
        val gone = downloads.filter { ids.contains(it.id) }
        // 正在下载的任务被删：先掐断传输。临时文件留给工作线程收尾时删——
        // 这里直接删会和还在写的线程抢（对方会又把文件建出来，反而留成垃圾）。
        gone.filter { it.running }.forEach { pauseDownload(it) }
        downloads.removeAll { ids.contains(it.id) }
        // 排队中还没启动的任务也要从队列撤掉，否则前一个下载结束时它会自动跑起来
        dlQueue.removeAll { ids.contains(it.first) }
        gone.filter { !it.running }.forEach { Fetch.dropTemp(ctx, it.id, it.name) }
        dlSel.clear()
        dlMultiSelect = false
        toast(ctx, "已删除 ${ids.size} 条下载记录")
        persist(ctx)
    }
}
