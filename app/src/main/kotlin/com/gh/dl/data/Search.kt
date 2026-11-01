package com.gh.dl.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 项目搜索数据层。
 *
 * 三种源：
 *  - github   ：GitHub 官方搜索 API（可经加速代理前缀触达）。此为「国内源」模式复用的索引。
 *  - foreign  ：合法境外代码托管（GitLab.com / Codeberg），各自提供公开搜索 API，无鉴权。
 *  - domestic ：复用 GitHub 索引（国内高校镜像站无「按关键词搜索仓库」的 API），
 *              每条结果标记 domestic=true，点击下载时由解析器自动走镜像站获取 Release。
 *
 * 合规说明：仅搜索公开仓库信息（名称/描述/star），不向第三方传递任何 Token；
 * 加速代理前缀仅套用于 GitHub 官方域名。
 */
object Search {

    data class Hit(
        /** github / gitlab / codeberg / gitee / gitcode */
        val source: String,
        val fullName: String,   // owner/repo
        val htmlUrl: String,
        val desc: String,
        val stars: Int,
        val language: String,
        val license: String,
        val updatedAt: String,
        /** 默认分支（GitHub/GitLab/Gitee/GitCode/Codeberg 的搜索 API 均返回），用于构造源码压缩包直链 */
        val defaultBranch: String = "",
        /** 国内源模式下为 true（表示可走国内镜像下载 Release） */
        val domestic: Boolean = false,
        /** 选中的优先镜像站中文短名（如「南大」）；非空时结果卡上标注「可走 XX 镜像下载」。
         *  仅用于 GitHub 官方源结果（镜像站仅代理 GitHub Release，不代理搜索）。 */
        val mirrorLabel: String? = null
    )

    /** GitHub 仓库搜索。
     *  请求 https://api.github.com；开启加速（闪电按钮）时 [com.gh.dl.data.Accel] 拦截器
     *  会直接将请求套用镜像前缀后发送（所有 GitHub 官方域名均走前缀，与 accelOn 开关联动），
     *  关闭加速则走 [com.gh.dl.data.Net] 自带的 DoH + IP 优选直连。
     *  此处不手动拼接前缀，统一交由拦截器处理，避免重复改写。
     *
     *  兼容性修复：GitHub 的 /search/repositories 返回的是对象 { total_count, items:[...] }，
     *  而非裸数组；此前直接 JSONArray(...) 会将整个对象作为数组解析而抛出 JSONException（UI 表现为
     *  「搜索失败：Value」）。此处统一通过 [parseGitHubJson] 获取 items 数组，并对每个字段做空安全取值。 */
    fun github(q: String, page: Int = 1): Pair<List<Hit>, Boolean> {
        val url = "https://api.github.com/search/repositories?q=${enc(q)}&per_page=30&sort=stars&page=$page"
        val body = apiGet(url)
        val hits = parseGitHubJson(body)
        return hits to (hits.size >= 30)
    }

    /**
     * GitHub API 取数：与解析器 apiGet 同一套「前缀池先轮 + 直连最后兜底」逻辑。
     *
     * 为什么搜索不能像过去那样直接交给 Net 的加速拦截器：拦截器会盲目把
     * api.github.com 也套上「文件中转」前缀，而公共 ghproxy 类中转普遍不代理 API
     * （实测返回 403 / 超时），于是「开着下载加速就搜不出结果」。这里显式实现：
     *  1) 未开加速 → 直连（Net 自带 DoH + 真实 IP 优选）；
     *  2) 开了加速 → 依次试 API 候选前缀（跳过冷却中的），任一成功即用并记健康度；
     *  3) 全部前缀失败 → 短超时赌一次直连。
     * 前缀请求用 direct=true 客户端（host 为中转域），authTokenFor 会自动剥离 Token，
     * 绝不经中转泄露；仅最后的直连才携带 Token。
     */
    private fun apiGet(url: String): String {
        if (!Accel.enabled()) return Net.get(url, ghToken(), direct = true, timeoutMs = 12_000)
        val pool = Accel.pickApi()
        for (p in pool) {
            if (Accel.apiCooling(p)) continue
            try {
                val body = Net.get(Accel.rewriteWith(url, p), ghToken(), direct = true, timeoutMs = 12_000)
                if (isApiJson(body)) { Accel.markApi(p, true); return body }
                Accel.markApi(p, false)
            } catch (e: Exception) {
                Accel.markApi(p, false)
            }
        }
        // 前缀均不可用 → 直连兜底；仍失败则抛出，由上层给出可重试提示。
        return try {
            Net.get(url, ghToken(), direct = true, timeoutMs = 6_000)
        } catch (e: Exception) {
            Net.evictOnFailure("api.github.com")
            throw e
        }
    }

    /** 粗判响应是否像 GitHub API JSON（对象或数组开头），排除中转返回的 HTML 首页。 */
    private fun isApiJson(body: String): Boolean {
        val t = body.trimStart()
        return t.startsWith("{") || t.startsWith("[")
    }

