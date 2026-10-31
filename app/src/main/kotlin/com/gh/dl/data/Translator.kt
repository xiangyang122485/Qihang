package com.gh.dl.data

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 浏览页正文翻译。
 *
 * 翻译粒度是 **HTML 文本节点**：译文直接写回原节点，标签、链接、图片与 CSS 结构均不变，
 * 因此页面布局不会被译文窗口或额外容器改变。代码（pre/code）、目录列表与标签行不参与翻译。
 *
 * 翻译服务按顺序自动尝试（全部为公开接口，无需用户配置密钥）：
 *   1. 有道翻译演示接口（国内可直连，支持多行批量）
 *   2. Microsoft Edge 翻译（JSON 批量）
 *   3. Google translate_a/single
 *   4. MyMemory
 * 同一批文本优先合并为一次请求；服务不保留换行或返回行数不符时，自动退回逐条请求。
 * 任一服务成功即返回；全部失败时抛出最后一个异常，由界面决定是否提示。
 *
 * 隐私：仅在用户点击翻译，或开启「设置 → 浏览 → 静默翻译」后自动执行时，才会向上述服务
 * 发送当前页面的正文文字；不发送浏览地址、Cookie 或令牌。
 */
object Translator {

    data class Line(val src: String, val dst: String)

    data class Result(
        val engine: String,
        val lines: List<Line>,
        val truncated: Boolean,
        val partial: Boolean
    )

    /** 一次提取的结果；[truncated] 表示因数量或长度上限而未覆盖全部正文 */
    data class Extract(val nodes: List<String>, val truncated: Boolean)

    private const val MAX_NODES = 400
    private const val MAX_TOTAL_CHARS = 20_000
    private const val MAX_NODE_CHARS = 2_000
    private const val GROUP_CHARS = 1_000
    private const val GROUP_COUNT = 20
    private const val BATCH = 20
    private const val DEFAULT_BUDGET_MS = 45_000L

    private val WS = Regex("\\s+")
    private val CJK = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")

    /** 不翻译的标签：代码与页面元数据不是自然语言正文 */
    private val SKIP_TAGS = setOf("script", "style", "noscript", "pre", "code", "title")
    /** 不翻译的容器：目录列表、仓库标签行、截断提示 */
    private val SKIP_CLASSES = setOf("meta", "dirlist", "chips", "trunc")

    /** 翻译结果缓存：同一页面（或同一批文字）重复翻译不再联网 */
    private val cache = ConcurrentHashMap<String, Result>()

