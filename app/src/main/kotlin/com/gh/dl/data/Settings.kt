package com.gh.dl.data

import android.content.Context
import org.json.JSONArray

object Settings {
    private const val FILE = "gh_dl"

    fun get(ctx: Context, key: String, def: String = ""): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, def) ?: def

    fun put(ctx: Context, key: String, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    /** 读取已保存的加速代理前缀列表（JSON 数组字符串，缺省为空） */
    fun loadAccelPrefixes(ctx: Context): List<String> {
        val raw = get(ctx, "accelPrefixes")
        if (raw.isBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 保存加速代理前缀列表（本地持久化） */
    fun saveAccelPrefixes(ctx: Context, list: List<String>) {
        val a = JSONArray()
        list.forEach { a.put(it) }
        put(ctx, "accelPrefixes", a.toString())
    }
}

object Channels {
    const val OFFICIAL = "official"
    const val CUSTOM = "custom"

    /** 探活用的最小请求（api.github.com 永远直连，失败由 [Accel] 拦截器回退前缀） */
    fun probeUrl(id: String, custom: String): String =
        "https://api.github.com/zen"

    /** API 基址（api.github.com 永远直连，失败由 [Accel] 拦截器回退前缀） */
    fun apiBase(id: String, custom: String): String =
        "https://api.github.com"

    /**
     * 下载链接改写：统一交给 [Accel.rewrite] 按 host 精确分流。
     *  - 仅 GitHub 官方 Release / 源码 / git 智能 HTTP 路径才套前缀；
     *  - 国内高校镜像、Gitee、GitLab、api.github.com 一律不套（修复「无差别套前缀」Bug）。
     * 注意：这里不再依赖 [id]/[custom] 参数，直接读全局 [com.gh.dl.ui.AppState]
     * （与 data 层其它消费者一致），避免和加速开关状态不同步。
     */
    fun file(id: String, custom: String, url: String): String =
        com.gh.dl.data.Accel.rewrite(url)
}

/** 从各种形态的输入里提取 owner/repo */
fun parseSlug(raw: String): String? {
    val s = raw.trim()
    val m = Regex("github\\.com[:/]+([A-Za-z0-9_.\\-]+)/([A-Za-z0-9_.\\-]+)").find(s)
    if (m != null) return m.groupValues[1] + "/" + m.groupValues[2].removeSuffix(".git")
    val m2 = Regex("^([A-Za-z0-9_.\\-]+)/([A-Za-z0-9_.\\-]+)$").find(s)
    if (m2 != null) return m2.groupValues[1] + "/" + m2.groupValues[2]
    return null
}
