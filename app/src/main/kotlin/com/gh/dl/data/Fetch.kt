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
     * 流式下载：先写 App 私有的临时文件（支持 Range 断点续写），
     * 完成后再由 [commit] 搬到最终落盘位置（SAF 共享文件夹 / 应用目录）。
     *
     * 为什么要中转：SAF 的 Uri 不能追加写，之前「直接写 SAF」的写法让
     * canResume 恒为 false —— 点「继续」每次都从头重下，而界面写着「可断点续传」。
     * 现在三个引擎（单线程 / OkDownload / Aria2）的临时文件模型一致，续传真的生效；
     * SHA256 也一定是对完整文件算的（不是只对续传那一段算）。
     *
     * 代价：大文件临时占两倍磁盘（临时 + 成品），完成即删临时文件。
     *
     * @param onProgress (已下载字节, 总字节或 -1, 瞬时速度 B/s)
     * @param cancelHolder 引擎启动时把「停止当前传输」的回调塞进来，供暂停按钮调用
     * @param resume true=续传（保留临时文件，用 Range 从断点接着写）
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
        if (resumeFrom > 0) reqBuilder.header("Range", "bytes=$resumeFrom-")

        val stopped = AtomicBoolean(false)
        cancelHolder.set { stopped.set(true) }

        val req = reqBuilder.build()
        var sum = resumeFrom
        var total = -1L
        var speed = 0L
        Net.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("响应为空")
            // 206=断点续传成功；其余当作从头写
            val append = resumeFrom > 0 && resp.code == 206
            val directTotal = if (resp.code == 206) resumeFrom + body.contentLength().coerceAtLeast(0)
                             else body.contentLength()
            // 直接拿不到总大小时，用下载前 HEAD 探到的真实大小兜底（chunked 传输常拿不到）
            total = if (directTotal > 0) directTotal else totalHint

            // 一律先写私有临时文件；最终落盘位置由 commit() 决定（SAF 或应用目录）。
            // 服务器没接受 Range（没回 206）：丢掉半截文件从头写，否则新旧字节拼起来是坏文件
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
                        // 只把 -1 当 EOF：InputStream 的合同允许 read 返回 0
                        // （非阻塞源、或某些包装流），按 <=0 退出会**静悄悄少下一截**，
                        // 而总大小未知时（codeload chunked）连「下载不完整」那道兜底都不会触发
                        if (n < 0) break
                        // 0 次读数要有出口上限，否则「某个包装流每次都返回 0」会变成
                        // 一个既不报错也不结束的忙循环（表现是下载永远 0%，还吃满一个核）
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
            // 用户主动暂停：保留临时文件，下次用 Range 从断点接着写
            if (stopped.get()) throw PausedException()
        }
        // 总大小已知却没收满：连接被中途掐断而没抛异常，这时落盘就是坏文件，
        // 且它的 SHA256 会「校验不通过」误导用户以为被篡改。宁可报错重下。
        if (total > 0L && sum < total) throw RuntimeException("下载不完整（${sum}/${total} 字节），请重试")
        onProgress(sum, total, speed)
        // 校验值一律由 commit 对完整文件重算：续传时流式哈希只覆盖本次新增的字节。
        return commit(ctx, targetFile, name)
    }

    /**
     * 任务被删除时清掉它的断点临时文件（三个引擎的目录都扫一遍）。
     * 不清的话：用户删掉一个下了 80% 的任务，那半截大文件会一直躺在 cacheDir，
     * 除非系统「清理存储」，否则越积越多——而这些任务已经不可能再续传了。
     */
    fun dropTemp(ctx: Context, jobKey: Long, name: String) {
        val n = tmpName(jobKey, name)
        for (d in listOf("dltmp", "oktmp", "ariatmp")) {
            java.io.File(File(ctx.cacheDir, d), n).delete()
            java.io.File(File(ctx.cacheDir, d), "$n.aria2").delete()
        }
    }

    /**
     * 临时文件名带任务 id 前缀：并发下载的多个任务可能同名（例如同一仓库两个
     * Release 都叫 app.apk），共用一个临时文件会互相踩字节，续传时更会把两条
     * 任务的断点混成一个坏文件。最终落盘仍用干净的 [name]。
     */
    fun tmpName(jobKey: Long, name: String): String =
        if (jobKey > 0L) "$jobKey-$name" else name

    /** 文件名清洗，避免路径穿越 */
    fun safeName(name: String): String =
        name.replace('\\', '_').replace('/', '_').trim().ifBlank { "download.bin" }

    /** 计算文件 SHA256（用于把临时文件落盘后给出校验值） */
    fun sha256(file: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            var zeros = 0
            while (true) {
                val n = ins.read(buf)
                // 同 download：0 不是 EOF。这里出错最坏是**给出一个错的校验值**，
                // 用户会以为文件被篡改，比报错难解释得多
                if (n < 0) break
                // 同 download 的出口上限，别让忙循环卡在这里
                // 这里不能「跳出循环算个残缺哈希」——那等于凭空给出一个错的校验值，
                // 用户会以为下载的文件被篡改。宁可抛异常说算不出来。
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
     * 把下载到私有缓存的临时文件搬到最终落盘位置，并对完整文件算 SHA256：
     * 已授权共享文件夹 → 写进 GitHub下载器/；否则写 App 私有 Download 目录。
     * 三个引擎共用这一段，落盘位置与校验值语义完全一致。
     *
     * 授权失效时直接报错，绝不悄悄改写到私有目录——用户按界面提示去共享文件夹
     * 找文件却找不到，比一次明确的失败更难排查。
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
                        "请到 设置 → 下载 重新选择文件夹"
                )
            }
            val os = ctx.contentResolver.openOutputStream(fileUri)
                ?: throw RuntimeException(
                    "共享文件夹不可写：${Store.displayName(ctx)}/GitHub下载器/$name，" +
                        "请到 设置 → 下载 重新授权"
                )
            os.use { out -> src.inputStream().use { ins -> ins.copyTo(out, 64 * 1024) } }
            src.delete()
            return Saved("共享文件夹：${Store.displayName(ctx)}/GitHub下载器/$name", bytes, sha)
        }
        val d = java.io.File(ctx.getExternalFilesDir(null), "Download")
        if (!d.exists()) d.mkdirs()
        val f = java.io.File(d, name)
        f.outputStream().use { out -> src.inputStream().use { ins -> ins.copyTo(out, 64 * 1024) } }
        src.delete()
        return Saved("应用目录：${f.absolutePath}", bytes, sha)
    }
}
