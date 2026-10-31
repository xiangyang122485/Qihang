package com.gh.dl.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Translator] 回归测试：
 * 只提取正文文本节点，代码块、文件列表与中文片段不提交翻译；
 * 译文写回时保留原标签、链接与图片。
 */
class TranslatorTest {

    @Test
    fun collectsProseButSkipsCodeAndFileList() {
        val html = """
            <html><body>
              <div class="meta">https://github.com/owner/repo</div>
              <div class="chips"><span>★ 12</span></div>
              <p class="desc">A small command line tool.</p>
              <h2>Usage</h2>
              <p>Run the following command.</p>
              <pre><code>echo hello</code></pre>
              <ul class="dirlist"><li>src</li><li>README.md</li></ul>
              <blockquote>Note: requires Java 17.</blockquote>
            </body></html>
        """.trimIndent()
        val out = Translator.collect(html).nodes
        assertTrue(out.any { it.contains("small command line tool") })
        assertTrue(out.any { it == "Usage" })
        assertTrue(out.any { it.contains("Run the following command") })
        assertTrue(out.any { it.contains("requires Java 17") })
        assertFalse(out.any { it.contains("echo hello") })
        assertFalse(out.any { it == "src" || it == "README.md" })
        assertFalse(out.any { it.contains("★") || it.contains("github.com") })
    }

    @Test
    fun deduplicatesIdenticalText() {
        val html = "<p>Same sentence.</p><p>Same sentence.</p><p>Another sentence.</p>"
        val out = Translator.collect(html).nodes
        assertEquals(listOf("Same sentence.", "Another sentence."), out)
    }

    @Test
    fun skipsInlineCodeButKeepsSurroundingText() {
        val out = Translator.collect("<p>Install with <code>brew</code> first.</p>").nodes
        assertFalse(out.any { it.contains("brew") })
        assertTrue(out.any { it.contains("Install with") })
        assertTrue(out.any { it.contains("first") })
    }

    @Test
    fun skipsChinesePages() {
        assertFalse(Translator.needsTranslation("<p>这是一个中文仓库。</p>"))
        assertTrue(Translator.needsTranslation("<p>This is an English repository.</p>"))
        // 只有代码时没有自然语言正文，不应触发翻译
        assertFalse(Translator.needsTranslation("<pre><code>echo hello</code></pre>"))
    }

    @Test
    fun applyKeepsFullPageStructureAndStyle() {
        val html = """<!DOCTYPE html>
            <html lang="zh-CN"><head><style>p{color:red}</style></head>
            <body><p>Hello world.</p></body></html>
        """.trimIndent()
        val out = Translator.apply(html, listOf(Translator.Line("Hello world.", "你好世界。")))
        assertTrue(out.contains("<!doctype html>"))
        assertTrue(out.contains("p{color:red}"))
        assertTrue(out.contains("你好世界。"))
    }

    @Test
    fun applyKeepsInlineLinksAndStructure() {
        val html = "<p>Read the <a href=\"https://example.com\">docs</a> first.</p>"
        val lines = listOf(
            Translator.Line("Read the", "阅读"),
            Translator.Line("docs", "文档"),
            Translator.Line("first.", "首先。")
        )
        val out = Translator.apply(html, lines)
        assertTrue(out.contains("href=\"https://example.com\""))
        assertTrue(out.contains(">文档</a>"))
        assertTrue(out.contains("首先。"))
        assertFalse(out.contains(">docs</a>"))
    }
}
