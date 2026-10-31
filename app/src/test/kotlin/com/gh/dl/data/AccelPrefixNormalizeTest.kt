package com.gh.dl.data

import com.gh.dl.ui.AppState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前缀输入规范化回归测试。
 *
 * 用户报告「加速前缀设置过却完全不起作用」：前缀可能来自手工输入 / 剪贴板 / 导入文件，
 * 前后空白、漏写协议、被引号包裹、结尾多斜杠都会让拼接结果成为非法 URL。
 * 这类坏前缀不会在保存时暴露，而是让 OkHttp 在真正发请求时抛异常。
 */
class AccelPrefixNormalizeTest {

    @After
    fun tearDown() {
        AppState.custom = ""
        AppState.accelOn = false
    }

    @Test
    fun `去除前后空白与结尾斜杠`() {
        assertEquals("https://gh-proxy.com", Accel.normalizePrefix("  https://gh-proxy.com/  "))
        assertEquals("https://gh-proxy.com", Accel.normalizePrefix("https://gh-proxy.com////"))
    }

    @Test
    fun `补全缺失的协议`() {
        assertEquals("https://gh-proxy.com", Accel.normalizePrefix("gh-proxy.com"))
        assertEquals("https://gh-proxy.com", Accel.normalizePrefix("gh-proxy.com/"))
    }

    @Test
    fun `去除引号与尖括号`() {
        assertEquals("https://gh-proxy.com", Accel.normalizePrefix("\"https://gh-proxy.com\""))
        assertEquals("https://gh-proxy.com", Accel.normalizePrefix("<https://gh-proxy.com>"))
        assertEquals("https://gh-proxy.com", Accel.normalizePrefix("“https://gh-proxy.com”"))
    }

    @Test
    fun `非法输入返回空串而不是坏 URL`() {
        assertEquals("", Accel.normalizePrefix(""))
        assertEquals("", Accel.normalizePrefix("   "))
        assertEquals("", Accel.normalizePrefix("https://"))
        assertEquals("", Accel.normalizePrefix("http://exa mple.com"))
        assertEquals("", Accel.normalizePrefix("ftp://gh-proxy.com"))
        assertEquals("", Accel.normalizePrefix("https://a\nhttps://b"))
    }

    @Test
    fun `规范化后的前缀可以真正拼接`() {
        AppState.accelOn = true
        AppState.custom = "  gh-proxy.com/  "
        assertTrue("规范化后应视为已启用", Accel.enabled())
        assertEquals(
            "https://gh-proxy.com/https://github.com/owner/repo/releases/download/v1/a.zip",
            Accel.rewrite("https://github.com/owner/repo/releases/download/v1/a.zip")
        )
    }

    @Test
    fun `坏前缀视为未启用而不是发坏请求`() {
        AppState.accelOn = true
        AppState.custom = "http://exa mple.com"
        assertFalse(Accel.enabled())
        assertEquals(
            "https://github.com/owner/repo",
            Accel.rewrite("https://github.com/owner/repo")
        )
    }
}
