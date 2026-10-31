package com.gh.dl.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 带进度的下载器。
 *
 * 不采用系统 DownloadManager，原因：需将文件直接写入用户授权的共享文件夹，
 * 同时需实时报告进度（已下载字节 / 总大小 / 速度），并在写入的同时计算 SHA256，
 * 无论最终写入共享文件夹还是 App 私有目录，均可获得校验值。
 */
object Fetch {

    /**
     * [uri] 为落盘文件的可访问地址（SAF 文档 URI 或 FileProvider URI），
     * 供下载页「安装 / 打开」使用；仅作展示信息时可为空。
     */
    data class Saved(val path: String, val bytes: Long, val sha: String, val uri: Uri? = null)

    /** Range 请求头：断点续传时告知服务器从何处继续；无断点则不发送 */
    internal fun rangeHeader(resumeFrom: Long): String? =
        if (resumeFrom > 0) "bytes=$resumeFrom-" else null

    /** 服务器是否接受断点续传：仅当客户端请求了续传且服务器返回 206 时为 true */
    internal fun resumeAccepted(resumeFrom: Long, code: Int): Boolean =
        resumeFrom > 0 && code == 206


    /** 暂停专用异常。引擎据此识别为「已暂停」而非「失败」，调用方保留临时文件供续传 */
    class PausedException : RuntimeException("用户已暂停")

