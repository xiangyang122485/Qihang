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
 * 锁住一条真机踩出来的教训：**网页绝不能套加速前缀**。
 * 闪电用的前缀是 gh-proxy 风格的文件中转，只认 /raw、/releases/download、/archive；
 * 把仓库主页 URL 交给它，它判断不出是文件，就把自己的首页返回给你
 * （实测页面变成「GitHub加速下载代理」+ 它的 logo 和广告链接）。
 * 所以 [Accel.isWebGithubHost] 只用于「判断该不该用真实 IP 直连代取」，
 * 而 [Accel.shouldPrefix] 只作用于下载/API——两者绝不能混用。
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

    /** 网页域名判定：GitHub 官方与各资产子域都算，供浏览页决定要不要代取 */
    @Test
    fun webHostsCoverGithubAssetDomains() {
        for (h in listOf(
            "github.com", "www.github.com", "api.github.com", "codeload.github.com",
            "raw.githubusercontent.com", "objects.githubusercontent.com",
            "avatars.githubusercontent.com", "camo.githubusercontent.com",
            "github.githubassets.com"
        )) {
            assertTrue("网页域名应认: $h", Accel.isWebGithubHost(h))
        }
    }

    /** 非 GitHub 域名一律不接管：别人的站不该被本 App 代取 */
    @Test
    fun webHostsRejectNonGithub() {
        for (h in listOf(
            "gitee.com", "gitlab.com", "codeberg.org", "gitcode.com",
            "mirrors.tuna.tsinghua.edu.cn",
            // 子串里带 github 但不是 GitHub 域名——历史上「无差别套前缀」正是栽在这里
            "notgithub.com", "github.com.evil.cn", "evilgithub.com", ""
        )) {
            assertFalse("不该当成 GitHub: $h", Accel.isWebGithubHost(h))
        }
    }

    /** 关键回归：网页域名判定比下载宽，但下载侧规则绝不能因此变宽 */
    @Test
    fun downloadRulesStayNarrowerThanWeb() {
        // 头像/资产域名属于「网页才需要」，下载规则里不该套前缀
        for (h in listOf("avatars.githubusercontent.com", "camo.githubusercontent.com", "github.githubassets.com")) {
            assertTrue(Accel.isWebGithubHost(h))
            assertFalse("下载不该给资产域名套前缀: $h", Accel.shouldPrefix("https://$h/a/b"))
        }
        // 下载主力域名照常套前缀
        assertTrue(Accel.shouldPrefix("https://codeload.github.com/o/r/zip/refs/heads/main"))
        assertTrue(Accel.shouldPrefix("https://raw.githubusercontent.com/o/r/HEAD/README.md"))
        assertTrue(Accel.shouldPrefix("https://api.github.com/repos/o/r"))
    }

    /** 白名单域名既不套前缀也不该被代取接管 */
    @Test
    fun neverTouchedHostsStayUntouched() {
        for (u in listOf(
            "https://gitee.com/foo/bar",
            "https://gitlab.com/foo/bar",
            "https://mirrors.tuna.tsinghua.edu.cn/github-release/foo.zip"
        )) {
            assertFalse("不该套前缀: $u", Accel.shouldPrefix(u))
        }
    }

    /** 下载侧：开关或前缀任一缺失，enabled() 即为假，不套前缀（浏览不看这里，见下） */
    @Test
    fun disabledMeansNoInterception() {
        AppState.accelOn = false
        assertTrue(Accel.isWebGithubHost("github.com"))   // 域名判定与开关无关
        assertFalse(Accel.enabled())
        assertFalse(Accel.shouldPrefix("https://github.com/o/r"))
        AppState.accelOn = true
        AppState.custom = "   "
        assertFalse("前缀为空也算未启用", Accel.enabled())
    }
}
