package com.gh.dl.data

import android.content.Context
import com.gh.dl.ui.AppState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Semaphore

/**
 * Git LFS 支持（纯 Kotlin，不引入任何原生库）。
 *
 * 背景：JGit 不会执行 LFS 的 smudge 过滤器，所以克隆后工作树里的大文件
 * 只是一个「指针文件」（几十字节的文本，记录真实文件的 sha256 和大小）。
 * 这里在克隆完成后扫一遍工作树，找出所有指针，向 GitHub LFS 服务批量
 * 申请真实下载地址，再用 OkHttp 把真实大文件拉下来替换指针。
 *
 * 网络规则与 Release 下载严格一致：
 *  - 仅 github.com / githubusercontent.com 才套加速代理前缀；
 *  - 公开仓库的 LFS 不需要登录，绝不把 Token 发给镜像（Net 层也已兜底）；
 *  - 并发上限 3，避免公益镜像 429 限流。
 */
object Lfs {

    private val VER_RE = Regex("^version https://git-lfs\\.github\\.com/spec/v1$")
    private val OID_RE = Regex("^oid sha256:([0-9a-f]{64})$")
    private val SIZE_RE = Regex("^size (\\d+)$")

    data class Pointer(val oid: String, val size: Long)

    /** 判断一段文本是不是 LFS 指针文件 */
    fun isPointer(text: String): Boolean {
        val lines = text.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
        if (lines.size !in 3..4) return false
        if (lines.none { VER_RE.matches(it) }) return false
        val oid = lines.firstNotNullOfOrNull { OID_RE.matchEntire(it)?.groupValues?.get(1) }
        val size = lines.firstNotNullOfOrNull { SIZE_RE.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
        return oid != null && size != null
    }

    /** 遍历工作树，收集所有 LFS 指针文件（只查小文件，避免读大文件浪费） */
    fun scan(dir: File): List<Pair<File, Pointer>> {
        val out = mutableListOf<Pair<File, Pointer>>()
        try {
            dir.walkTopDown().forEach { f ->
                if (f.isFile && f.length() in 1..2048L) {
                    try {
                        val t = f.readText(Charsets.UTF_8)
                        if (isPointer(t)) {
                            val lines = t.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
                            val oid = lines.firstNotNullOfOrNull { OID_RE.matchEntire(it)?.groupValues?.get(1) } ?: return@forEach
                            val size = lines.firstNotNullOfOrNull { SIZE_RE.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() } ?: return@forEach
                            out.add(f to Pointer(oid, size))
                        }
                    } catch (e: Exception) { /* 非文本/读失败，跳过 */ }
                }
            }
        } catch (e: Exception) { /* 目录遍历异常，忽略 */ }
        return out
    }

    data class Result(val total: Int, val okBytes: Long, val failed: Int, val msg: String)

    /**
     * 向 GitHub LFS 申请真实下载地址并拉取、替换指针。
     * @return 结果摘要
     */
    fun resolve(
        slug: String,
        dir: File,
        onProgress: (String, Int, Long) -> Unit
    ): Result {
        val pointers = scan(dir)
        if (pointers.isEmpty()) return Result(0, 0, 0, "仓库未使用 Git LFS")

        onProgress("LFS：发现 ${pointers.size} 个大文件指针，正在请求下载地址…", 0, 0)

        val apiUrl = Channels.file(
            AppState.channel, AppState.custom,
            "https://github.com/$slug.git/info/lfs/objects/batch"
        )
        val bodyJson = JSONObject().apply {
            put("operation", "download")
            put("transfers", JSONArray().put("basic"))
            put("objects", JSONArray().apply {
                pointers.forEach { (_, p) ->
                    put(JSONObject().apply { put("oid", p.oid); put("size", p.size) })
                }
            })
        }.toString()

        val client = OkHttpClient.Builder().build()
        val req = Request.Builder().url(apiUrl)
            .header("Accept", "application/vnd.git-lfs+json")
            .header("Content-Type", "application/vnd.git-lfs+json")
            .header("User-Agent", "gh-dl-android")
            // 仅官方直连时才可能带 token；走镜像（CUSTOM）一律不带，避免泄露给第三方
            .apply {
                if (AppState.channel != Channels.CUSTOM && AppState.token.isNotBlank())
                    header("Authorization", "Bearer ${AppState.token}")
            }
            .post(bodyJson.toRequestBody("application/vnd.git-lfs+json".toMediaType()))
            .build()

        val respText = try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    return Result(pointers.size, 0, pointers.size,
                        "LFS 地址请求返回 HTTP ${r.code}（公开仓库通常不需登录）")
                }
                r.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            return Result(pointers.size, 0, pointers.size, "LFS 地址请求失败：${e.message}")
        }