    /**
     * 流式下载：先写入 App 私有临时文件（支持 Range 断点续写），
     * 完成后由 [commit] 移至最终落盘位置（SAF 共享文件夹 / 应用目录）。
     *
     * 中转原因：SAF 的 Uri 不支持追加写入。此前「直接写 SAF」的实现导致
     * canResume 恒为 false，即点击「继续」每次都从头重下，而界面显示「可断点续传」。
     * 当前三个引擎（单线程 / OkDownload / Aria2）的临时文件模型一致，续传功能实际生效；
     * SHA256 始终对完整文件计算（而非仅对续传新增部分计算）。
     *
     * 代价：大文件临时占用两倍磁盘空间（临时文件 + 成品），完成后删除临时文件。
     *
     * @param onProgress (已下载字节, 总字节或 -1, 瞬时速度 B/s)
     * @param cancelHolder 引擎启动时写入「停止当前传输」的回调，供暂停按钮调用
     * @param resume true=续传（保留临时文件，用 Range 从断点继续写入）
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
    ): Saved {
        val tmpDir = File(ctx.cacheDir, "dltmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()
        val targetFile = File(tmpDir, tmpName(jobKey, name))
        val canResume = resume && targetFile.exists() && targetFile.length() > 0L
        val resumeFrom = if (canResume) targetFile.length() else 0L

        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "gh-dl-android")
        val rh = rangeHeader(resumeFrom)
        if (rh != null) reqBuilder.header("Range", rh)


        val stopped = AtomicBoolean(false)
        cancelHolder.set { stopped.set(true) }

        val req = reqBuilder.build()
        var sum = resumeFrom
        var total = -1L
        var speed = 0L
        Net.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("响应为空")
            // 206=断点续传成功；其余状态码按从头写入处理
            val append = resumeAccepted(resumeFrom, resp.code)

            val directTotal = if (resp.code == 206) resumeFrom + body.contentLength().coerceAtLeast(0)
                             else body.contentLength()
            // 直接获取不到总大小时，以下载前 HEAD 探测的真实大小作为备用（chunked 传输常无法获取）
            total = if (directTotal > 0) directTotal else totalHint

            // 统一先写入私有临时文件；最终落盘位置由 commit() 决定（SAF 或应用目录）。
            // 服务器未接受 Range（未返回 206）：删除半截文件从头写入，否则新旧字节拼接将产生损坏文件
            if (resumeFrom > 0L && !append) targetFile.delete()
            val out: OutputStream = if (append) FileOutputStream(targetFile, true)
                                    else targetFile.outputStream()

            var lastT = System.currentTimeMillis()
            var lastB = resumeFrom

            out.use { o ->
                body.byteStream().use { ins ->
                    val buf = ByteArray(64 * 1024)
                    var zeros = 0
                    while (true) {
                        if (stopped.get()) break
                        val n = ins.read(buf)
                        // 仅将 -1 视为 EOF：InputStream 的契约允许 read 返回 0
                        // （非阻塞源或某些包装流），按 <=0 退出会导致**数据静默缺失**，
                        // 且总大小未知时（codeload chunked）连「下载不完整」的备用检测都不会触发
                        if (n < 0) break
                        // 零读数需设置退出上限，否则「某包装流始终返回 0」将导致
                        // 一个既不报错也不结束的忙循环（表现为下载进度始终 0%，且占满一个 CPU 核心）
                        if (n == 0) {
                            if (++zeros > 1000) throw RuntimeException("读取无进展，已中止")
                            continue
                        }
                        zeros = 0
                        o.write(buf, 0, n)
                        sum += n
                        val now = System.currentTimeMillis()
                        if (now - lastT >= 500) {
                            speed = (sum - lastB) * 1000 / (now - lastT).coerceAtLeast(1)
                            lastT = now
                            lastB = sum
                            onProgress(sum, total, speed)
                        }
                    }
                }
                o.flush()
            }
            // 用户主动暂停：保留临时文件，下次使用 Range 从断点继续写入
            if (stopped.get()) throw PausedException()
        }
        // 总大小已知却未收满：连接被中途断开而未抛出异常，此时落盘为损坏文件，
        // 且其 SHA256 会「校验不通过」，误导用户以为文件被篡改。因此抛出异常要求重试。
        if (total > 0L && sum < total) throw RuntimeException("下载不完整（${sum}/${total} 字节），请重试")
        onProgress(sum, total, speed)
        // 校验值统一由 commit 对完整文件重新计算：续传时流式哈希仅覆盖本次新增字节。
        return commit(ctx, targetFile, name)
    }

    /**
     * 任务被删除时清除其断点临时文件（扫描三个引擎的目录）。
     * 若不清除：用户删除一个已完成 80% 的任务后，该半截大文件将持续占用 cacheDir，
     * 除非系统执行「清理存储」，否则将持续累积，而这些任务已无法继续续传。
     */
    fun dropTemp(ctx: Context, jobKey: Long, name: String) {
        val n = tmpName(jobKey, name)
        for (d in listOf("dltmp", "oktmp", "ariatmp")) {
            java.io.File(File(ctx.cacheDir, d), n).delete()
            java.io.File(File(ctx.cacheDir, d), "$n.aria2").delete()
        }
    }

    /**
     * 临时文件名带任务 id 前缀：并发下载的多个任务可能同名（例如同一个仓库的两个
     * Release 均命名为 app.apk），共用临时文件将相互覆盖字节，续传时更会将两条
     * 任务的断点混合为损坏文件。最终落盘仍使用原始 [name]。
     */
    fun tmpName(jobKey: Long, name: String): String =
        if (jobKey > 0L) "$jobKey-$name" else name

    /** 断点临时文件是否存在且非空，用于决定重试能否续传 */
    fun hasTemp(ctx: Context, jobKey: Long, name: String): Boolean =
        File(ctx.cacheDir, tmpName(jobKey, name)).let { it.exists() && it.length() > 0L }

    /** 文件名清洗，防止路径穿越 */
    fun safeName(name: String): String =
        name.replace('\\', '_').replace('/', '_').trim().ifBlank { "download.bin" }

