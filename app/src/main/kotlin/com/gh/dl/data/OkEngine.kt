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
 * 与单线程 [Fetch.download] 的区别：把文件切成多个分片、开 [CONN] 个连接并行拉取，
 * 再把分片拼起来 —— 对支持 HTTP Range 的源（GitHub Release、jsDelivr、高校镜像）提速明显。
 *
 * OkDownload 只能写本地文件，所以先下到 App 私有缓存，再用 [Fetch.commit] 搬到
 * 共享文件夹 / 私有目录（与单线程走同一套落盘 + SHA256 校验）。
 *
 * 进度获取（关键坑）：OkDownload 1.0.7 在下载过程中把各分片写在隐藏的 block 文件里，
 * **最终的目标文件要等全部完成后才组装**；所以「轮询目标文件大小」在结束前一直是 0、
 * 结束时瞬间满，表现为「0% 直接跳到 100%」。改用 [DownloadListener.fetchProgress]
 * 回调里累加每次增量字节（increaseBytes）作为真实已下载量，进度就平滑且正确了。
 *
 * 总大小：单分片 [DownloadListener.fetchStart] 只给「该分片」长度（= total/CONN），
 * 不足以当天当总量；优先用 [totalHint]（下载前 HEAD/Range 探到的真实 Content-Length），
 * 分片求和只在 hint 缺失时兜底。
 *
 * 完成判定：只有 [EndCause.COMPLETED] 才提交文件；否则（网络中断/服务器不支持分片）
 * 直接抛明确错误，绝不把残缺文件当成功落盘。
 */
object OkEngine {

    /** 单文件分片连接数。镜像站有限流，太大会触发 429，3~4 是稳妥值 */
    private const val CONN = 4

    @Volatile
    private var inited = false

    @Synchronized
    private fun ensure(ctx: Context) {
        if (!inited) {
            try {
                OkDownload.Builder(ctx.applicationContext).build()
            } catch (_: Throwable) {
                // 已有实例则忽略；初始化失败在首次 execute 时会暴露
            }
            inited = true
        }
    }

    /**
     * 用 OkDownload 下载 [url] 到 [name]。
     * @param onProgress (已下载字节, 总字节或 -1, 瞬时速度 B/s)
     * @param cancelHolder 引擎启动时把「停止当前传输」的回调塞进来，供暂停按钮调用
     * @param resume true=续传（保留上次暂停留下的临时文件，OkDownload 自动从断点继续）
     * @param totalHint 下载前探到的真实总大小（字节），作为进度分母基准
     */
    fun download(
        ctx: Context,
        url: String,
        name: String,
        onProgress: (Long, Long, Long) -> Unit,
        cancelHolder: AtomicReference<(() -> Unit)?>,
        resume: Boolean = false,
        totalHint: Long = -1L
    ): Fetch.Saved {
        ensure(ctx)
        val tmpDir = File(ctx.cacheDir, "oktmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()
        val tmp = File(tmpDir, name)
        // 续传：保留临时文件；否则清掉旧残留，避免误续传
        if (tmp.exists() && !resume) tmp.delete()

        val task = DownloadTask.Builder(url, tmpDir.absolutePath, name)
            .setConnectionCount(CONN)
            .build()

        // 真实总大小：优先 totalHint；hint 缺失时，等所有分片 fetchStart 都上报后求和兜底
        val totalLen = AtomicLongHolder(if (totalHint > 0) totalHint else -1L)
        val blockMap = mutableMapOf<Int, Long>()
        fun recompute() {
            if (totalLen.get() > 0) return // hint 已给出就别用分片求和覆盖
            if (blockMap.size >= CONN) {
                val s = blockMap.values.sum()
                if (s > 0) totalLen.set(s)
            }
        }
        // 真实已下载字节：累加 fetchProgress 的每次增量
        val downloaded = AtomicLongHolder(0L)
        var lastT = System.currentTimeMillis()
        var lastB = 0L
        fun report(force: Boolean = false) {
            val now = System.currentTimeMillis()
            val b = downloaded.get()
            if (force || now - lastT >= 300) {
                val sp = if (now > lastT) (b - lastB) * 1000 / (now - lastT) else 0L
                lastT = now
                lastB = b
                onProgress(b, totalLen.get(), sp.coerceAtLeast(0L))
            }
        }

        val completed = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val errRef = AtomicReference<Exception?>(null)

        // 把「停止」回调交给调用方（暂停按钮用）
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

                /** 真正可靠的进度来源：每次回调给的是本分片本次新增的字节数，累加即得真实已下量 */
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

        // 用户主动暂停：保留临时文件（下次续传），不落盘、不抛成「失败」
        if (cancelled.get()) throw Fetch.PausedException()
        // 真正的失败（网络中断 / 服务器不支持分片 / 异常）：抛出明确错误
        errRef.get()?.let { throw it }
        // 没 COMPLETED 但也没异常（极少）—— 把残缺文件当失败，绝不静默提交
        if (!completed.get()) {
            if (!resume) tmp.delete()
            throw RuntimeException("下载未完成（连接中断或服务器不支持分片），可改用 Aria2 / 单线程")
        }

        // 完成时再确认一次最终大小与字节，进度拉满
        val t = totalLen.get()
        if (t > 0) onProgress(t, t, 0L) else onProgress(downloaded.get(), -1L, 0L)

        val saved = Fetch.commit(ctx, tmp, name)
        tmp.delete()
        return saved
    }

    /** 小工具：让 Long 能在匿名内部类里读写 */
    private class AtomicLongHolder(@Volatile var value: Long) {
        fun get() = value
        fun set(v: Long) {
            value = v
        }

        fun addAndGet(delta: Long): Long {
            value += delta
            return value
        }
    }
}
