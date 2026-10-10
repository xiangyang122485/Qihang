package com.gh.dl.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 内置 wget 的状态码解析（第八轮）。
 *
 * 为什么值得单独测：wget 没有「把状态码打到 stdout」的参数，App 只能从 -S 写进
 * stderr 的响应头里捞。这条通道一旦解析错，界面就会显示错的状态（把 404 当 200，
 * 或者反过来把有用的 404 页面当成整条通道失败）——而它跑不进 JVM，只能靠纯函数测。
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
        // 「awaiting response... 200 OK」是 wget 自己的进度行，不是响应头，不能拿
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