    private fun cacheKey(blocks: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        val d = md.digest(blocks.joinToString("\u0000").toByteArray())
        return d.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun normalize(s: String): String = s.replace(WS, " ").trim()

    /** 是否为以中文为主的文字；日文假名、韩文等不计入中文比例，仍会触发翻译 */
    private fun isChinese(text: String): Boolean {
        var letters = 0
        var cjk = 0
        for (ch in text) {
            if (!ch.isLetter()) continue
            letters++
            if (CJK.matches(ch.toString())) cjk++
        }
        return letters >= 2 && cjk.toDouble() / letters >= 0.35
    }

    private class Collector {
        val nodes = LinkedHashSet<String>()
        var chars = 0
        var truncated = false

        fun walk(el: Element) {
            if (truncated) return
            for (node in el.childNodes()) {
                when (node) {
                    is TextNode -> add(node.text())
                    is Element -> {
                        if (skip(node)) continue
                        walk(node)
                    }
                }
            }
        }

        private fun skip(el: Element): Boolean =
            el.tagName().lowercase() in SKIP_TAGS ||
                SKIP_CLASSES.any { el.hasClass(it) }

        private fun add(raw: String) {
            val t = normalize(raw)
            if (t.length < 2) return
            if (t.none { it.isLetter() }) return
            if (isChinese(t)) return
            if (t.length > MAX_NODE_CHARS) return
            if (nodes.size >= MAX_NODES || chars + t.length > MAX_TOTAL_CHARS) {
                truncated = true
                return
            }
            if (nodes.add(t)) chars += t.length
        }
    }

    /**
     * 提取页面中需要翻译的文本节点：跳过代码、目录列表、标签行与已以中文为主的片段，
     * 相同文字只保留一份。
     */
    fun collect(html: String): Extract {
        val doc = Jsoup.parse(html)
        val c = Collector()
        c.walk(doc.body())
        return Extract(c.nodes.toList(), c.truncated)
    }

    /** 页面是否包含需要翻译的非中文正文；用于「静默翻译」的触发判断 */
    fun needsTranslation(html: String): Boolean = collect(html).nodes.isNotEmpty()

    /**
     * 将译文写回原 HTML：只替换文本节点，保留标签、属性、链接与图片。
     * [lines] 中缺失或为空的条目保持原文。
     */
    fun apply(html: String, lines: List<Line>): String {
        if (lines.isEmpty()) return html
        val map = HashMap<String, String>(lines.size * 2)
        for (line in lines) {
            val dst = normalize(line.dst)
            if (dst.isNotBlank() && dst != line.src) map[line.src] = dst
        }
        if (map.isEmpty()) return html

        val doc = Jsoup.parse(html)
        replaceIn(doc.body(), map)
        return doc.outerHtml()
    }

    private fun replaceIn(el: Element, map: Map<String, String>) {
        for (node in el.childNodes()) {
            when (node) {
                is TextNode -> {
                    val raw = node.text()
                    val src = normalize(raw)
                    val dst = map[src] ?: continue
                    val start = raw.indexOfFirst { !it.isWhitespace() }
                    val end = raw.indexOfLast { !it.isWhitespace() }
                    if (start < 0 || end < start) continue
                    node.text(raw.substring(0, start) + dst + raw.substring(end + 1))
                }
                is Element -> {
                    if (node.tagName().lowercase() in SKIP_TAGS) continue
                    if (SKIP_CLASSES.any { node.hasClass(it) }) continue
                    replaceIn(node, map)
                }
            }
        }
    }

    /** 执行翻译；[blocks] 为空时抛出异常 */
    fun translate(
        blocks: List<String>,
        truncated: Boolean = false,
        budgetMs: Long = DEFAULT_BUDGET_MS
    ): Result {
        require(blocks.isNotEmpty()) { "当前页面没有可翻译的正文" }
        val key = cacheKey(blocks)
        cache[key]?.let { return it }
        val deadline = System.currentTimeMillis() + budgetMs
        var last: Throwable? = null

        // 顺序按「国内网络可达性 + 速度」排列：有道演示接口在国内可直连且支持较长文本；
        // Edge/Google 在部分网络下不可达，仅在前面服务失败时尝试
        val engines = listOf(
            Engine("有道翻译", ::youdaoBatch, ::youdaoOne),
            Engine("Microsoft 翻译", ::edge, { edge(listOf(it)).first() }),
            Engine("Google 翻译", ::googleBatch, ::googleOne),
            Engine("MyMemory", null, ::mymemoryOne)
        )
        for (engine in engines) {
            try {
                val dst = ArrayList<String>(blocks.size)
                var partial = false
                var i = 0
                while (i < blocks.size) {
                    if (System.currentTimeMillis() >= deadline) { partial = true; break }
                    val group = group(blocks, i)
                    val out = if (engine.batch != null) {
                        try {
                            engine.batch.invoke(group)
                        } catch (t: Throwable) {
                            // 批量接口不保留换行或返回行数不符时，退回逐条请求
                            group.map { engine.one(it) }
                        }
                    } else {
                        group.map { engine.one(it) }
                    }
                    if (out.size != group.size) throw RuntimeException("${engine.name} 返回结果数量不符")
                    dst.addAll(out)
                    i += group.size
                }
                if (dst.isEmpty()) throw RuntimeException("${engine.name} 返回空结果")
                // 超时中断时只返回已完成的段落，未翻译部分保持原文
                val out = Result(
                    engine = engine.name,
                    lines = (0 until dst.size).map { Line(blocks[it], dst[it]) },
                    truncated = truncated,
                    partial = partial
                )
                if (!partial) {
                    if (cache.size > 8) cache.clear()
                    cache[key] = out
                }
                return out
            } catch (t: Throwable) {
                last = t
            }
        }
        throw RuntimeException(last?.message?.take(60) ?: "翻译服务均不可用")
    }

    private class Engine(
        val name: String,
        val batch: ((List<String>) -> List<String>)?,
        val one: (String) -> String
    )

    /** 从 [start] 起取一组：条数与总长度均受上限约束；单条超长时单独成组 */
    private fun group(blocks: List<String>, start: Int): List<String> {
        var end = start
        var chars = 0
        while (end < blocks.size && end - start < GROUP_COUNT) {
            val add = blocks[end].length + if (end > start) 1 else 0
            if (end > start && chars + add > GROUP_CHARS) break
            chars += add
            end++
        }
        if (end == start) end = start + 1
        return blocks.subList(start, end)
    }

    // ---- 各翻译服务 ----

    private fun client() = Net.http.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var edgeToken: String = ""
    @Volatile private var edgeTokenAt: Long = 0L

    private fun edgeToken(): String {
        val now = System.currentTimeMillis()
        if (edgeToken.isNotBlank() && now - edgeTokenAt < 8 * 60 * 1000) return edgeToken
        val req = Request.Builder()
            .url("https://edge.microsoft.com/translate/auth")
            .header("User-Agent", Net.UA)
            .build()
        client().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("Edge 鉴权 HTTP ${r.code}")
            val t = r.body?.string().orEmpty().trim()
            if (t.length < 100) throw RuntimeException("Edge 鉴权响应无效")
            edgeToken = t
            edgeTokenAt = now
            return t
        }
    }

