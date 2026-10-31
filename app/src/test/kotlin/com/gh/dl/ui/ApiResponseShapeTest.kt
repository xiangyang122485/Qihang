package com.gh.dl.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub API 响应形状回归测试。
 *
 * 仓库信息是对象 `{}`，Release / 分支 / 标签是数组 `[]`。
 * 1.3.0 早期只接受 `{`，导致 Release 请求把合法数组当成中转站首页，
 * 触发一次多余的直连重试，最终在 DNS 污染环境下报「域名解析失败」。
 */
class ApiResponseShapeTest {

    @Test
    fun `仓库对象与 Release 数组都算合法 JSON`() {
        assertTrue(isGithubApiJson("""{"full_name":"owner/repo"}"""))
        assertTrue(isGithubApiJson("""[{"tag_name":"v1.0"}]"""))
        assertTrue(isGithubApiJson("[]"))
    }

    @Test
    fun `前后空白与 BOM 不影响判断`() {
        assertTrue(isGithubApiJson("\n  [1,2,3]\n"))
        assertTrue(isGithubApiJson("\uFEFF{\"a\":1}"))
    }

    @Test
    fun `中转站首页与空响应必须拒绝`() {
        assertFalse(isGithubApiJson(""))
        assertFalse(isGithubApiJson("   "))
        assertFalse(isGithubApiJson("<!DOCTYPE html><html>GitHub加速下载代理</html>"))
        assertFalse(isGithubApiJson("Not Found"))
    }
}