    /** 计算文件 SHA256（用于临时文件落盘后提供校验值） */
    fun sha256(file: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            var zeros = 0
            while (true) {
                val n = ins.read(buf)
                // 与 download 相同：0 不是 EOF。此处出错的最坏情况是**给出错误的校验值**，
                // 用户会误以为文件被篡改，比直接报错更难排查
                if (n < 0) break
                // 与 download 相同的退出上限，避免忙循环滞留
                // 此处不可「跳出循环计算残缺哈希」，那等同于凭空给出错误的校验值，
                // 用户会误以为下载的文件被篡改。因此抛出异常说明计算失败。
                if (n == 0) {
                    if (++zeros > 1000) throw java.io.IOException("读取无进展，SHA256 计算中止")
                    continue
                }
                zeros = 0
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Content-Range: bytes 0-0/<总长> 中的总长 */
    private val RANGE_TOTAL = Regex("""/(\d+)""")

    /**
     * 探测文件的实际总大小（字节），用于进度条分母。
     * 优先使用 HEAD 获取 Content-Length；部分镜像/代理不支持 HEAD，则退化为使用 Range: bytes=0-0
     * 从 Content-Range 获取全长。均无法获取时返回 -1（调用方按 indeterminate 处理）。
     * 与下载共用同一个 Net.http（相同的代理/通道），因此获取的即为实际下载 URL 的大小。
     */
    fun headTotal(url: String): Long {
        return try {
            val head = Request.Builder().url(url).head()
                .header("User-Agent", "gh-dl-android").build()
            Net.http.newCall(head).execute().use { resp ->
                resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
                // HEAD 未返回，退化为 Range 0-0：响应头 Content-Range: bytes 0-0/<总长>
                val range = Request.Builder().url(url)
                    .header("Range", "bytes=0-0")
                    .header("User-Agent", "gh-dl-android").build()
                Net.http.newCall(range).execute().use { r ->
                    val cr = r.header("Content-Range")
                    val m = cr?.let { RANGE_TOTAL.find(it) }
                    m?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
                }
            }
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * 将下载到私有缓存的临时文件移至最终落盘位置，并对完整文件计算 SHA256：
     * 已授权共享文件夹时写入 GitHub下载器/；否则写入 App 私有 Download 目录。
     * 三个引擎共用此逻辑，落盘位置与校验值语义完全一致。
     *
     * 授权失效时直接报错，不可静默改写到私有目录，否则用户按界面提示去共享文件夹
     * 查找文件却找不到，比一次明确的失败更难排查。
     */
    fun commit(ctx: Context, src: java.io.File, name: String): Saved {
        val sha = sha256(src)
        val bytes = src.length()
        val tree = Store.treeUri(ctx)
        if (tree != null) {
            val dir = Store.subDir(ctx, "GitHub下载器")
            val fileUri = dir?.let { Store.newFile(ctx, it, name) }
            if (fileUri == null) {
                throw RuntimeException(
                    "无法在共享文件夹「${Store.displayName(ctx)}/GitHub下载器」创建 $name，" +
                        "请前往「设置 → 下载」重新选择文件夹"
                )
            }
            val os = ctx.contentResolver.openOutputStream(fileUri)
                ?: throw RuntimeException(
                    "共享文件夹不可写：${Store.displayName(ctx)}/GitHub下载器/$name，" +
                        "请前往「设置 → 下载」重新授权"
                )
            os.use { out -> src.inputStream().use { ins -> ins.copyTo(out, 64 * 1024) } }
            src.delete()
            return Saved("共享文件夹：${Store.displayName(ctx)}/GitHub下载器/$name", bytes, sha, fileUri)
        }
        val d = Dirs.external(ctx, "Download")
        val f = java.io.File(d, name)
        f.outputStream().use { out -> src.inputStream().use { ins -> ins.copyTo(out, 64 * 1024) } }
        src.delete()
        // 应用外部目录中的文件经 FileProvider 暴露给系统安装器 / 文件查看器，
        // 不申请存储权限，也不把文件复制到公共目录
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        }.getOrNull()
        return Saved("应用目录：${f.absolutePath}", bytes, sha, uri)
    }
}
