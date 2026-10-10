package com.gh.dl.data

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/**
 * 第三方 HTML 的白名单净化。
 *
 * README 是仓库作者写的、完全不可信的 HTML。本地渲染时如果原样塞进 WebView，
 * 等于给任意仓库一个在自己 App 界面里执行脚本的入口（XSS）——所以必须先过白名单。
 * 净化后 `<script>`、`on*` 事件属性、`javascript:` 链接都会被剥掉。
 *
 * 相对链接保持原样不做二次解析（preserveRelativeLinks 默认 true）：
 * [GhView] 已经把 href/src 全部换成绝对地址了，而且图片用的是 `data:` URI，
 * 再走一遍 URL 解析反而会被丢掉。
 */
object Sanitize {

    val ALLOWED: Safelist = Safelist.relaxed()
        // README 里常见的历史写法：居中徽标表格、<font>、折叠块、任务清单复选框
        .addTags(
            "font", "center", "del", "s", "strike", "kbd", "ins", "mark",
            "input", "picture", "video", "source", "article", "section", "aside"
        )
        // 排版靠 class（.markdown-body / .dirlist / .code / .trunc）认标签，所以放开 class；
        // 值都是我们自己生成的页面里那些类名，放开不会引入风险
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
        // 只放这些协议，其余（javascript: / file: / vbscript:）一律去掉
        .addProtocols("img", "src", "http", "https", "data")
        .addProtocols("a", "href", "http", "https", "mailto")
        .addProtocols("video", "src", "http", "https")
        .addProtocols("source", "src", "http", "https")

    /** 把一段 HTML 洗成只剩安全标签；结果里不会带 script / on* / javascript: */
    fun html(src: String): String = runCatching { Jsoup.clean(src, ALLOWED) }.getOrDefault("")
}
