package com.gh.dl.data

import android.content.Context
import com.liulishuo.okdownload.DownloadListener
import com.liulishuo.okdownload.DownloadTask
import com.liulishuo.okdownload.OkDownload
import com.liulishuo.okdownload.core.breakpoint.BreakpointInfo
import com.liulishuo.okdownload.core.cause.EndCause
import com.liulishuo.okdownload.core.cause.ResumeFailedCause
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * OkDownload 多线程分片下载引擎（纯 JVM，无原生依赖）。
 *
 * 与单线程 [Fetch.download] 的区别：将文件切分为多个分片、开启 [CONN] 个连接并行拉取，
 * 再拼接分片 —— 对支持 HTTP Range 的源（GitHub Release、jsDelivr、高校镜像）提速显著。
 *
 * OkDownload 仅支持写入本地文件，因此先下载至 App 私有缓存，再通过 [Fetch.commit] 转移至
 * 共享文件夹 / 私有目录（与单线程使用同一套落盘 + SHA256 校验流程）。
 *
 * 进度获取（关键难点）：OkDownload 1.0.7 在下载过程中将各分片写入隐藏的 block 文件，
 * **最终目标文件需等待全部完成后才组装**；因此「轮询目标文件大小」在结束前始终为 0、
 * 结束时瞬间满，表现为「0% 直接跳至 100%」。改用 [DownloadListener.fetchProgress]
 * 回调中累加每次增量字节（increaseBytes）作为真实已下载量，进度即平滑且正确。
 *
 * 总大小：单分片 [DownloadListener.fetchStart] 仅提供「该分片」长度（= total/CONN），
 * 不足以作为总量；优先使用 [totalHint]（下载前 HEAD/Range 探测到的真实 Content-Length），
 * 分片求和仅在 hint 缺失时作为回退。
 *
 * 完成判定：仅 [EndCause.COMPLETED] 时提交文件；否则（网络中断/服务器不支持分片）
 * 直接抛出明确错误，绝不将残缺文件作为成功落盘。
 */
object OkEngine {

    /** 单文件分片连接数。镜像站有限流机制，过大会触发 429，3~4 为稳妥值 */
    private const val CONN = 4

    @Volatile
    private var inited = false

    @Synchronized
    private fun ensure(ctx: Context) {
        if (inited) return
        try {
            OkDownload.Builder(ctx.applicationContext).build()
            inited = true
        } catch (t: Throwable) {
            // 已存在实例时 Builder.build() 抛 IllegalStateException，视为初始化完成；
            // 其余异常不置位，否则一次偶发失败会让本进程内该引擎永久不可用
            if (t is IllegalStateException) inited = true
            else android.util.Log.w("OkEngine", "OkDownload 初始化失败，下次调用重试", t)
        }
    }

