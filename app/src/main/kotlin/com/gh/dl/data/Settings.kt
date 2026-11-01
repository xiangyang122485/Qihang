package com.gh.dl.data

import android.content.Context
import org.json.JSONArray

object Settings {
    private const val FILE = "gh_dl"

    /**
     * 外观模式的 SharedPreferences key 与默认值。
     *
     * 收成常量而非各处写字符串的原因：此 key 原先在 3 处各写一遍
     * （启动预读、initSettings 灌入、外观页写入），默认值也各自定义。
     * 一旦某处拼写错误或默认值不一致，表现即为「更改外观后重启又恢复原状」这类难以排查的问题。
     *
     * 默认值自 v1.2.1 起从 "system" 改为 **"light"**：用户明确要求「此 App 默认为浅色」。
     * 更关键的是同时修复启动闪黑问题。原默认跟随系统，系统深色时 App 第一帧按深色渲染，
     * 而浅色为用户期望，来回切换即产生一次闪烁。当前未存储过设置的用户从头到尾均为浅色。
     */
    const val KEY_THEME_MODE = "theme_mode"
    const val THEME_DEFAULT = "light"

    /** 读取当前外观选择（system / light / dark），未设置时为 [THEME_DEFAULT] */
    fun themeMode(ctx: Context): String = get(ctx, KEY_THEME_MODE, THEME_DEFAULT)

    /** 静默翻译：浏览页遇到非中文正文时自动翻译并原位替换；默认关闭 */
    const val KEY_AUTO_TRANSLATE = "autoTranslate"

    fun autoTranslate(ctx: Context): Boolean = get(ctx, KEY_AUTO_TRANSLATE, "0") != "0"

    fun setAutoTranslate(ctx: Context, on: Boolean) {
        put(ctx, KEY_AUTO_TRANSLATE, if (on) "1" else "0")
    }

    fun get(ctx: Context, key: String, def: String = ""): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, def) ?: def

    fun put(ctx: Context, key: String, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    /** 读取已保存的加速代理前缀列表（JSON 数组字符串，默认为空） */
    fun loadAccelPrefixes(ctx: Context): List<String> = parseList(get(ctx, "accelPrefixes"))

    /** 保存加速代理前缀列表（本地持久化） */
    fun saveAccelPrefixes(ctx: Context, list: List<String>) {
        put(ctx, "accelPrefixes", jsonList(list))
    }

    private fun jsonList(list: List<String>): String {
        val a = JSONArray()
        list.forEach { a.put(it) }
        return a.toString()
    }

    private fun parseList(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 浏览加速的网页镜像站列表（空表示使用内置默认） */
    fun loadBrowseMirrors(ctx: Context): List<String> = parseList(get(ctx, "browseMirrors"))

    fun saveBrowseMirrors(ctx: Context, list: List<String>) {
        put(ctx, "browseMirrors", jsonList(list))
    }

    /** 搜索历史（本地持久化，最多 10 条，最近优先） */
    fun loadSearchHistory(ctx: Context): List<String> = parseList(get(ctx, "searchHistory"))

    fun saveSearchHistory(ctx: Context, list: List<String>) {
        put(ctx, "searchHistory", jsonList(list.take(10)))
    }
}

object Channels {
    const val OFFICIAL = "official"
    const val CUSTOM = "custom"

    /**
     * 探活用的最小请求。
     * 实际请求由 [com.gh.dl.data.Net] 发出：加速开启时 [Accel] 拦截器会为其添加前缀，
     * 失败后由调用方清空优选并直连重试（见 ParserViewModel.apiGet）。
     */
    fun probeUrl(id: String, custom: String): String =
        "https://api.github.com/zen"

    /** API 基址；线路回退由调用方负责，本方法只返回官方地址 */
    fun apiBase(id: String, custom: String): String =
        "https://api.github.com"

    /**
     * 下载链接改写：统一交由 [Accel.rewrite] 按 host 精确分流。
     *  - 仅 GitHub 官方 Release / 源码 / git 智能 HTTP 路径套用前缀；
     *  - 国内高校镜像、Gitee、GitLab、api.github.com 一律不套用（修复「无差别套用前缀」缺陷）。
     * 说明：此处不再依赖 [id]/[custom] 参数，直接读取全局 [com.gh.dl.ui.AppState]
     * （与 data 层其他消费者一致），避免与加速开关状态不同步。
     */
    fun file(id: String, custom: String, url: String): String =
        com.gh.dl.data.Accel.rewrite(url)
}

private val SLUG_URL = Regex("github\\.com[:/]+([A-Za-z0-9_.\\-]+)/([A-Za-z0-9_.\\-]+)")
private val SLUG_PLAIN = Regex("^([A-Za-z0-9_.\\-]+)/([A-Za-z0-9_.\\-]+)$")

/** 从各种形态的输入中提取 owner/repo */
fun parseSlug(raw: String): String? {
    val s = raw.trim()
    val m = SLUG_URL.find(s)
    if (m != null) return m.groupValues[1] + "/" + m.groupValues[2].removeSuffix(".git")
    val m2 = SLUG_PLAIN.find(s)
    if (m2 != null) return m2.groupValues[1] + "/" + m2.groupValues[2]
    return null
}
