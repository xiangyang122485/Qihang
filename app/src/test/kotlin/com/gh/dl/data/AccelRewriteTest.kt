package com.gh.dl.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Accel.rewriteWith] 加速前缀幂等性回归测试。
 *
 * 用户报告「用了加速前缀但加速不可用，只能重启 APP」：前缀拼接逻辑在特定条件下
 * 未能正确识别「已添加前缀」的 URL，导致重复拼接或遗漏拼接。
 * [Accel.rewriteWith] 为纯函数，可在 JVM 中直接测试前缀拼接与幂等判定。
 *
 * 注意：[Accel.shouldPrefix] 的域名分流规则已由 AccelHostRoutingTest 覆盖，
 * 本测试仅关注「给定前缀后，URL 改写是否正确且幂等」。
 */
class AccelRewriteTest {

    private val prefix = "https://ghp.example.com"

    // ===== 基本改写 =====

    @Test
    fun `普通 GitHub URL 添加前缀`() {
        val url = "https://github.com/owner/repo/releases/download/v1.0/file.zip"
        assertEquals(
            "$prefix/https://github.com/owner/repo/releases/download/v1.0/file.zip",
            Accel.rewriteWith(url, prefix)
        )
    }

    @Test
    fun `raw 子域名 URL 添加前缀`() {
        val url = "https://raw.githubusercontent.com/owner/repo/HEAD/README.md"
        assertEquals(
            "$prefix/$url",
            Accel.rewriteWith(url, prefix)
        )
    }

    // ===== 幂等性：已指向前缀 host 的 URL 不再重复拼接 =====

    @Test
    fun `已指向前缀 host 的 URL 原样返回`() {
        val alreadyPrefixed = "$prefix/https://github.com/owner/repo/archive/refs/heads/main.zip"
        assertEquals(alreadyPrefixed, Accel.rewriteWith(alreadyPrefixed, prefix))
    }

    @Test
    fun `改写两次等于改写一次`() {
        val url = "https://codeload.github.com/owner/repo/zip/refs/tags/v2.0"
        val once = Accel.rewriteWith(url, prefix)
        val twice = Accel.rewriteWith(once, prefix)
        assertEquals("重复改写不应产生双重前缀", once, twice)
    }

    // ===== host 大小写不敏感 =====

    @Test
    fun `前缀 host 大小写差异不影响幂等判定`() {
        val url = "https://GHP.Example.Com/some/path"
        assertEquals(url, Accel.rewriteWith(url, prefix))
    }

    @Test
    fun `URL host 大小写差异不影响改写`() {
        val url = "https://GitHub.com/owner/repo/releases/download/v1.0/f.zip"
        assertEquals(
            "$prefix/$url",
            Accel.rewriteWith(url, prefix)
        )
    }

    // ===== 空白前缀 =====

    @Test
    fun `空白前缀不修改 URL`() {
        val url = "https://github.com/owner/repo"
        assertEquals(url, Accel.rewriteWith(url, ""))
        assertEquals(url, Accel.rewriteWith(url, "   "))
    }

    // ===== 非 GitHub 域名也按前缀规则改写 =====
    // rewriteWith 本身不做域名过滤（由 shouldPrefix 负责），此处验证纯函数行为

    @Test
    fun `非 GitHub 域名也正常添加前缀`() {
        val url = "https://example.com/file.zip"
        assertEquals("$prefix/$url", Accel.rewriteWith(url, prefix))
    }

    // ===== 前缀带结尾斜杠的容错 =====

    @Test
    fun `前缀带结尾斜杠仍可正确提取 host`() {
        val url = "https://github.com/o/r"
        val pp = "https://ghp.example.com/"
        // rewriteWith 按 "$p/$url" 拼接，前缀带结尾斜杠时产生双斜杠——
        // 这正是 prefix() 中 trimEnd('/') 存在的原因：生产路径已去除结尾斜杠
        assertEquals("$pp/$url", Accel.rewriteWith(url, pp))
    }

    // ===== 畸形 URL 的容错 =====

    @Test
    fun `畸形 URL 不崩溃仍拼接前缀`() {
        // URL 解析异常时 uh 为 null，ph 非 null，不触发幂等判定，因此仍拼接前缀
        // 调用方（shouldPrefix）应确保输入合法 URL
        assertEquals("$prefix/not-a-url", Accel.rewriteWith("not-a-url", prefix))
    }
}
