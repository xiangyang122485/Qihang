package com.gh.dl.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析器页的链接历史。
 *
 * 单独文件而非并入 [Store] / [Settings] 的原因：其存储的是「逐条记录」而非
 * 单个配置值，读写需排序去重，混入通用 KV 会导致双方均难以维护。
 *
 * 存储于 SharedPreferences 的单条 JSON 字符串（非数据库）：上限仅 [MAX] 条，
 * 全量读写一次不足 10KB，引入 Room 仅会增加 APK 体积。
 *
 * 去重依据使用 **slug**（owner/repo）而非原始输入：用户常先粘贴 `owner/repo` 简写、
 * 再粘贴完整链接，两者实为同一仓库；按 slug 合并方可将列表保持在有效长度。
 * 时间仅用于排序与展示，不做过期清理（用户未要求自动删除，自动删除反而可能丢失用户刚保存的记录）。
 */
object History {

    /** 一条历史记录。[input] 为用户当时实际输入的原文，[slug] 为归一化后的仓库标识 */
    class Entry(val input: String, val slug: String, val title: String, val at: Long)

    private const val KEY = "parseHistory"
    private const val KEY_ON = "historyOn"

    /** 最大保留条数。超过此数量无实际意义：手机上用户极少翻阅至第 60 条 */
    const val MAX = 60

    /**
     * 开关状态直接读取持久值，避免依赖 AppState（[record] 可能在初始化前被调用）。
     * 默认 "0"（自 v1.2.1 起）：未存储过时即为**不记录**。该默认值必须与
     * [com.gh.dl.ui.SettingsViewModel.initSettings] 与 [com.gh.dl.ui.AppState.historyOn]
     * 三处一致，否则界面显示「已关闭」但实际仍在记录，此为最严重的不一致。
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
     * 记录一条。关闭开关时不执行任何操作；同一 slug 已存在时**移至最前**并更新时间，
     * 而非新增重复项，亦非保留旧时间（用户重新解析表明其再次相关）。
     */
    /**
     * load → filter → save 为三步读改写，与 [remove]、[clear] 并发时会静默丢失条目，
     * 故所有历史写操作共用一把锁（解析成功、删除、清空可能来自不同线程）。
     */
    private val lock = Any()

    fun record(ctx: Context, input: String, slug: String, title: String = "") {
        if (!enabled(ctx) || slug.isBlank()) return
        synchronized(lock) {
            val old = load(ctx)
            val keep = old.filterNot { it.slug == slug }
            val e = Entry(input.ifBlank { "https://github.com/$slug" }, slug,
                title.ifBlank { old.firstOrNull { it.slug == slug }?.title.orEmpty() },
                System.currentTimeMillis())
            save(ctx, listOf(e) + keep)
        }
    }

    fun remove(ctx: Context, slug: String) = synchronized(lock) {
        save(ctx, load(ctx).filterNot { it.slug == slug })
    }

    fun clear(ctx: Context) = synchronized(lock) { Settings.put(ctx, KEY, "") }
}
