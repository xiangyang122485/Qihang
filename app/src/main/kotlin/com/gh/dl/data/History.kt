package com.gh.dl.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析器页的链接历史（第六轮新增）。
 *
 * 为什么单独一个文件而不是塞进 [Store] / [Settings]：它存的是「一条一条记录」而不是
 * 单个配置值，读写要排序去重，混进通用 KV 里会让两边都难读。
 *
 * 存 SharedPreferences 里的一根 JSON 字符串（不是数据库）：上限只有 [MAX] 条，
 * 全量读写一次不到 10KB，引入 Room 纯属给 APK 加重量。
 *
 * 去重口径用 **slug**（owner/repo）而不是原始输入：用户常常先粘 `owner/repo` 简写、
 * 再粘完整链接，两条其实是同一个仓库；按 slug 合并才能把列表保持在有用的长度。
 * 时间只用于排序与展示，不做过期清理（用户没要求自动删，自动删反而会把人刚存的东西弄丢）。
 */
object History {

    /** 一条历史。[input] 是用户当时真正输入的原文，[slug] 是归一化后的仓库标识 */
    class Entry(val input: String, val slug: String, val title: String, val at: Long)

    private const val KEY = "parseHistory"
    private const val KEY_ON = "historyOn"

    /** 最多留多少条。再多没意义：手机上没人会翻到第 60 条 */
    const val MAX = 60

    /**
     * 开关状态直接读持久值，避免依赖 AppState（[record] 可能在初始化前被调用）。
     * 默认 "0"（第九轮）：没存过就是**不记录**。口径必须与
     * [com.gh.dl.ui.SettingsViewModel.initSettings] 与 [com.gh.dl.ui.AppState.historyOn]
     * 三处一致，否则界面显示「已关」但实际还在记 —— 这是最坏的一种不一致。
     */
    fun enabled(ctx: Context): Boolean = Settings.get(ctx, KEY_ON, "0") != "0"

    fun setEnabled(ctx: Context, on: Boolean) = Settings.put(ctx, KEY_ON, if (on) "1" else "0")

    fun load(ctx: Context): List<Entry> {
        val raw = Settings.get(ctx, KEY)
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val slug = o.optString("slug")
                if (slug.isBlank()) return@mapNotNull null
                Entry(o.optString("input"), slug, o.optString("title"), o.optLong("at"))
            }.sortedByDescending { it.at }
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        list.take(MAX).forEach { e ->
            a.put(JSONObject().put("input", e.input).put("slug", e.slug)
                .put("title", e.title).put("at", e.at))
        }
        Settings.put(ctx, KEY, a.toString())
    }

    /**
     * 记一条。关掉开关时什么都不做；已存在同一 slug 时**顶到最前**并更新时间，
     * 而不是新增一条重复项，也不是保留旧时间（用户重新解析说明它又相关了）。
     */
    fun record(ctx: Context, input: String, slug: String, title: String = "") {
        if (!enabled(ctx) || slug.isBlank()) return
        val old = load(ctx)
        val keep = old.filterNot { it.slug == slug }
        val e = Entry(input.ifBlank { "https://github.com/$slug" }, slug,
            title.ifBlank { old.firstOrNull { it.slug == slug }?.title.orEmpty() },
            System.currentTimeMillis())
        save(ctx, listOf(e) + keep)
    }

    fun remove(ctx: Context, slug: String) = save(ctx, load(ctx).filterNot { it.slug == slug })

    fun clear(ctx: Context) = Settings.put(ctx, KEY, "")
}
