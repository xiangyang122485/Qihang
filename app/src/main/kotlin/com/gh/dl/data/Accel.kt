package com.gh.dl.data

import okhttp3.Interceptor
import okhttp3.Response
import java.net.URL
import android.util.Log
import com.gh.dl.ui.AppState

/**
 * 加速前缀（ghproxy 风格第三方中转）的按 host 分流规则。
 *
 * 当加速开关开启（accelOn）且用户已配置前缀（custom 非空）时，
 * 所有 GitHub 官方域名链接均在原 URL 前拼接镜像前缀后发起请求。
 * 搜索请求走 api.github.com，解析请求走 github.com/owner/repo，
 * 下载请求走 raw/codeload/objects，均按此规则处理，不再区分路径。
 * 当加速关闭或前缀为空时，GitHub 请求走 [com.gh.dl.data.Net] 的 DoH + IP 优选直连。
 *
 * 白名单（NEVER_PREFIX）包含：国内高校镜像站、Gitee、GitLab、Codeberg、GitCode。
 * 这些域名本身为国内可达的目标站，或 gh-proxy 第三方中转无法代理，添加前缀会导致 404。
 * 此设计修复了「加速前缀无差别套用」缺陷，仅按 host 精确分流，不使用
 * url.contains("github") 等子串判断（会误匹配 mirrors.xxx.edu.cn/github-release/）。
 *
 * 前缀仅拼接在「原 URL」前面；前缀化后目标 host 变为第三方中转域名，
 * 拦截器同时剥离 Authorization 头，防止 GitHub Token 进入中转请求。
 *
 * 「浏览」Tab **不走**此处的前缀逻辑（真机验证，参见 [isWebGithubHost] 说明）。
 */
object Accel {

    /**
     * 内置加速前缀：首次开启加速、或长按闪电按钮时直接可选，无需先去设置页添加。
     * 仅收录实测可同时代理 GitHub API 与文件下载的公共中转站；
     * 第三方中转可能变更或失效，用户仍可在设置中自行增删。
     */
    val BUILT_IN_PREFIXES = listOf(
        "https://gh-proxy.com",
        "https://gh-proxy.org"
    )

    /** GitHub 官方可添加前缀的主机（含 *.github.com） */
    private val GITHUB_HOSTS = setOf(
        "github.com", "api.github.com", "objects.githubusercontent.com",
        "raw.githubusercontent.com", "codeload.github.com"
    )

    /** 永不添加前缀的域名：国内高校镜像 + 国内外代码托管平台（加速前缀为 ghproxy 风格中转，对这些域名无效且会导致 404） */
    private val NEVER_PREFIX = setOf(
        "mirrors.tuna.tsinghua.edu.cn", "mirrors.ustc.edu.cn", "mirror.nju.edu.cn",
        "mirrors.huaweicloud.com", "mirror.bjtu.edu.cn", "mirror.sjtu.edu.cn",
        "mirrors.hust.edu.cn", "mirrors.bfsu.edu.cn", "mirror.lzu.edu.cn",
        "gitee.com", "gitlab.com", "codeberg.org", "gitcode.com"
    )

    /**
     * 「浏览」网页涉及的 GitHub 域名，范围宽于下载使用的 [GITHUB_HOSTS]：
     * 仓库页的 JS/CSS 位于 *.githubassets.com，图片与头像位于 *.githubusercontent.com。
     * 仅按「等值或父域后缀」匹配，不做子串判断。
     */
    private val WEB_DOMAINS = listOf(
        "github.com", "githubusercontent.com", "githubassets.com"
    )

    /**
     * 规范化用户输入的前缀。
     *
     * 实际使用中前缀来自手工输入、剪贴板或导入文件，常见问题包括：
     * 前后空格 / 换行、被引号或尖括号包裹、漏写 https://、结尾多余斜杠。
     * 其中「漏写协议」和「前后空白」会让拼接结果变成非法 URL，OkHttp
     * 直接抛异常，表现为「加速开关开着但所有请求都失败」。
     * 因此所有读取前缀的路径统一经过本方法；无法修复的输入返回空串，
     * 由 [enabled] 判定为未启用，而不是带着坏 URL 发请求。
     */
    fun normalizePrefix(raw: String): String {
        var s = raw.trim().trim('"', '\'', '<', '>', '“', '”', '‘', '’').trim()
        if (s.isEmpty()) return ""
        // 显式写了其它协议（ftp:// 等）直接判无效；只填域名时补全 https://
        val schemeEnd = s.indexOf("://")
        if (schemeEnd >= 0) {
            val scheme = s.substring(0, schemeEnd).lowercase()
            if (scheme != "http" && scheme != "https") return ""
        } else {
            s = "https://$s"
        }
        s = s.trimEnd('/')
        // 中间仍含空白/换行的输入无法安全拼接，视为无效
        if (s.any { it.isWhitespace() }) return ""
        val u = runCatching { URL(s) }.getOrNull() ?: return ""
        if (u.protocol != "http" && u.protocol != "https") return ""
        if (u.host.isNullOrBlank()) return ""
        return s
    }

