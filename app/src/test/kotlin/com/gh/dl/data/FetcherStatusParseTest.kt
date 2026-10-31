package com.gh.dl.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 内置 wget 的状态码解析测试。
 *
 * wget 无「将状态码输出至 stdout」的参数，应用仅能从 -S 写入
 * stderr 的响应头中提取状态码。解析错误将导致界面显示不正确的状态
 * （如将 404 误判为 200，或将有效的 404 页面误判为整条通道失败）。
 * 该逻辑无法在 JVM 中运行，需通过纯函数单元测试覆盖。
 */
class FetcherStatusParseTest {

    @Test
    fun `取最后一跳不是第一跳`() {
        val s = "  HTTP/1.1 301 Moved Permanently\n  HTTP/1.1 404 Not Found\n"
        assertEquals(404, Fetcher.parseHttpStatus(s))
    }

    @Test
    fun `HTTP 2 的简写格式`() {
        assertEquals(200, Fetcher.parseHttpStatus("  HTTP/2 200\n"))
        assertEquals(503, Fetcher.parseHttpStatus("  HTTP/1.0 503 Service Unavailable\n"))
    }

    @Test
    fun `解析不出来就返回 null 不猜 200`() {
        // "awaiting response... 200 OK" 为 wget 自身进度行，非响应头，不应作为解析输入
        assertNull(Fetcher.parseHttpStatus("HTTP request sent, awaiting response... 200 OK\n"))
        assertNull(Fetcher.parseHttpStatus(""))
        assertNull(Fetcher.parseHttpStatus("Connecting to github.com... connected."))
    }

    @Test
    fun `三跳重定向取终点`() {
        val s = "  HTTP/1.1 301 Moved Permanently\n" +
            "  HTTP/1.1 302 Found\n" +
            "  HTTP/1.1 200 OK\n"
        assertEquals(200, Fetcher.parseHttpStatus(s))
    }
}
