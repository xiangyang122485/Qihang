package com.gh.dl.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 用户可管理的「镜像源」清单（高校镜像直连目标站 + 用户自行导入的源）。
 *
 * 与 [MirrorSources]（内置 9 家「只读」目录，仅供启动探活与默认回退）不同，
 * 本列表**可持久化、可增删改、可导入、可测速**。选中的「默认镜像」会写回
 * [com.gh.dl.ui.ParserViewModel.domestic]，从而驱动解析器/搜索页「国内镜像」的
 * 下载 base 地址（Release / 源码压缩包实际从选中的镜像站直连获取）。
 *
 * 合规形态：这些镜像均为**直连目标站**（如 mirrors.tuna.tsinghua.edu.cn/github-release/），
 * 非 ghproxy 式前缀中转；加速前缀（ghproxy）仍由 SettingsViewModel.custom / accelOn 单独管理，互不干扰。
 */
data class MirrorSource(
    /** 稳定标识，用于与 parser.domestic 对齐（内置源沿用 tuna/ustc 等旧 id） */
    val id: String,
    /** 展示名，如「清华大学」「华为云」 */
    val name: String,
    /** 镜像站 github-release base，形如 https://mirrors.tuna.tsinghua.edu.cn/github-release/ */
    val url: String,
    /** 测速结果：HEAD 往返毫秒数，未测或失败时为 -1 */
    val latencyMs: Long = -1,
    /** 测速是否可达 */
    val available: Boolean = false,
    /** 是否为当前默认源（驱动下载线路） */
    val isDefault: Boolean = false
)

object MirrorManager {

    private const val SP_KEY = "mirror_sources"
    private const val TEST_PATH = "torvalds/linux/releases"

    /** 内置默认源（取自 [MirrorSources.ALL]，确保「恢复默认」时不丢失官方 9 家） */
    private fun builtins(): List<MirrorSource> =
        MirrorSources.ALL.map { MirrorSource(it.id, it.name, it.base) }

    /**
     * [list] 的内存缓存：`磁盘原文 -> 解析结果`。
     *
     * 设置此缓存的原因：界面在**组合过程中**即调用 [list]（解析器页的镜像选择条、
     * 搜索页列表、镜像管理页），而组合逻辑为「每次相关状态变化均重新执行」——
     * 若每次调用均读取 SharedPreferences 并解析 JSON，相当于在 UI 线程执行磁盘 IO。
     * 滑动解析器页时列表项反复重组，每次重组都伴随一次磁盘读取，产生卡顿。
     *
     * 以磁盘原文作为键，任何写入操作（导入/删除/测速更新延迟）后缓存自动失效，
     * 不会出现「修改后不生效」的情况。写入方无需维护缓存，仅涉及一个 volatile 字段。
     */
    /**
     * 解析结果缓存：raw 与解析后的列表**必须作为不可变对象整体存储**。
     *
     * 若用两个 @Volatile 字段（cacheRaw / cacheVal）分别存储，写入为两步操作：
     * `cacheRaw = raw; cacheVal = out` 两步操作 —— 另一线程可能恰好在两步之间读取，
     * 观察到「raw 已更新、列表仍为旧值」，从而获取**已被删除的镜像源**。
     * 界面侧（解析页镜像下拉、加速页列表）确实在后台线程调用 list()，
     * 而用户在镜像管理页删除源时由主线程调用 saveList()，并发场景确实存在。
     * 将两个字段合并为一个 val 对象整体替换，读取时始终获取完整一致的数据，无中间态。
     */
    private class Cached(val raw: String, val list: List<MirrorSource>)

    @Volatile private var cache: Cached? = null

    /** 读取持久化的镜像列表；首次为空时返回内置 9 家 */
    fun list(ctx: Context): List<MirrorSource> {
        val raw = Settings.get(ctx, SP_KEY)
        cache?.let { if (it.raw == raw) return it.list }
        val out = parse(raw)
        cache = Cached(raw, out)
        return out
    }

