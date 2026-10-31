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
import com.gh.dl.data.Threads
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

    /** 历史任务只合并一次；重复调用（配置变更等）不会把同一批记录再追加一遍 */
    private var loadedOnce = false

    /** 分配新任务 id：跳过列表中已存在的 id，避免与尚未合并/已恢复的历史任务冲突 */
    fun newDlId(): Long {
        var candidate = nextDlId
        while (downloads.any { it.id == candidate }) candidate++
        nextDlId = candidate + 1L
        return candidate
    }

    /** 下载页「批量删除」多选模式 */
    var dlMultiSelect by mutableStateOf(false)
    val dlSel = mutableStateListOf<Long>()

    /** 同时下载上限：公共镜像站有限流策略，过高并发会触发 429 状态码 */
    private val MAX_CONCURRENT = 3

    /**
     * 下载任务排队队列：并发达到上限时入队等待，完成一个后启动下一个。
     * 队列中保留 job.id 以便「删除任务」时同步移除对应的排队项——否则用户删除的排队任务
     * 会在前一个下载结束时自动启动。
     */
    private val dlQueue = ArrayDeque<Pair<Long, () -> Unit>>()

    /** 从队列中补充并发任务，直至达到 MAX_CONCURRENT 上限 */
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

    /** 任务已从列表移除（用户删除）时，丢弃其排队入口 */
    private fun queuedIfAlive(job: DlJob, start: () -> Unit) {
        if (downloads.none { it.id == job.id }) return
        start()
    }

    /** 将当前列表持久化到磁盘（结构变更 / 终态时调用） */
    private fun persist(ctx: Context) {
        DownloadStore.save(ctx, downloads)
    }

    /** 启动时恢复历史任务（进程终止后 running 状态会被复位） */
    fun loadDownloads(ctx: Context) {
        applyLoaded(DownloadStore.load(ctx))
    }

    /**
     * 异步版本：整份下载历史为一个 JSON 文件，读取与解析在 IO 线程执行，
     * 仅「写入列表」步骤回到主线程——SnapshotStateList 的增删必须在主线程进行，
     * 否则重组时序无法保证（这也是 [loadDownloads] 不整体移至 IO 线程的原因）。
     */
    suspend fun loadDownloadsAsync(ctx: Context) {
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            DownloadStore.load(ctx)
        }
        applyLoaded(loaded)
    }

    private fun applyLoaded(list: List<DlJob>) {
        if (loadedOnce) return
        loadedOnce = true

        // 合并历史任务时必须再次校验 id 唯一性：loadDownloadsAsync 在 IO 线程读取期间，
        // 用户可能已经点过「下载」，新任务先进入了列表并占用了相同 id。
        // 此时若直接 addAll，下载页的 LazyColumn key 会重复并崩溃；
        // 对冲突的「历史任务」重新编号（新任务可能正在下载，不能改动其 id）。
        val used = downloads.mapTo(HashSet()) { it.id }
        var maxId = maxOf(
            downloads.maxOfOrNull { it.id } ?: 0L,
            list.maxOfOrNull { it.id } ?: 0L
        )
        for (job in list) {
            if (job.id <= 0L || !used.add(job.id)) {
                do { maxId++ } while (!used.add(maxId))
                job.id = maxId
            }
            downloads.add(job)
        }
        nextDlId = maxOf(nextDlId, maxId + 1L)
    }

    /**
     * 单个下载任务的执行体：在后台线程中拉取数据并将进度/结果写回 [job]。
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

        // Task 4：未授权 SAF 下载位置时，不自动下载到私有目录，改为显示引导并标记失败，等待用户授权后重试。
        // 必须在设置 running=true 之前检查：此分支通过 return 退出，不经过下方线程的 finally { pumpDownloads() }，
        // 提前设置 running 会虚占一个并发位，导致队列中的后续任务永久无法启动。
        if (!Store.isSet(ctx)) {
            AppState.needAuthGuide = true
            MAIN.post {
                job.failed = true
                job.msg = "未授权下载位置：前往「设置 → 下载」选择文件夹"
                persist(ctx)
                pumpDownloads()
            }
            return
        }

        job.running = true
        job.msg = if (resume) "继续下载…" else "下载中…"
        val real = Channels.file(settings.channel, settings.custom, url)
        val engine = settings.dlEngine
        val cancelHolder = AtomicReference<(() -> Unit)?>(null)
        // 前台服务 + 唤醒锁：切换后台或锁屏后仍可完成下载
        AppState.dlNotiBytes = 0L
        AppState.dlNotiTotal = -1L
        DownloadService.begin(app, name)
        Threads.start("下载任务") {
            // 引擎可用性探测含一次 fork/exec（最长 8 秒），必须在工作线程执行。
            // 原实现位于调用方（主线程）路径上：首次点击下载会阻塞主线程直至 ANR，
            // 表现为「点下载就闪退/无响应」。aria2 不可用时回退至多线程的 okdownload。
            val effective = if (engine == "aria2" &&
                !(AriaEngine.available(app) && AriaEngine.verifyExec(app))) {
                MAIN.post { toast(app, "Aria2 在本机不可用，已改用多线程下载") }
                "okdownload"
            } else engine
            // 已知真实大小（来自 GitHub API 的 Release 资产 / jsDelivr 文件）直接作为总量，
            // 跳过 HEAD 探测；未知（-1）时回退到 HEAD/Range 探测（源码 ZIP 经 codeload chunked 传输无法获取）。
            // 探测必须在后台线程执行：runDownload 由主线程（点击下载按钮 / 队列 pump）调用，
            // 而 Fetch.headTotal 内部捕获了所有异常——在主线程发起网络请求会被
            // NetworkOnMainThreadException 捕获，导致始终返回 -1，进度条持续显示「总大小未知」。
            val totalHint = if (sizeHint > 0) sizeHint else Fetch.headTotal(real)
            try {
                val saved = when (effective) {
                    "okdownload" -> OkEngine.download(app, real, name,
                        { b, t, s -> MAIN.post { job.bytes = b; job.total = t; job.speed = s; AppState.dlNotiBytes = b; AppState.dlNotiTotal = t } },
                        cancelHolder, resume, totalHint, jobKey = job.id)
                    "aria2" -> try {
                        AriaEngine.download(app, real, name,
                            { b, t, s -> MAIN.post { job.bytes = b; job.total = t; job.speed = s; AppState.dlNotiBytes = b; AppState.dlNotiTotal = t } },
                            cancelHolder, resume, totalHint, jobKey = job.id)
                    } catch (e: AriaEngine.Unavailable) {
                        // available() 通过但实际 exec 被拒（首任务即失败）：本次就地改用 JVM 引擎，
                        // 不把「设备不支持」呈现为「下载失败」
                        android.util.Log.w("DownloadsVM", "aria2 不可用，改用 okdownload", e)
                        MAIN.post { toast(app, "Aria2 在本机不可用，已改用多线程下载") }
                        OkEngine.download(app, real, name,
                            { b, t, s -> MAIN.post { job.bytes = b; job.total = t; job.speed = s; AppState.dlNotiBytes = b; AppState.dlNotiTotal = t } },
                            cancelHolder, resume, totalHint, jobKey = job.id)
                    }
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
                            "✗ 与官方校验值不符！文件可能已被第三方篡改，建议删除"
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
                    job.whereUri = saved.uri?.toString().orEmpty()
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
                    // 暂停时任务已被用户删除 → 此断点已无法续传，直接清理，
                    // 否则每次删除下载中的大文件都会在缓存中遗留永久无用文件
                    if (downloads.none { it.id == job.id }) Fetch.dropTemp(app, job.id, job.name)
                    DownloadService.end(app, true, name, suppressDone = true)
                    persist(ctx)
                }
            } catch (e: Exception) {
                MAIN.post {
                    job.running = false
                    job.failed = true
                    job.canceller = null
                    // 保留断点临时文件：失败多由连接中断或服务端限流造成，已接收字节仍然有效。
                    // 重试时服务端接受 Range 即从断点继续，不接受则引擎自行删除旧文件重下
                    //（见 Fetch.download 的 206 判定），因此保留断点不会损坏文件。
                    // 因此保留断点文件，重试不必从 0 开始。
                    job.msg = "失败：${e.message ?: e.javaClass.simpleName}"
                    AppState.dlNotiOk = false
                    DownloadService.end(app, false, name)
                    persist(ctx)
                }
            } catch (t: Throwable) {
                // Error（NoClassDefFoundError、OutOfMemoryError 等）不拦截会使线程静默终止，
                // job.running 永远停留在 true，队列与前台通知都无法收尾
                android.util.Log.e("DownloadsVM", "下载线程异常终止", t)
                MAIN.post {
                    job.running = false
                    job.failed = true
                    job.canceller = null
                    job.msg = "失败：" + (t.javaClass.simpleName)
                    AppState.dlNotiOk = false
                    DownloadService.end(app, false, name)
                    persist(ctx)
                }
            } finally {
                MAIN.post { pumpDownloads() }
            }
        }
        job.canceller = { cancelHolder.get()?.invoke() }
    }

    /** 暂停：停止当前传输，保留临时文件，等待「继续」执行断点续传 */
    fun pauseDownload(job: DlJob) {
        if (!job.running || job.paused) return
        job.cancelled = true
        job.canceller?.invoke()
    }

    /**
     * 继续：从暂停处断点续传（整仓打包任务则重新打包）。
     * 与 start/retry 相同，需通过 [submitDownload] 提交：直接调用 runDownload/runPack
     * 并发已满时点击「继续」会超出并发上限，触发镜像站限流。
     */
    fun resumeDownload(ctx: Context, job: DlJob) {
        if (!job.paused) return
        if (job.kind == "pack") {
            val files = job.packFiles
            if (files == null || job.packSlug.isBlank()) {
                toast(ctx, "该打包任务无可继续的数据")
                return
            }
            submitDownload(job) {
                queuedIfAlive(job) { runPack(ctx, job, job.packSlug, job.packRef, files) }
            }
            return
        }
        if (job.url.isBlank()) {
            toast(ctx, "该任务无可继续的链接")
            return
        }
        job.startedAt = System.currentTimeMillis()
        submitDownload(job) {
            queuedIfAlive(job) {
                runDownload(ctx, job, job.url, job.name, job.officialSha, resume = true, sizeHint = job.total)
            }
        }
    }

    /**
     * 启动下载：限制并发，并自动切换到下载页。
     *
     * 整体捕获异常：该入口由界面线程直接调用，任何未捕获异常（系统服务受限、
     * 文件名非法、状态写入异常）都会终止进程，表现为「点下载闪退」。
     * 失败时保留任务条目并给出可读原因，用户可重试。
     */
    fun startDownload(ctx: Context, url: String, rawName: String, officialSha: String? = null, sizeHint: Long = -1L) {
        val job = DlJob(newDlId(), Fetch.safeName(rawName))
        try {
            downloads.add(0, job)
            AppState.tab = AppState.TAB_DOWNLOADS
            submitDownload(job) { queuedIfAlive(job) { runDownload(ctx, job, url, rawName, officialSha, sizeHint = sizeHint) } }
            persist(ctx)
        } catch (t: Throwable) {
            runCatching {
                job.running = false
                job.failed = true
                job.msg = "无法启动下载：" + (t.message ?: t.javaClass.simpleName)
            }
            toast(ctx, "无法启动下载：" + (t.message ?: t.javaClass.simpleName))
        }
    }

    /** 批量下载：不抢夺焦点跳页，同样受并发上限约束 */
    fun enqueueDownload(ctx: Context, url: String, rawName: String, officialSha: String? = null, sizeHint: Long = -1L) {
        val job = DlJob(newDlId(), Fetch.safeName(rawName))
        try {
            downloads.add(0, job)
            submitDownload(job) { queuedIfAlive(job) { runDownload(ctx, job, url, rawName, officialSha, sizeHint = sizeHint) } }
            persist(ctx)
        } catch (t: Throwable) {
            runCatching { job.failed = true; job.msg = "无法加入下载队列：" + (t.message ?: t.javaClass.simpleName) }
            toast(ctx, "无法加入下载队列：" + (t.message ?: t.javaClass.simpleName))
        }
    }

    /** 失败任务重试：复用同一任务对象，断点文件存在时从断点继续 */
    fun retryDownload(ctx: Context, job: DlJob) {
        if (job.kind == "pack") {
            val files = job.packFiles
            if (files == null || job.packSlug.isBlank()) {
                toast(ctx, "该打包任务无可重试的数据")
                return
            }
            submitDownload(job) { queuedIfAlive(job) { runPack(ctx, job, job.packSlug, job.packRef, files) } }
            persist(ctx)
            return
        }
        if (job.url.isBlank()) {
            toast(ctx, "该任务无可重试的链接")
            return
        }
        // 必须先提取已知总大小再清零 job.total：顺序颠倒会使传给引擎的总量恒为 -1，
        // 导致传给引擎的总量始终为 -1，重试后进度条退回「总大小未知」状态。
        val hint = if (job.total > 0) job.total else -1L
        // 不可在此处预置 running=true：并发已满时任务处于排队状态，
        // 该 true 会虚占一个并发位，导致自身及其他排队任务一同阻塞。
        job.done = false
        job.failed = false
        job.bytes = 0L
        job.total = -1L
        job.speed = 0L
        job.where = ""
        job.verify = ""
        job.startedAt = System.currentTimeMillis()
        job.msg = "正在重试…"
        // 通过队列提交：直接调用 runDownload 会绕过 MAX_CONCURRENT，使并发超限触发镜像站 429
        // 断点文件存在则以续传方式重试；服务端不接受 Range 时引擎会自行删除旧文件重下
        val canResume = Fetch.hasTemp(ctx, job.id, job.name)
        submitDownload(job) {
            queuedIfAlive(job) {
                runDownload(ctx, job, job.url, job.name, job.officialSha, resume = canResume, sizeHint = hint)
            }
        }
        persist(ctx)
    }

    /**
     * jsDelivr 整仓源码打包执行体：作为一条下载任务运行，支持暂停。
     * 暂停即中止打包（丢弃半成品），「继续」时重新打包整仓。
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
        Threads.start("重试任务") {
            try {
                val packed = ZipPack.pack(app, slug, ref, files,
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
                    job.where = packed.message.substringAfterLast('\n').trim()
                    job.whereUri = packed.uri?.toString().orEmpty()
                    job.msg = packed.message
                    parser.packMsg = packed.message
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
        }
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
     * 「清除已完成」：移除成功的记录并立即持久化到磁盘。
     * 若未执行持久化，重启后 DownloadStore 会重新读取这些记录，表现为「删除后再次出现」。
     */
    fun clearCompleted(ctx: Context) {
        val goneJobs = downloads.filter { it.done && !it.failed }
        val gone = goneJobs.map { it.id }.toSet()
        downloads.removeAll { gone.contains(it.id) }
        dlQueue.removeAll { gone.contains(it.first) }
        goneJobs.forEach { Fetch.dropTemp(ctx, it.id, it.name) }
        persist(ctx)
    }

    /** 删除勾选的下载任务条目（仅从列表移除记录，不删除磁盘上已下载完成的文件） */
    fun deleteSelectedDownloads(ctx: Context) {
        val ids = dlSel.toSet()
        if (ids.isEmpty()) {
            toast(ctx, "请先勾选要删除的任务")
            return
        }
        val gone = downloads.filter { ids.contains(it.id) }
        // 正在下载的任务被删除时：先中断传输。临时文件留给工作线程收尾时删除——
        // 此处直接删除会与仍在运行的工作线程竞争（后者会重新创建文件，反而遗留无用文件）。
        gone.filter { it.running }.forEach { pauseDownload(it) }
        downloads.removeAll { ids.contains(it.id) }
        // 排队中尚未启动的任务也需从队列中移除，否则前一个下载结束时该任务会自动启动
        dlQueue.removeAll { ids.contains(it.first) }
        gone.filter { !it.running }.forEach { Fetch.dropTemp(ctx, it.id, it.name) }
        dlSel.clear()
        dlMultiSelect = false
        toast(ctx, "已删除 ${ids.size} 条下载记录")
        persist(ctx)
    }
}
