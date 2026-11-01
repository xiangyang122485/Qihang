package com.gh.dl.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/**
 * GitHub 的 Atom 订阅端点解析器：`releases.atom`（版本发布）与 `tags.atom`（标签）。
 *
 * 为什么走 Atom 而不是 api.github.com：
 * 公共 ghproxy 类中转普遍**不代理 api.github.com**（真机检测证实：文件 ✓、API ✗），
 * 但 `…/releases.atom`、`…/tags.atom` 属于 github.com 网页域下的静态 XML 端点，
 * 会被这些中转当作「文件」原样转发，因此可以借**下载用的加速前缀**来获取，绕开 API 这条断腿。
 *
 * 解析用 Android 自带的 XmlPullParser，不引第三方依赖；对畸形/截断内容一律安全降级返回空。
 */
object Atom {

    /** 解析 releases.atom，返回按发布倒序的 Release（含 tag / 标题 / 时间 / 网页链接）。
     *  Atom 不含二进制资产列表，故 assets 为空，由调用方按需经 expanded_assets 补齐。 */
    fun parseReleases(xml: String): List<Release> {
        val out = ArrayList<Release>()
        forEachEntry(xml) { tag, title, updated, link ->
            if (tag.isNotBlank()) out.add(
                Release(
                    tag = tag,
                    name = title.ifBlank { tag },
                    published = updated,
                    htmlUrl = link.ifBlank { "https://github.com/releases/tag/$tag" },
                    assets = emptyList()
                )
            )
        }
        return out
    }

    /** 解析 tags.atom，返回 (tag, 时间) 列表。 */
    fun parseTags(xml: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        forEachEntry(xml) { tag, _, updated, _ ->
            if (tag.isNotBlank()) out.add(tag to updated)
        }
        return out
    }

    /** 遍历 Atom 的每个 <entry>，抽出 tag（从 /releases/tag/ 或 /tree/ 链接解析）、标题、更新时间、链接。 */
    private inline fun forEachEntry(
        xml: String, onEntry: (tag: String, title: String, updated: String, link: String) -> Unit
    ) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(xml.reader())
            var inEntry = false
            var title = ""; var updated = ""; var link = ""; var linkHref = ""
            for (event in parser.eventSequence()) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "entry" -> { inEntry = true; title = ""; updated = ""; link = ""; linkHref = "" }
                        "title" -> if (inEntry) title = parser.nextText().trim()
                        "updated", "published" -> if (inEntry && updated.isBlank()) updated = parser.nextText().trim()
                        "link" -> if (inEntry) linkHref = parser.getAttributeValue(null, "href").orEmpty().trim()
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "entry") {
                        inEntry = false
                        val eff = link.ifBlank { linkHref }
                        onEntry(tagFromLink(eff), title, updated, eff)
                    }
                }
            }
        } catch (e: Exception) {
            // 解析失败一律返回已收集部分（调用方按空处理并回退其它通道）
        }
    }

    private fun XmlPullParser.eventSequence(): Sequence<Int> = sequence {
        var e = eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            yield(e)
            e = next()
        }
    }

    /** 从链接尾段取 tag：`…/releases/tag/v1.2.3` → v1.2.3；`…/tree/v1.2.3` → v1.2.3；否则取末段。 */
    private fun tagFromLink(link: String): String {
        val l = link.trimEnd('/')
        val rt = l.indexOf("/releases/tag/")
        if (rt >= 0) return decode(l.substring(rt + "/releases/tag/".length))
        val tr = l.indexOf("/tree/")
        if (tr >= 0) return decode(l.substring(tr + "/tree/".length))
        return decode(l.substringAfterLast('/'))
    }

    private fun decode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