        val objs = try {
            JSONObject(respText).getJSONArray("objects")
        } catch (e: Exception) {
            return Result(pointers.size, 0, pointers.size, "LFS 响应解析失败")
        }

        // oid -> 真实下载地址（basic 传输）
        val links = mutableMapOf<String, String>()
        for (i in 0 until objs.length()) {
            val o = objs.getJSONObject(i)
            val oid = o.optString("oid")
            val href = o.optJSONObject("actions")?.optJSONObject("download")?.optString("href").orEmpty()
            if (oid.isNotBlank() && href.isNotBlank()) links[oid] = href
        }

        // 这些计数由最多 3 个工作线程并发累加：IntVar 的 ++ 是「读-改-写」，
        // 会丢数（成功/失败数对不上、字节数偏小），必须用原子类。
        val okCount = java.util.concurrent.atomic.AtomicInteger(0)
        val failCount = java.util.concurrent.atomic.AtomicInteger(0)
        val okBytes = java.util.concurrent.atomic.AtomicLong(0L)
        val sem = Semaphore(3) // 并发上限，防镜像 429
        val jobs = pointers.map { (file, p) ->
            Thread {
                sem.acquire()
                try {
                    val href = links[p.oid]
                    if (href.isNullOrBlank()) {
                        failCount.incrementAndGet(); return@Thread
                    }
                    val dl = Channels.file(AppState.channel, AppState.custom, href)
                    val r2 = Request.Builder().url(dl).header("User-Agent", "gh-dl-android").build()
                    client.newCall(r2).execute().use { call ->
                        if (!call.isSuccessful) { failCount.incrementAndGet(); return@Thread }
                        val data = call.body?.bytes() ?: ByteArray(0)
                        if (data.size.toLong() != p.size) { failCount.incrementAndGet(); return@Thread } // 大小不符，保险起见不覆盖
                        // 先写 .part 再改名：中途断电/异常不会留下半截文件冒充真身
                        val part = File(file.absolutePath + ".part")
                        FileOutputStream(part).use { it.write(data) }
                        if (!part.renameTo(file)) {
                            part.delete()
                            failCount.incrementAndGet(); return@Thread
                        }
                        val ok = okCount.incrementAndGet()
                        val bytes = okBytes.addAndGet(data.size.toLong())
                        onProgress("LFS：已拉取 $ok/${pointers.size} · ${fmtSize(bytes)}", ok, bytes)
                    }
                } catch (e: Exception) {
                    failCount.incrementAndGet()
                } finally {
                    sem.release()
                }
            }
        }
        jobs.forEach { it.start() }
        jobs.forEach { try { it.join() } catch (e: InterruptedException) { /* ignore */ } }

        val nOk = okCount.get(); val nFail = failCount.get(); val nBytes = okBytes.get()
        val msg = if (nFail == 0)
            "Git LFS：已拉取全部 $nOk 个大文件（${fmtSize(nBytes)}），指针已替换为真实文件"
        else
            "Git LFS：成功 $nOk / 失败 $nFail（失败项仍是 LFS 指针，可在电脑上用 git lfs pull 补拉）"
        return Result(pointers.size, nBytes, nFail, msg)
    }
}
