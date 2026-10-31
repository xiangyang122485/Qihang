package com.gh.dl.data

import com.gh.dl.ui.AppState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [Accel] 的域名分流回归测试。
 *
 * 核心约束：**网页域名不得套用加速前缀**。
 * 加速前缀采用 gh-proxy 风格的文件中转，仅识别 /raw、/releases/download、/archive 路径；
 * 将仓库主页 URL 交给该前缀时，其无法识别为文件请求，返回自身的首页
 * （实测结果为「GitHub加速下载代理」页面及其 logo 与广告链接）。
 * 因此 [Accel.isWebGithubHost] 仅用于判断是否使用真实 IP 代取，
 * 而 [Accel.shouldPrefix] 仅作用于下载/API 路径——两者不可混用。
 */
class AccelHostRoutingTest {

    private val prefix = "https://gh-proxy.example.com"

    @Before
    fun setUp() {
        AppState.custom = prefix
        AppState.accelOn = true
    }

    @After
    fun tearDown() {
        AppState.custom = ""
        AppState.accelOn = false
    }

    /** 网页域名判定：GitHub 官方与各资产子域均纳入，供浏览页决定是否代取 */
    @Test
    fun webHostsCoverGithubAssetDomains() {
        for (h in listOf(
            "github.com", "www.github.com", "api.github.com", "codeload.github.com",
            "raw.githubusercontent.com", "objects.githubusercontent.com",
            "avatars.githubusercontent.com", "camo.githubusercontent.com",
            "github.githubassets.com"
        )) {
            assertTrue("网页域名判定应包含: $h", Accel.isWebGithubHost(h))
        }
    }

    /** 非 GitHub 域名不接管：第三方站点不应被本应用代取 */
    @Test
    fun webHostsRejectNonGithub() {
        for (h in listOf(
            "gitee.com", "gitlab.com", "codeberg.org", "gitcode.com",
            "mirrors.tuna.tsinghua.edu.cn",
            // 子串中包含 github 但并非 GitHub 域名——历史上「无差别套用前缀」的缺陷即源于此
            "notgithub.com", "github.com.evil.cn", "evilgithub.com", ""
        )) {
            assertFalse("不应判定为 GitHub 域名: $h", Accel.isWebGithubHost(h))
        }
    }

    /** 关键回归：网页域名判定范围宽于下载，但下载侧规则不得因此放宽 */
    @Test
    fun downloadRulesStayNarrowerThanWeb() {
        // 头像/资产域名属于「仅网页需要」范畴，下载规则不应套用前缀
        for (h in listOf("avatars.githubusercontent.com", "camo.githubusercontent.com", "github.githubassets.com")) {
            assertTrue(Accel.isWebGithubHost(h))
            assertFalse("下载规则不应为资产域名套用前缀: $h", Accel.shouldPrefix("https://$h/a/b"))
        }
        // 下载主要域名正常套用前缀
        assertTrue(Accel.shouldPrefix("https://codeload.github.com/o/r/zip/refs/heads/main"))
        assertTrue(Accel.shouldPrefix("https://raw.githubusercontent.com/o/r/HEAD/README.md"))
        assertTrue(Accel.shouldPrefix("https://api.github.com/repos/o/r"))
    }

    /** 白名单域名既不套用前缀，也不应被代取接管 */
    @Test
    fun neverTouchedHostsStayUntouched() {
        for (u in listOf(
            "https://gitee.com/foo/bar",
            "https://gitlab.com/foo/bar",
            "https://mirrors.tuna.tsinghua.edu.cn/github-release/foo.zip"
        )) {
            assertFalse("不应套用前缀: $u", Accel.shouldPrefix(u))
        }
    }

    /** 下载侧：开关或前缀任一缺失时，enabled() 返回 false，不套用前缀（浏览侧逻辑见其他测试） */
    @Test
    fun disabledMeansNoInterception() {
        AppState.accelOn = false
        assertTrue(Accel.isWebGithubHost("github.com"))   // 域名判定独立于开关状态
        assertFalse(Accel.enabled())
        assertFalse(Accel.shouldPrefix("https://github.com/o/r"))
        AppState.accelOn = true
        AppState.custom = "   "
        assertFalse("前缀为空白时视为未启用", Accel.enabled())
    }
}
