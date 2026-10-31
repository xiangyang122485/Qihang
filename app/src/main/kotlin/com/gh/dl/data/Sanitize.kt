package com.gh.dl.data

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/**
 * 第三方 HTML 的白名单净化。
 *
 * README 为仓库作者编写的、完全不可信的 HTML。本地渲染时若原样嵌入 WebView，
 * 等同于为任意仓库提供在 App 界面内执行脚本的入口（XSS），因此必须先经白名单过滤。
 * 净化后 `<script>`、`on*` 事件属性、`javascript:` 链接均被移除。
 *
 * 相对链接保持原样不做二次解析（preserveRelativeLinks 默认 true）：
 * [GhView] 已将 href/src 全部转换为绝对地址，且图片使用 `data:` URI，
 * 再次执行 URL 解析反而会被丢弃。
 */
object Sanitize {

    val ALLOWED: Safelist = Safelist.relaxed()
        // README 中常见的历史写法：居中徽标表格、<font>、折叠块、任务清单复选框
        .addTags(
            "font", "center", "del", "s", "strike", "kbd", "ins", "mark",
            "input", "picture", "video", "source", "article", "section", "aside"
        )
        // 排版依赖 class（.markdown-body / .dirlist / .code / .trunc）识别标签，因此放开 class；
        // 值均为自身生成的页面中的类名，放开不会引入风险
        .addAttributes(":all", "class")
        .addAttributes("font", "align", "color", "size", "face")
        .addAttributes("div", "align")
        .addAttributes("span", "align")
        .addAttributes("p", "align")
        .addAttributes("h1", "align").addAttributes("h2", "align").addAttributes("h3", "align")
        .addAttributes("h4", "align").addAttributes("h5", "align").addAttributes("h6", "align")
        .addAttributes("img", "align", "width", "height")
        .addAttributes("table", "align", "width", "border", "cellpadding", "cellspacing")
        .addAttributes("td", "align", "valign", "width")
        .addAttributes("th", "align", "valign", "width")
        .addAttributes("a", "name")
        .addAttributes("input", "type", "checked", "disabled")
        .addAttributes("video", "src", "controls", "poster", "width", "height", "muted")
        .addAttributes("source", "src", "type")
        // 仅允许这些协议，其余（javascript: / file: / vbscript:）一律移除
        .addProtocols("img", "src", "http", "https", "data")
        .addProtocols("a", "href", "http", "https", "mailto")
        .addProtocols("video", "src", "http", "https")
        .addProtocols("source", "src", "http", "https")

    /** 将一段 HTML 清洗为仅含安全标签；结果中不含 script / on* / javascript: */
    fun html(src: String): String = runCatching { Jsoup.clean(src, ALLOWED) }.getOrDefault("")
}
