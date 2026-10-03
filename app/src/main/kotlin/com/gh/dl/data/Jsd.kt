package com.gh.dl.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
 * 这是一条合法公共 CDN，缓存的是开源仓库的公开文件；在 GitHub 被阻断的网络下仍可用，
 * 因此作为源码获取的兜底通道：列版本 → 列文件树 → 逐文件下载 → 本地打包 zip。
 */
object Jsd {

    private const val DATA = "https://data.jsdelivr.com/v1/packages/gh/"
    private const val CDN = "https://cdn.jsdelivr.net/gh/"

    data class Entry(val path: String, val size: Long)

    /** 仓库的所有版本（tag） */
    fun versions(slug: String): List<String> {
        val txt = Net.get(DATA + slug)
        val arr = JSONObject(txt).optJSONArray("versions") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i)?.optString("version").orEmpty()
            if (v.isNotBlank()) out.add(v)
        }
        return out
    }

    /** 指定版本的文件树 */
    fun tree(slug: String, ref: String): List<Entry> {
        val txt = Net.get(DATA + slug + "@" + ref + "?structure=flat")
        val arr = JSONObject(txt).optJSONArray("files") ?: return emptyList()
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = o.optString("name").removePrefix("/")
            out.add(Entry(p, o.optLong("size")))
        }
        return out
    }

    fun fileUrl(slug: String, ref: String, path: String): String = "$CDN$slug@$ref/$path"
}

/** 把整仓文件下载下来打包成一个 zip，并保存到系统「下载」目录 */
object ZipPack {

    private const val MAX_FILES = 4000
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    /** 并发下载会先放在内存里，所以总量设个上限，避免 OOM */
    private const val MAX_TOTAL_BYTES = 150L * 1024 * 1024

    /**
     * 整仓打包。
     * @param cancelHolder 用户暂停时由外部置 true，本函数检测到后中止打包并抛
     *        [Fetch.PausedException]，由调用方把任务标记为「已暂停」。
     * @param onProgress 进度回调（已下载字节, 总字节），用于下载页进度条。
     */
    fun pack(
        ctx: Context,
        slug: String,
        ref: String,
        files: List<Jsd.Entry>,
        onProgress: (Long, Long) -> Unit,
        cancelHolder: AtomicReference<Boolean>
    ): String {
        val list = files.take(MAX_FILES)
        if (list.isEmpty()) return "没有可下载的文件"
        val name = slug.replace("/", "-") + "-" + ref + ".zip"
        val tmp = File(ctx.cacheDir, name)
        val n = list.size
        val totalHint = list.sumOf { it.size }

        // 并发拉取（串行下 800 多个文件要好几分钟）
        val threads = 12
        val pool = Executors.newFixedThreadPool(threads)
        val buf = arrayOfNulls<ByteArray>(n)
        val latch = CountDownLatch(n)
        val doneCount = AtomicInteger(0)
        val totalBytes = AtomicLong(0)
        val failed = AtomicInteger(0)

        for (i in 0 until n) {
            // 用户暂停：不再提交新任务，剩下的直接放行计数
            if (cancelHolder.get()) break
            val f = list[i]
            pool.submit {
                // 即使已进入线程池，也先确认未被取消
                if (cancelHolder.get()) { latch.countDown(); return@submit }
                try {
                    if (f.size <= MAX_FILE_BYTES && totalBytes.get() + f.size <= MAX_TOTAL_BYTES) {
                        val data = Net.bytes(Jsd.fileUrl(slug, ref, f.path))
                        buf[i] = data
                        totalBytes.addAndGet(data.size.toLong())
                    } else {
                        failed.incrementAndGet()
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
        } catch (e: Exception) { }
        pool.shutdownNow()
        // 用户主动暂停：丢弃半成品，交回 PausedException 让调用方标记「已暂停」
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
        val where = save(ctx, tmp, name)
        tmp.delete()
        val skipNote = if (failed.get() > 0) "（${failed.get()} 个未取到）" else ""
        return "打包完成：$written 个文件 · ${fmtSize(total)}$skipNote\n$where"
    }

    /** Android 10+ 用 MediaStore 写入公共下载目录，无需存储权限 */
    private fun save(ctx: Context, tmp: File, name: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "写入失败：无法创建文件"
                resolver.openOutputStream(uri)?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "已保存到「下载」目录：$name"
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val dst = File(dir, name)
                tmp.inputStream().use { ins -> FileOutputStream(dst).use { ins.copyTo(it) } }
                "已保存到：${dst.absolutePath}"
            }
        } catch (e: Exception) {
            "保存失败：${e.message}"
        }
    }
}
