package com.gh.dl.data

import android.content.Context
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
 * 不用系统 DownloadManager，因为要把文件直接写进用户授权的共享文件夹，
 * 同时要能实时报进度（已下载字节 / 总大小 / 速度），并在写入的同时算出 SHA256，
 * 这样无论写进共享文件夹还是 App 私有目录，都能拿到校验值。
 */
object Fetch {

    data class Saved(val path: String, val bytes: Long, val sha: String)

    /** 暂停专用异常：引擎据此识别为「已暂停」而非「失败」，调用方保留临时文件供续传 */
    class PausedException : RuntimeException("用户已暂停")

    /**
     * 流式下载并写到目标位置。
     * @param onProgress (已下载字节, 总字节或 -1, 瞬时速度 B/s)
     * @param cancelHolder 引擎启动时把「停止当前传输」的回调塞进来，供暂停按钮调用
     * @param resume true=续传（仅「应用目录」路径支持真正的 Range 断点续写；SAF 共享文件夹路径会重下）
     */
    fun download(
        ctx: Context,
        url: String,
        name: String,
        onProgress: (Long, Long, Long) -> Unit,
        cancelHolder: AtomicReference<(() -> Unit)?>,
        resume: Boolean = false,
        totalHint: Long = -1L
    ): Saved {
        val tree = Store.treeUri(ctx)
        // 仅「应用目录」路径支持断点续传；SAF 共享文件夹不支持追加写入，续传时直接重下
        val fileDir = File(ctx.getExternalFilesDir(null), "Download")
        if (!fileDir.exists()) fileDir.mkdirs()
        val targetFile = File(fileDir, name)
        val canResume = resume && tree == null && targetFile.exists()
        val resumeFrom = if (canResume) targetFile.length() else 0L

        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "gh-dl-android")
        if (resumeFrom > 0) reqBuilder.header("Range", "bytes=$resumeFrom-")

        val stopped = AtomicBoolean(false)
        cancelHolder.set { stopped.set(true) }

        val req = reqBuilder.build()
        Net.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("响应为空")
            // 206=断点续传成功；其余当作从头写
            val append = resumeFrom > 0 && resp.code == 206
            val directTotal = if (resp.code == 206) resumeFrom + body.contentLength().coerceAtLeast(0)
                             else body.contentLength()
            // 直接拿不到总大小时，用下载前 HEAD 探到的真实大小兜底（chunked 传输常拿不到）
            val total = if (directTotal > 0) directTotal else totalHint

            var out: OutputStream? = null
            var where = ""

            if (tree != null) {
                val dir = Store.subDir(ctx, "GitHub下载器")
                val fileUri = if (dir != null) Store.newFile(ctx, dir, name) else null
                if (fileUri != null) {
                    out = ctx.contentResolver.openOutputStream(fileUri)
                    where = "共享文件夹：${Store.displayName(ctx)}/GitHub下载器/$name"
                }
            }
            if (out == null) {
                if (append) {
                    out = FileOutputStream(targetFile, true)
                } else {
                    if (resumeFrom > 0) targetFile.delete() // 服务器不支持断点，重下
                    out = targetFile.outputStream()
                }
                where = "应用目录：${targetFile.absolutePath}"
            }

            var sum = resumeFrom
            var lastT = System.currentTimeMillis()
            var lastB = resumeFrom
            var speed = 0L
            val md = MessageDigest.getInstance("SHA-256")

            out.use { o ->
                body.byteStream().use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (stopped.get()) break
                        val n = ins.read(buf)
                        if (n <= 0) break
                        o.write(buf, 0, n)
                        md.update(buf, 0, n)
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
            // 用户主动暂停：保留已写部分（应用目录路径可被下次 Range 续传），不抛成失败
            if (stopped.get()) throw PausedException()
            onProgress(sum, total, speed)
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            return Saved(where, sum, sha)
        }
    }

    /** 文件名清洗，避免路径穿越 */
    fun safeName(name: String): String =
        name.replace('\\', '_').replace('/', '_').trim().ifBlank { "download.bin" }

    /** 计算文件 SHA256（用于把临时文件落盘后给出校验值） */
    fun sha256(file: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 探测文件的真实总大小（字节），用于进度条分母。
     * 优先 HEAD 拿 Content-Length；部分镜像/代理不支持 HEAD，则退化用 Range: bytes=0-0
     * 拿 Content-Range 里的全长。都拿不到返回 -1（调用方走 indeterminate）。
     * 与下载用同一个 Net.http（同样的代理/通道），所以拿到的就是实际要下的那个 URL 的大小。
     */
    fun headTotal(url: String): Long {
        return try {
            val head = Request.Builder().url(url).head()
                .header("User-Agent", "gh-dl-android").build()
            Net.http.newCall(head).execute().use { resp ->
                resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
                // HEAD 没给，退化到 Range 0-0：响应头 Content-Range: bytes 0-0/<总长>
                val range = Request.Builder().url(url)
                    .header("Range", "bytes=0-0")
                    .header("User-Agent", "gh-dl-android").build()
                Net.http.newCall(range).execute().use { r ->
                    val cr = r.header("Content-Range")
                    val m = cr?.let { Regex("""/(\d+)""").find(it) }
                    m?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
                }
            }
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * 把第三方引擎（OkDownload / Aria2）下到私有缓存的临时文件，搬到最终落盘位置：
     * 优先写进用户授权的共享文件夹（SAF），否则写 App 私有 Download 目录。
     * 复用与 [download] 同一套落盘逻辑，返回落盘位置与字节数、校验值。
     */
    fun commit(ctx: Context, src: java.io.File, name: String): Saved {
        val sha = sha256(src)
        val tree = Store.treeUri(ctx)
        var where = ""
        var written = false
        if (tree != null) {
            val dir = Store.subDir(ctx, "GitHub下载器")
            val fileUri = if (dir != null) Store.newFile(ctx, dir, name) else null
            if (fileUri != null) {
                ctx.contentResolver.openOutputStream(fileUri)?.use { os ->
                    src.inputStream().use { it.copyTo(os, 64 * 1024) }
                }
                where = "共享文件夹：${Store.displayName(ctx)}/GitHub下载器/$name"
                written = true
            }
        }
        if (!written) {
            val d = java.io.File(ctx.getExternalFilesDir(null), "Download")
            if (!d.exists()) d.mkdirs()
            val f = java.io.File(d, name)
            src.inputStream().use { it.copyTo(f.outputStream(), 64 * 1024) }
            where = "应用目录：${f.absolutePath}"
        }
        return Saved(where, src.length(), sha)
    }
}
