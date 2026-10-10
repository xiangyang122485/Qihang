package com.gh.dl.data

import okhttp3.Interceptor
import okhttp3.Response
import java.net.URL
import android.util.Log
import com.gh.dl.ui.AppState

/**
 * 加速前缀（ghproxy 风格第三方中转）的按 host 分流规则。
 *
 * 设计：开启加速（闪电按钮 = accelOn）且用户已配置前缀（custom 非空）时，
 * 「凡是 GitHub 官方域名的链接」都自动在前面拼上镜像前缀，然后发起请求——
 * 搜索走 api.github.com、解析走 github.com/owner/repo、下载走 raw/codeload/objects
 * 一律如此，不再区分路径；
 * 关闭加速或前缀为空时，GitHub 走 [com.gh.dl.data.Net] 自带的 DoH + IP 优选直连。
 *
 * 不套前缀的白名单（NEVER_PREFIX）：国内高校镜像站、Gitee、GitLab、Codeberg、GitCode。
 * 这些要么本身就是国内可达的目标站，要么 gh-proxy 第三方中转代理不了，套上前缀只会 404。
 * 这正是早先「加速前缀无差别套用」Bug 的修复点——只按 host 精确分流，绝不靠
 * url.contains("github") 这种子串判断（会误伤 mirrors.xxx.edu.cn/github-release/）。
 *
 * 合规红线：前缀只拼在「原 URL」前面；前缀化后目标 host 变成第三方中转域名，
 * 拦截器会同时剥离 Authorization 头，绝不让 GitHub Token 被带进中转请求。
 *
 * 「浏览」Tab 明确**不走**这里的前缀（真机验证过，见 [isWebGithubHost] 的说明）。
 */
object Accel {

    /** GitHub 官方「可套前缀」的主机（*.github.com 也含） */
    private val GITHUB_HOSTS = setOf(
        "github.com", "api.github.com", "objects.githubusercontent.com",
        "raw.githubusercontent.com", "codeload.github.com"
    )

    /** 永远不套前缀的域名：国内高校镜像 + 国内/外国代码托管（加速前缀是 ghproxy 风格中转，对这些无用且会 404） */
    private val NEVER_PREFIX = setOf(
        "mirrors.tuna.tsinghua.edu.cn", "mirrors.ustc.edu.cn", "mirror.nju.edu.cn",
        "mirrors.huaweicloud.com", "mirror.bjtu.edu.cn", "mirror.sjtu.edu.cn",
        "mirrors.hust.edu.cn", "mirrors.bfsu.edu.cn", "mirror.lzu.edu.cn",
        "gitee.com", "gitlab.com", "codeberg.org", "gitcode.com"
    )

    /**
     * 「浏览」网页涉及的 GitHub 域名，比下载用的 [GITHUB_HOSTS] 宽：
     * 仓库页的 JS/CSS 在 *.githubassets.com，图片与头像在 *.githubusercontent.com。
     * 只按「等值或父域后缀」匹配，不做子串判断。
     */
    private val WEB_DOMAINS = listOf(
        "github.com", "githubusercontent.com", "githubassets.com"
    )

    /** 当前生效的加速前缀（去掉结尾斜杠） */
    fun prefix(): String = AppState.custom.trimEnd('/')

    /** 加速是否真正可用：开关开 + 前缀非空 */
    fun enabled(): Boolean = AppState.accelOn && AppState.custom.isNotBlank()

    /** host 是否属于 GitHub 官方域名（含 *.github.com） */
    fun isGithubHost(host: String): Boolean =
        host in GITHUB_HOSTS || host.endsWith(".github.com")

    /** 给定 URL，是否应当套前缀（已套过则视为不需要再套） */
    fun shouldPrefix(url: String): Boolean {
        if (!enabled()) return false
        val u = runCatching { URL(url) }.getOrNull() ?: return false
        if (u.host in NEVER_PREFIX) return false
        return isGithubHost(u.host)
    }

    /**
     * host 是否是「浏览网页」意义上的 GitHub 域名（含各资产子域）。
     *
     * 为什么网页绝不能套加速前缀（真机截图证实）：闪电用的前缀是 gh-proxy 风格的
     * **文件**中转，只认 /raw、/releases/download、/archive 这类可下载的地址；
     * 把仓库主页 URL 交给它，它判断不出是文件，就直接把**自己的首页**返回给你
     * （页面标题变成「GitHub加速下载代理」、正文是它的 logo 和广告链接）。
     * 所以浏览的加速改用 [com.gh.dl.data.Net.browse] 那条路：DoH + GitHub 真实 IP 优选直连，
     * 对本就是 HTML 的页面才是有效的。
     */
    fun isWebGithubHost(host: String): Boolean =
        host.isNotBlank() && WEB_DOMAINS.any { host == it || host.endsWith(".$it") }

    /** 把 URL 改写成「前缀 + 原 URL」；不满足规则或已套过则原样返回（幂等） */
    fun rewrite(url: String): String {
        if (!shouldPrefix(url)) return url
        val p = prefix()
        if (p.isBlank()) return url
        if (url.startsWith(p)) return url
        return "$p/$url"
    }

    /** OkHttp 拦截器：开启加速时 GitHub 官方链接直接套前缀；其余按白名单放行 */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val req = chain.request()
        val url = req.url.toString()
        val host = runCatching { URL(url).host }.getOrNull() ?: req.url.host

        // 已经发往前缀代理本身的请求（host 就是 gh-proxy 之类）→ 原样放行，避免重复套
        val pHost = runCatching { URL(prefix()).host }.getOrNull()
        if (pHost != null && host == pHost) return@Interceptor chain.proceed(req)
        // 永远不套前缀的域名 → 原样放行
        if (host in NEVER_PREFIX) return@Interceptor chain.proceed(req)

        // GitHub 官方域名：开启加速则直接套前缀；关闭则直连（依赖 Net 的 DNS 优选）
        if (isGithubHost(host)) {
            if (!enabled()) return@Interceptor chain.proceed(req)
            val rewritten = rewrite(url)
            if (rewritten != url) {
                // 前缀化后目标变成第三方中转，剥离 Authorization，避免把 GitHub Token 泄露给代理
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