    private fun parse(raw: String): List<MirrorSource> {
        if (raw.isBlank()) return builtins()
        return try {
            val a = JSONArray(raw)
            val out = ArrayList<MirrorSource>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.isBlank()) continue
                out.add(
                    MirrorSource(
                        id = o.optString("id").ifBlank { url.hashCode().toString() },
                        name = o.optString("name").ifBlank { url },
                        url = url,
                        latencyMs = o.optLong("ms", -1L),
                        available = o.optBoolean("ok"),
                        isDefault = o.optBoolean("df")
                    )
                )
            }
            if (out.isEmpty()) builtins() else out
        } catch (e: Exception) {
            builtins()
        }
    }

    /** 持久化整个镜像列表 */
    fun saveList(ctx: Context, list: List<MirrorSource>) {
        val a = JSONArray()
        list.forEach { m ->
            a.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("name", m.name)
                    put("url", m.url)
                    put("ms", m.latencyMs)
                    put("ok", m.available)
                    put("df", m.isDefault)
                }
            )
        }
        val raw = a.toString()
        Settings.put(ctx, SP_KEY, raw)
        // 缓存随写入同步更新：直接赋值新值，下次 [list] 命中缓存，无需重新解析。
        // 必须整体替换（而非分别修改两个字段），原因见 [Cached] 注释。
        cache = Cached(raw, list.toList())
    }

    /** 恢复内置 9 家，并将 tuna 设为默认 */
    fun restoreDefaults(ctx: Context) {
        val def = builtins().map { it.copy(isDefault = it.id == "tuna") }
        saveList(ctx, def)
        setDefault(ctx, "tuna")
    }

    /** 查询某 id 的 base 地址；未找到时返回 null（供 domesticBase 使用） */
    fun baseOf(ctx: Context, id: String): String? =
        list(ctx).firstOrNull { it.id == id }?.url

    /** 当前默认源 id（无默认时取第一个，列表为空时返回 null） */
    fun defaultId(ctx: Context): String? {
        val l = list(ctx)
        return l.firstOrNull { it.isDefault }?.id ?: l.firstOrNull()?.id
    }

    /** 设置某 id 为默认（清除其它默认项）并持久化。调用方需自行同步 parser.domestic */
    fun setDefault(ctx: Context, id: String) {
        val cur = list(ctx).map { it.copy(isDefault = it.id == id) }
        saveList(ctx, cur)
    }

    /** 新增一个镜像（名称可空，URL 必须以 http(s):// 开头）。成功时返回 true */
    fun add(ctx: Context, name: String, url: String): Boolean {
        val u = normalize(url) ?: return false
        val cur = list(ctx).toMutableList()
        val id = (name.takeIf { it.isNotBlank() } ?: u).hashCode().toString()
        if (cur.any { it.id == id || it.url == u }) return false
        cur.add(MirrorSource(id, name.ifBlank { u }, u))
        saveList(ctx, cur)
        return true
    }

    /** 删除一个镜像；若删除的是默认项且列表仍有剩余，则将第一项设为默认 */
    fun remove(ctx: Context, id: String) {
        val cur = list(ctx).filter { it.id != id }.toMutableList()
        if (cur.isEmpty()) {
            restoreDefaults(ctx)
            return
        }
        if (cur.none { it.isDefault }) cur[0] = cur[0].copy(isDefault = true)
        saveList(ctx, cur)
    }

    /** 修改名称/URL */
    fun update(ctx: Context, id: String, name: String, url: String) {
        val u = normalize(url) ?: return
        val cur = list(ctx).map { if (it.id == id) it.copy(name = name.ifBlank { u }, url = u) else it }
        saveList(ctx, cur)
    }

    /**
     * 从文本批量导入：支持两种格式
     *  1) TXT：每行 `名称|https://...` 或纯 `https://...`（# 开头视为注释）
     *  2) JSON：url 字符串数组，或 [{ "name":.., "url":.. }] / [{ "name":.., "base":.. }]
     * 返回成功新增的条数。
     */
    fun importFromText(ctx: Context, text: String): Int {
        val pairs = parseMirrorText(text)
        if (pairs.isEmpty()) return 0
        val cur = list(ctx).toMutableList()
        var added = 0
        for ((name, url) in pairs) {
            val u = normalize(url) ?: continue
            val id = (name.takeIf { it.isNotBlank() } ?: u).hashCode().toString()
            if (cur.any { it.id == id || it.url == u }) continue
            cur.add(MirrorSource(id, name.ifBlank { u }, u))
            added++
        }
        if (added > 0) saveList(ctx, cur)
        return added
    }

    private fun parseMirrorText(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            try {
                val a = JSONArray(trimmed)
                for (i in 0 until a.length()) {
                    when (val e = a.opt(i)) {
                        is JSONObject -> {
                            val url = e.optString("url").ifBlank { e.optString("base") }
                            if (url.isNotBlank()) out.add(e.optString("name") to url)
                        }
                        is String -> if (isUrl(e)) out.add("" to e)
                    }
                }
                return out
            } catch (_: Exception) { /* 非 JSON 格式，继续按 TXT 解析 */ }
        }
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                if (line.contains("|")) {
                    val (n, u) = line.split("|", limit = 2)
                    if (isUrl(u)) out.add(n.trim() to u.trim())
                } else if (isUrl(line)) {
                    out.add("" to line)
                }
            }
        return out
    }

    /** 补全为带结尾斜杠的规范 URL；非 http(s) 协议时返回 null */
    private fun normalize(url: String): String? {
        val u = url.trim()
        if (!isUrl(u)) return null
        return u.trimEnd('/') + "/"
    }

    private fun isUrl(s: String): Boolean =
        s.startsWith("http://", true) || s.startsWith("https://", true)

    // ---- 测速：并发 HEAD 探活，记录延迟与可用性 ----
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 并发对全部镜像执行 HEAD 探活（最长等待 10 秒），返回按「可用优先、延迟升序」排序的列表，并持久化结果。
     * 必须在后台线程/协程调用，禁止在主线程直接调用。
     */
    fun speedTest(ctx: Context): List<MirrorSource> {
        val src = list(ctx)
        if (src.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(src.size.coerceAtMost(12))
        val results = ArrayList<MirrorSource>(src.size)
        val latch = CountDownLatch(src.size)
        val lock = Any()
        for (m in src) {
            pool.submit {
                var ok = false
                var ms = -1L
                try {
                    val t0 = System.currentTimeMillis()
                    val req = Request.Builder().url(m.url + TEST_PATH).head()
                        .header("User-Agent", "gh-dl-android").build()
                    probeClient.newCall(req).execute().use { r ->
                        // 获取 HTTP 响应（含 2xx/3xx/4xx）即视为可达；仅连接级失败视为不可用
                        ok = r.code in 100..599
                        ms = System.currentTimeMillis() - t0
                    }
                } catch (_: Exception) {
                    ok = false
                    ms = -1L
                }
                synchronized(lock) { results.add(m.copy(latencyMs = ms, available = ok)) }
                latch.countDown()
            }
        }
        try { latch.await(10, TimeUnit.SECONDS) } catch (_: Exception) { /* 超时返回已完成部分 */ }
        pool.shutdownNow()
        val mapped = results.toList()
        saveList(ctx, mapped)
        return mapped.sortedWith(compareBy({ if (it.available) 0 else 1 }, { it.latencyMs }))
    }
}