    private fun edge(texts: List<String>): List<String> {
        val token = edgeToken()
        val body = JSONArray()
        texts.forEach { body.put(JSONObject().put("Text", it)) }
        val req = Request.Builder()
            .url("https://api-edge.cognitive.microsofttranslator.com/translate?api-version=3.0&to=zh-Hans")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("User-Agent", Net.UA)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val txt = client().newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RuntimeException("Edge 翻译 HTTP ${r.code}")
            t
        }
        val arr = JSONArray(txt)
        return texts.indices.map { i ->
            arr.optJSONObject(i)?.optJSONArray("translations")
                ?.optJSONObject(0)?.optString("text").orEmpty()
        }
    }

    /** 单文本接口的分段长度：超过服务端上限时按句子边界切分，避免被截断 */
    private fun chunks(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            var end = minOf(i + limit, text.length)
            if (end < text.length) {
                val cut = text.lastIndexOfAny(charArrayOf(' ', '。', '.', ',', '，'), end)
                if (cut > i + limit / 2) end = cut + 1
            }
            out.add(text.substring(i, end))
            i = end
        }
        return out
    }

    /** 按换行合并为一次请求；服务返回的行数必须与输入一致，否则由调用方逐条重试 */
    private fun batchByNewline(texts: List<String>, fn: (String) -> String): List<String> {
        val out = fn(texts.joinToString("\n"))
        val parts = out.split("\n")
        if (parts.size != texts.size) throw RuntimeException("批量翻译返回行数不符")
        return parts
    }

    private fun googleBatch(texts: List<String>): List<String> =
        batchByNewline(texts, ::googleOne)

    private fun googleOne(text: String): String {
        val form = FormBody.Builder()
            .add("q", text)
            .add("sl", "auto")
            .add("tl", "zh-CN")
            .add("dt", "t")
            .add("client", "gtx")
            .build()
        val req = Request.Builder()
            .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t")
            .header("User-Agent", Net.UA)
            .post(form)
            .build()
        val txt = client().newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RuntimeException("Google 翻译 HTTP ${r.code}")
            t
        }
        val seg = JSONArray(txt).optJSONArray(0) ?: throw RuntimeException("Google 翻译响应无效")
        val sb = StringBuilder()
        for (i in 0 until seg.length()) {
            sb.append(seg.optJSONArray(i)?.optString(0).orEmpty())
        }
        return sb.toString()
    }

    private fun youdaoBatch(texts: List<String>): List<String> =
        batchByNewline(texts, ::youdaoOne)

    private fun youdaoOne(text: String): String = chunks(text, 1_200).joinToString("") { part ->
        val q = URLEncoder.encode(part, "UTF-8")
        val req = Request.Builder()
            .url("https://aidemo.youdao.com/trans?q=$q&from=Auto&to=zh-CHS")
            .header("User-Agent", Net.UA)
            .build()
        val txt = client().newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RuntimeException("有道翻译 HTTP ${r.code}")
            t
        }
        val arr = JSONObject(txt).optJSONArray("translation")
            ?: throw RuntimeException("有道翻译响应无效")
        (0 until arr.length()).joinToString("") { arr.optString(it) }
    }

    private fun mymemoryOne(text: String): String = chunks(text, 450).joinToString("") { part ->
        val q = URLEncoder.encode(part, "UTF-8")
        val req = Request.Builder()
            .url("https://api.mymemory.translated.net/get?q=$q&langpair=en|zh-CN")
            .header("User-Agent", Net.UA)
            .build()
        val txt = client().newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RuntimeException("MyMemory HTTP ${r.code}")
            t
        }
        JSONObject(txt).optJSONObject("responseData")
            ?.optString("translatedText")?.takeIf { it.isNotBlank() }
            ?: throw RuntimeException("MyMemory 响应无效")
    }
}
