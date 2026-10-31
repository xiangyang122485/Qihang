package com.gh.dl.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * jsDelivr 源码通道。
 * 此为合法公共 CDN，缓存开源仓库的公开文件；在 GitHub 直连不可达的网络环境下仍可用，
 * 因此作为源码获取的回退通道：列版本 → 列文件树 → 逐文件下载 → 本地打包 zip。
 */
object Jsd {

    private const val DATA = "https://data.jsdelivr.com/v1/packages/gh/"
    private const val CDN = "https://cdn.jsdelivr.net/gh/"

    data class Entry(val path: String, val size: Long)

    /** 仓库的所有版本（tag）；[timeoutMs] > 0 时为单次请求设置整体超时 */
    fun versions(slug: String, timeoutMs: Long = 0): List<String> {
        val txt = Net.get(DATA + slug, timeoutMs = timeoutMs)
        val arr = JSONObject(txt).optJSONArray("versions") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i)?.optString("version").orEmpty()
            if (v.isNotBlank()) out.add(v)
        }
        return out
    }

    /** 指定版本的文件树；[timeoutMs] > 0 时为单次请求设置整体超时 */
    fun tree(slug: String, ref: String, timeoutMs: Long = 0): List<Entry> {
        val txt = Net.get(DATA + slug + "@" + ref + "?structure=flat", timeoutMs = timeoutMs)
        val arr = JSONObject(txt).optJSONArray("files") ?: return emptyList()
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = o.optString("name").removePrefix("/")
            out.add(Entry(p, o.optLong("size")))
        }
        return out
    }

    /**
     * 逐段百分号编码后拼接：文件名含空格、中文等字符时，
     * 直接拼接会生成非法请求路径，表现为整仓打包中部分文件恒定失败。
     */
    fun fileUrl(slug: String, ref: String, path: String): String =
        "$CDN$slug@$ref/" + path.split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}

/** 将整仓文件下载并打包为 zip，保存至系统「下载」目录 */
object ZipPack {

    /** 打包结果：展示文本与文件可访问 URI（供下载页「打开」使用） */
    data class PackResult(val message: String, val uri: Uri?)

    /** compareAndSet 循环占用额度，超限返回 false 且不产生累加 */
    private fun reserve(total: java.util.concurrent.atomic.AtomicLong, n: Long): Boolean {
        while (true) {
            val cur = total.get()
            if (cur + n > MAX_TOTAL_BYTES) return false
            if (total.compareAndSet(cur, cur + n)) return true
        }
    }

    private const val MAX_FILES = 4000
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    /** 并发下载时先暂存于内存，因此设置总量上限以避免 OOM */
    private const val MAX_TOTAL_BYTES = 150L * 1024 * 1024

    /**
     * 整仓打包。
     * @param cancelHolder 用户暂停时由外部置 true，本函数检测后中止打包并抛出
     *        [Fetch.PausedException]，由调用方将任务标记为「已暂停」。
     * @param onProgress 进度回调（已下载字节, 总字节），用于下载页进度条。
     */
    fun pack(
        ctx: Context,
        slug: String,
        ref: String,
        files: List<Jsd.Entry>,
        onProgress: (Long, Long) -> Unit,
        cancelHolder: AtomicReference<Boolean>
    ): PackResult {
        val list = files.take(MAX_FILES)
        if (list.isEmpty()) return PackResult("无可下载文件", null)
        val name = slug.replace("/", "-") + "-" + ref + ".zip"
        val tmp = File(ctx.cacheDir, name)
        val n = list.size
        val totalHint = list.sumOf { it.size }

        // 并发下载（串行下载 800 余文件需数分钟）
        val threads = 12
        val pool = Executors.newFixedThreadPool(threads)
        val buf = arrayOfNulls<ByteArray>(n)
        val latch = CountDownLatch(n)
        val doneCount = AtomicInteger(0)
        val totalBytes = AtomicLong(0)
        val failed = AtomicInteger(0)

        for (i in 0 until n) {
            // 用户暂停：不再提交新任务，剩余任务直接放行计数
            if (cancelHolder.get()) break
            val f = list[i]
            pool.submit {
                // 即使已进入线程池，仍先确认未被取消
                if (cancelHolder.get()) { latch.countDown(); return@submit }
                try {
                    // 先原子占用额度再取回内容：检查与累加分两步时，
                    // 12 个并发线程可同时通过检查，内存峰值成倍超出上限
                    if (f.size > MAX_FILE_BYTES || !reserve(totalBytes, f.size)) {
                        failed.incrementAndGet()
                    } else {
                        try {
                            val data = Net.bytes(Jsd.fileUrl(slug, ref, f.path))
                            buf[i] = data
                            // 以实际字节数修正预留值（列表声明大小可能与服务端返回不一致）
                            totalBytes.addAndGet(data.size.toLong() - f.size)
                        } catch (e: Exception) {
                            totalBytes.addAndGet(-f.size)
                            throw e
                        }
                    }
                } catch (e: Exception) {
                    failed.incrementAndGet()
                } finally {
                    val d = doneCount.incrementAndGet()
                    if (d % 10 == 0 || d == n) onProgress(totalBytes.get(), totalHint)
                    latch.countDown()
                }
            }
        }
        try {
            latch.await(15, TimeUnit.MINUTES)
        } catch (_: Exception) {
            // 等待被中断：按已完成部分打包，未完成文件计入失败数
        }
        pool.shutdownNow()
        // 用户主动暂停：丢弃半成品，抛出 PausedException 供调用方标记「已暂停」
        if (cancelHolder.get()) throw Fetch.PausedException()

        var written = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zos ->
            for (i in 0 until n) {
                val data = buf[i] ?: continue
                val entry = ZipEntry(list[i].path)
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
                written++
            }
        }
        val total = totalBytes.get()
        val saved = save(ctx, tmp, name)
        tmp.delete()
        val skipNote = if (failed.get() > 0) "（${failed.get()} 个未获取）" else ""
        return PackResult("打包完成：$written 个文件 · ${fmtSize(total)}$skipNote\n${saved.first}", saved.second)
    }

    /** Android 10+ 使用 MediaStore 写入公共下载目录，无需存储权限 */
    private fun save(ctx: Context, tmp: File, name: String): Pair<String, Uri?> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "写入失败：无法创建文件" to null
                resolver.openOutputStream(uri)?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "已保存至「下载」目录：$name" to uri
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val dst = File(dir, name)
                tmp.inputStream().use { ins -> FileOutputStream(dst).use { ins.copyTo(it) } }
                val uri = runCatching {
                    FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", dst)
                }.getOrNull()
                "已保存至：${dst.absolutePath}" to uri
            }
        } catch (e: Exception) {
            "保存失败：${e.message}" to null
        }
    }
}