    /**
     * 使用 OkDownload 下载 [url] 至 [name]。
     * @param onProgress (已下载字节, 总字节或 -1, 瞬时速度 B/s)
     * @param cancelHolder 引擎启动时写入「停止当前传输」的回调，供暂停按钮调用
     * @param resume true=续传（保留上次暂停留下的临时文件，OkDownload 自动从断点继续）
     * @param totalHint 下载前探测到的真实总大小（字节），作为进度分母基准
     */
    fun download(
        ctx: Context,
        url: String,
        name: String,
        onProgress: (Long, Long, Long) -> Unit,
        cancelHolder: AtomicReference<(() -> Unit)?>,
        resume: Boolean = false,
        totalHint: Long = -1L,
        jobKey: Long = 0L
    ): Fetch.Saved {
        ensure(ctx)
        val tmpDir = File(ctx.cacheDir, "oktmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()
        val tmp = File(tmpDir, Fetch.tmpName(jobKey, name))
        // 续传：保留临时文件；否则清除旧残留，避免误续传
        if (tmp.exists() && !resume) tmp.delete()

        val task = DownloadTask.Builder(url, tmpDir.absolutePath, tmp.name)
            .setConnectionCount(CONN)
            .build()

        // 真实总大小：优先使用 totalHint；hint 缺失时，等待所有分片 fetchStart 上报后求和作为回退
        val totalLen = AtomicLongHolder(if (totalHint > 0) totalHint else -1L)
        // CONN 个分片的回调来自不同线程：普通 mutableMapOf 并发写入会抛出
        // ConcurrentModificationException，导致下载中途崩溃。
        val blockMap = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        fun recompute() {
            if (totalLen.get() > 0) return // hint 已提供，不使用分片求和覆盖
            if (blockMap.size >= CONN) {
                val s = blockMap.values.sum()
                if (s > 0) totalLen.set(s)
            }
        }
        // 真实已下载字节：累加 fetchProgress 的每次增量
        val downloaded = AtomicLongHolder(0L)
        // Kotlin 的 @Volatile 仅能标注字段，局部变量使用 AtomicLong 替代
        val lastT = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val lastB = java.util.concurrent.atomic.AtomicLong(0L)
        // 多个分片线程同时回调，节流判断与上报必须串行化：
        // 否则 addAndGet 的读改写窗口会丢失字节（进度偏小），lastT/lastB 亦会被交叉写坏。
        val reportLock = Any()
        fun report(force: Boolean = false) {
            synchronized(reportLock) {
                val now = System.currentTimeMillis()
                val b = downloaded.get()
                val t = lastT.get()
                if (force || now - t >= 300) {
                    val sp = if (now > t) (b - lastB.get()) * 1000 / (now - t) else 0L
                    lastT.set(now)
                    lastB.set(b)
                    onProgress(b, totalLen.get(), sp.coerceAtLeast(0L))
                }
            }
        }

        val completed = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val errRef = AtomicReference<Exception?>(null)

        // 将「停止」回调交予调用方（暂停按钮使用）
        cancelHolder.set { task.cancel() }

        try {
            task.execute(object : DownloadListener {
                override fun taskStart(task: DownloadTask) {}
                override fun connectTrialStart(
                    task: DownloadTask,
                    headers: Map<String, List<String>>
                ) {}

                override fun connectTrialEnd(
                    task: DownloadTask,
                    code: Int,
                    headers: Map<String, List<String>>
                ) {}

                override fun downloadFromBeginning(
                    task: DownloadTask,
                    info: BreakpointInfo,
                    cause: ResumeFailedCause
                ) {
                    var s = 0L
                    for (i in 0 until info.blockCount) s += info.getBlock(i).contentLength
                    if (s > 0) {
                        val cur = totalLen.get()
                        if (cur <= 0) totalLen.set(s)
                    }
                }

                override fun downloadFromBreakpoint(task: DownloadTask, info: BreakpointInfo) {
                    var s = 0L
                    for (i in 0 until info.blockCount) s += info.getBlock(i).contentLength
                    if (s > 0) {
                        val cur = totalLen.get()
                        if (cur <= 0) totalLen.set(s)
                    }
                }

                override fun connectStart(
                    task: DownloadTask,
                    index: Int,
                    headers: Map<String, List<String>>
                ) {}

                override fun connectEnd(
                    task: DownloadTask,
                    index: Int,
                    code: Int,
                    headers: Map<String, List<String>>
                ) {}

                override fun fetchStart(task: DownloadTask, index: Int, totalLength: Long) {
                    if (totalLength > 0) {
                        blockMap[index] = totalLength
                        recompute()
                    }
                }

                /** 可靠的进度来源：每次回调提供本分片本次新增的字节数，累加即得真实已下载量 */
                override fun fetchProgress(task: DownloadTask, index: Int, increaseBytes: Long) {
                    if (increaseBytes > 0) {
                        downloaded.addAndGet(increaseBytes)
                        report()
                    }
                }

                override fun fetchEnd(task: DownloadTask, index: Int, bytes: Long) {}
                override fun taskEnd(task: DownloadTask, cause: EndCause, realCause: Exception?) {
                    if (cause == EndCause.CANCELED) {
                        cancelled.set(true)
                    } else {
                        completed.set(cause == EndCause.COMPLETED)
                        if (cause != EndCause.COMPLETED && realCause != null) errRef.set(realCause)
                    }
                }
            })
        } catch (e: Exception) {
            errRef.set(e)
        }

        // 用户主动暂停：保留临时文件（供下次续传），不落盘、不视为「失败」
        if (cancelled.get()) throw Fetch.PausedException()
        // 实际失败（网络中断 / 服务器不支持分片 / 异常）：抛出明确错误
        errRef.get()?.let { throw it }
        // 未 COMPLETED 但无异常（极少出现）—— 将残缺文件视为失败，绝不静默提交
        if (!completed.get()) {
            if (!resume) tmp.delete()
            throw RuntimeException("下载未完成（连接中断或服务器不支持分片），可改用 Aria2 / 单线程")
        }

        // 完成时再次确认最终大小与字节数，进度置满
        val t = totalLen.get()
        if (t > 0) onProgress(t, t, 0L) else onProgress(downloaded.get(), -1L, 0L)

        val saved = Fetch.commit(ctx, tmp, name)
        tmp.delete()
        return saved
    }

    /**
     * 辅助工具：使 Long 能在匿名内部类中读写。
     * value 仅作为 get/set 的存储；累加通过底层 AtomicLong 执行，
     * 因 `value += delta` 为读-改-写三步操作，并发下会丢失增量。
     */
    private class AtomicLongHolder(initial: Long) {
        private val ref = java.util.concurrent.atomic.AtomicLong(initial)

        @Volatile var value: Long = initial
            private set

        fun get(): Long = ref.get()

        fun set(v: Long) {
            ref.set(v)
            value = v
        }

        fun addAndGet(delta: Long): Long {
            val n = ref.addAndGet(delta)
            value = n
            return n
        }
    }
}