    /**
     * 将 GitHub 搜索 API 的响应体解析为 [Hit] 列表（纯函数，不发网络，便于单测）。
     *  - 顶层为对象，仓库列表位于 `items` 数组中；
     *  - 所有字段使用 opt* 取值，缺失或为 null 时回退至空串或 0，不对 null 强行 getString 以避免崩溃。
     */
    fun parseGitHubJson(body: String): List<Hit> {
        val obj = runCatching { JSONObject(body) }.getOrElse { return emptyList() }
        val arr = obj.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<Hit>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Hit(
                    source = "github",
                    fullName = o.optString("full_name"),
                    htmlUrl = o.optString("html_url"),
                    desc = o.optString("description"),
                    stars = o.optInt("stargazers_count"),
                    language = o.optString("language"),
                    license = o.optJSONObject("license")?.optString("spdx_id") ?: "",
                    updatedAt = o.optString("pushed_at").take(10),
                    defaultBranch = o.optString("default_branch")
                )
            )
        }
        return out
    }

    /** GitLab.com 公开仓库搜索（合法境外源，自带公开 API） */
    fun gitlab(q: String, page: Int = 1): Pair<List<Hit>, Boolean> {
        val url = "https://gitlab.com/api/v4/projects?search=${enc(q)}" +
            "&order_by=stars&sort=desc&per_page=30&visibility=public&membership=false&page=$page"
        val arr = JSONArray(Net.text(url))
        val out = ArrayList<Hit>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Hit(
                    source = "gitlab",
                    fullName = o.optString("path_with_namespace"),
                    htmlUrl = o.optString("web_url"),
                    desc = o.optString("description"),
                    stars = o.optInt("star_count"),
                    language = "",
                    license = o.optString("license").let { if (it.isBlank()) "" else it },
                    updatedAt = o.optString("last_activity_at").take(10),
                    defaultBranch = o.optString("default_branch")
                )
            )
        }
        return out to (out.size >= 30)
    }

    /** Codeberg（Gitea，合法境外源）仓库搜索 */
    fun codeberg(q: String, page: Int = 1): Pair<List<Hit>, Boolean> {
        val url = "https://codeberg.org/api/v1/repos/search?q=${enc(q)}&limit=30&sort=stars&order=desc&page=$page"
        val obj = JSONObject(Net.text(url))
        val arr = obj.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<Hit>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Hit(
                    source = "codeberg",
                    fullName = o.optString("full_name"),
                    htmlUrl = o.optString("html_url"),
                    desc = o.optString("description"),
                    stars = o.optInt("stars_count"),
                    language = o.optString("language"),
                    license = o.optString("license").let { if (it.isBlank()) "" else it },
                    updatedAt = o.optString("updated_at").take(10),
                    defaultBranch = o.optString("default_branch")
                )
            )
        }
        return out to (out.size >= 30)
    }

    /** Gitee（码云，国内代码托管，公开搜索 API） */
    fun gitee(q: String, page: Int = 1): Pair<List<Hit>, Boolean> {
        val url = "https://gitee.com/api/v5/search/repositories?q=${enc(q)}&page=$page&per_page=20"
        val arr = JSONArray(Net.text(url))
        val out = ArrayList<Hit>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Hit(
                    source = "gitee",
                    fullName = o.optString("full_name"),
                    htmlUrl = o.optString("html_url"),
                    desc = o.optString("description"),
                    stars = o.optInt("stargazers_count"),
                    language = o.optString("language"),
                    license = o.optJSONObject("license")?.optString("name") ?: "",
                    updatedAt = o.optString("updated_at").take(10),
                    defaultBranch = o.optString("default_branch"),
                    domestic = true
                )
            )
        }
        return out to (out.size >= 20)
    }

    /**
     * GitCode（gitcode.com，CSDN 旗下，底层为 GitLab）。
     * 该 API 需要访问令牌；[token] 由设置页填入、仅发往 gitcode.com 本身。
     */
    fun gitcode(q: String, token: String = com.gh.dl.ui.AppState.gitcodeToken, page: Int = 1): Pair<List<Hit>, Boolean> {
        val url = "https://gitcode.com/api/v4/projects?search=${enc(q)}" +
            "&order_by=stars&sort=desc&per_page=30&visibility=public&membership=false&page=$page"
        val auth = if (token.isBlank()) null else "Bearer $token"
        val arr = JSONArray(Net.text(url, auth))
        val out = ArrayList<Hit>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Hit(
                    source = "gitcode",
                    fullName = o.optString("path_with_namespace"),
                    htmlUrl = o.optString("web_url"),
                    desc = o.optString("description"),
                    stars = o.optInt("star_count"),
                    language = "",
                    license = o.optString("license").let { if (it.isBlank()) "" else it },
                    updatedAt = o.optString("last_activity_at").take(10),
                    defaultBranch = o.optString("default_branch"),
                    domestic = true
                )
            )
        }
        return out to (out.size >= 30)
    }

    /** 优先镜像站 id 至中文短名的映射（与解析器下拉、诊断页保持一致）。用于结果卡标注「可走 XX 镜像下载」。 */
    private val MIRROR_SHORT = mapOf(
        "tuna" to "清华", "ustc" to "中科大", "nju" to "南大", "huawei" to "华为云",
        "bit" to "北京理工", "sjtu" to "上海交大", "hust" to "华中科技",
        "bfsu" to "北外", "lzu" to "兰大"
    )

    /** 给定镜像站 id 返回中文短名；未知 id 返回 null（不打标） */
    fun mirrorLabel(id: String): String? = MIRROR_SHORT[id]

    private fun enc(s: String): String =
        try { URLEncoder.encode(s, "UTF-8") } catch (e: Exception) { s }

    /** 获取 GitHub Token（仅发往 GitHub 官方域名，符合安全红线） */
    private fun ghToken(): String = com.gh.dl.ui.AppState.token
}