    /** 当前生效的加速前缀（规范化后；无效时为空串） */
    fun prefix(): String = normalizePrefix(AppState.custom)

    /** 加速功能是否可用：开关已开启且前缀有效 */
    fun enabled(): Boolean = AppState.accelOn && prefix().isNotBlank()

    /** host 是否属于 GitHub 官方域名（含 *.github.com） */
    fun isGithubHost(host: String): Boolean =
        host in GITHUB_HOSTS || host.endsWith(".github.com")

    /** 判断给定 URL 是否应当添加前缀（已添加则视为无需再次添加） */
    fun shouldPrefix(url: String): Boolean {
        if (!enabled()) return false
        val u = runCatching { URL(url) }.getOrNull() ?: return false
        if (u.host in NEVER_PREFIX) return false
        return isGithubHost(u.host)
    }

    /**
     * 判断 host 是否属于「浏览网页」意义上的 GitHub 域名（含各资产子域）。
     *
     * 网页不可添加加速前缀的原因（真机截图证实）：加速前缀采用 gh-proxy 风格的
     * **文件**中转，仅识别 /raw、/releases/download、/archive 等可下载地址；
     * 将仓库主页 URL 提交给该中转时，其无法识别为文件，直接返回**中转站自身首页**
     * （页面标题为「GitHub加速下载代理」、正文为中转站 logo 与广告链接）。
     * 因此浏览功能改用 [com.gh.dl.data.Net.browse] 路径：DoH + GitHub 真实 IP 优选直连，
     * 该方式对 HTML 页面有效。
     */
    fun isWebGithubHost(host: String): Boolean =
        host.isNotBlank() && WEB_DOMAINS.any { host == it || host.endsWith(".$it") }

    /**
     * 纯函数：给定前缀 p 与 url，按 host 比较判断是否已添加前缀（幂等），
     * 未添加则返回 "$p/$url"，已添加或 host 匹配则原样返回。
     * 从 [rewrite] 中提取，以便 JVM 单元测试覆盖。
     */
    internal fun rewriteWith(url: String, p: String): String {
        if (p.isBlank()) return url
        // 幂等判定按前缀主机名比较：startsWith 会把「前缀恰为另一域名前缀子串」的链接
        // 误判为已添加前缀，使该链接始终不经中转，表现为加速对部分文件不生效
        val ph = runCatching { URL(p).host.lowercase() }.getOrNull()
        val uh = runCatching { URL(url).host.lowercase() }.getOrNull()
        if (ph != null && ph == uh) return url
        return "$p/$url"
    }

    /** 将 URL 改写为「前缀 + 原 URL」；不满足规则或已添加前缀则原样返回（幂等操作） */
    fun rewrite(url: String): String {
        if (!shouldPrefix(url)) return url
        val p = prefix()
        return rewriteWith(url, p)
    }

    /** OkHttp 拦截器：加速开启时为 GitHub 官方链接添加前缀；其余请求按白名单放行 */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val req = chain.request()
        val url = req.url.toString()
        val host = runCatching { URL(url).host }.getOrNull() ?: req.url.host

        // 已发往前缀代理的请求（host 为 gh-proxy 等中转域名）→ 原样放行，避免重复添加前缀
        val pHost = runCatching { URL(prefix()).host }.getOrNull()
        if (pHost != null && host == pHost) return@Interceptor chain.proceed(req)
        // 永不添加前缀的域名 → 原样放行
        if (host in NEVER_PREFIX) return@Interceptor chain.proceed(req)

        // GitHub 官方域名：加速开启则添加前缀；关闭则直连（依赖 Net 的 DNS 优选）
        if (isGithubHost(host)) {
            if (!enabled()) return@Interceptor chain.proceed(req)
            val rewritten = rewrite(url)
            if (rewritten != url) {
                // 添加前缀后目标变为第三方中转，剥离 Authorization 头，防止 GitHub Token 泄露给代理
                Log.d("Accel", "prefix applied -> $rewritten")
                val newReq = req.newBuilder()
                    .url(rewritten)
                    .removeHeader("Authorization")
                    .build()
                return@Interceptor chain.proceed(newReq)
            }
        }
        chain.proceed(req)
    }
}
