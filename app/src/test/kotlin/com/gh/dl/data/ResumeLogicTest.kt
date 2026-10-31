package com.gh.dl.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Fetch] 断点续传逻辑回归测试。
 *
 * 用户报告「下载断点重试不可用」：续传时 Range 头格式不正确、或服务器未返回 206
 * 时未丢弃半截临时文件从头写入，导致文件损坏。
 * 该逻辑为纯函数决策，可从 [Fetch.download] 中提取后在 JVM 中直接测试。
 */
class ResumeLogicTest {

    // ===== rangeHeader：断点续传的 Range 请求头构造 =====

    @Test
    fun `resumeFrom=0 时不发送 Range 头`() {
        assertNull(Fetch.rangeHeader(0))
    }

    @Test
    fun `resumeFrom 为正数时生成精确的 bytes=N- 格式`() {
        assertEquals("bytes=1024-", Fetch.rangeHeader(1024))
        assertEquals("bytes=1-", Fetch.rangeHeader(1))
        assertEquals("bytes=999999999-", Fetch.rangeHeader(999999999))
    }

    @Test
    fun `resumeFrom 为负数时不发送 Range 头`() {
        // 负数不应出现于生产路径，但防御性返回 null 可避免生成畸形头
        assertNull(Fetch.rangeHeader(-1))
    }

    // ===== resumeAccepted：服务器是否接受断点续传 =====

    @Test
    fun `206 加 resumeFrom 大于 0 表示续传成功`() {
        assertTrue(Fetch.resumeAccepted(1024, 206))
    }

    @Test
    fun `200 加 resumeFrom 大于 0 表示服务器不支持续传需从头开始`() {
        // 关键回归：服务器返回 200 而非 206 时，必须丢弃半截文件从头写入，
        // 否则新旧字节拼接将产生损坏文件
        assertFalse(Fetch.resumeAccepted(1024, 200))
    }

    @Test
    fun `206 加 resumeFrom=0 不作为续传处理`() {
        // 客户端未请求续传时，206 不应触发追加写入逻辑
        assertFalse(Fetch.resumeAccepted(0, 206))
    }

    @Test
    fun `416 范围不满足时不接受续传`() {
        assertFalse(Fetch.resumeAccepted(1024, 416))
    }

    @Test
    fun `503 服务不可用时不接受续传`() {
        assertFalse(Fetch.resumeAccepted(1024, 503))
    }

    @Test
    fun `302 重定向时不接受续传`() {
        assertFalse(Fetch.resumeAccepted(1024, 302))
    }

    @Test
    fun `resumeFrom=0 时任何状态码均不接受续传`() {
        for (code in listOf(200, 206, 301, 302, 416, 500, 503)) {
            assertFalse("code=$code 时 resumeFrom=0 不应接受续传",
                Fetch.resumeAccepted(0, code))
        }
    }
}
