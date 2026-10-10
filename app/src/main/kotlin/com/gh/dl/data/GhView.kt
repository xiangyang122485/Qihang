package com.gh.dl.data

import com.gh.dl.ui.AppState
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 「浏览」的抓取 + 本地渲染（不依赖 WebView 联网）。
 *
 * 为什么不用 WebView 直接打开网页（真机 + 实测双重证实）：
 *  1. 下载用的加速前缀（gh-proxy 那套）是**文件**中转。把仓库主页交给它，它判断不出是文件，
 *     就返回**自己的首页**（实测：200、约 60KB、标题「GitHub加速下载代理 - 快速访问 GitHub 文件」）。
 *     所以前缀对网页根本无效，这是上一版方案的致命错误。
 *  2. 网页镜像 githubfast.com **专门拦浏览器**：同一个 URL，
 *     浏览器 UA → 403「To prevent abuse, only git and CLI access are allowed」；
 *     `curl/8.4.0` → 200 完整页面（实测 330KB~690KB）。WebView 发的正是浏览器 UA，所以必然打不开。
 *
 * 于是改成用户提的思路：**像 git / curl 那样用命令行 UA 抓 HTML，本地解析后自己渲染**。
 * WebView 只显示我们生成的这一页，页内没有任何外部资源，所以它不需要也不能联网。
 *
 * 可行性关键点（已实测）：
 *  - 该镜像的 README 是**服务端渲染**的，抓来的 HTML 里直接有 `<article class="markdown-body">`
 *    正文和已转好的 `<a>`/`<img>`，因此不需要 JS 引擎；
 *  - 源码文件页的逐行内容也在同一份 HTML 的 JSON（rawLines）里，不用再发第二次请求；
 *  - 目录页能从 `td .react-directory-truncate a` 拿到条目名与链接；
 *  - releases / issues / blob / tree / raw 各页该镜像都返回 200。
 */
object GhView {

    /** 命令行特征 UA：镜像站放行 git/curl/wget 类，拦浏览器类 */
    const val CLI_UA = "curl/8.4.0"

    /**
     * HTML 解析引擎（「设置 → 浏览 → 解析引擎」）。
     *
     * 先说清一件事：用户点名的 selectolax / parsel / lxml / html5lib **都是 Python 库**，
     * Android APK 里没有 CPython，带不进去（要带就得打包整个解释器 + 原生扩展，包体和风险都不划算）。
     * 这里给的是它们在 JVM 上的对等物，并在设置页里如实标注对应关系：
     *  - [ENGINE_JSOUP]   = 默认，Jsoup 的 HTML5 解析器（等价 html5lib 的建树口径，且唯一自带 CSS 选择器）
     *  - [ENGINE_XML]      = Jsoup 的 XML 解析器（lxml 的 XML 模式对等物；实测快 2~3 倍，
     *                        代价是遇到不闭合标签可能整页失败，所以失败会自动回落默认引擎）
     *  - [ENGINE_REPAIR]   = 先用 nu.validator 的 WHATWG html5 解析器把畸形 HTML **修一遍**
     *                        （html5lib 的 JVM 对等物），再交给 Jsoup 建树 —— 最稳，慢一档
     *  - [ENGINE_FAST]     = 零拷贝直取（Jericho 对等思路）：先在原文里定位
     *                        `<article class="markdown-body">` 的字符区间，只把那 20~30KB 交给
     *                        Jsoup 解析。实测整页 330KB 解析 11ms，切正文后只需约 1ms，
     *                        是这四项里唯一能**真的**让浏览变快的选项。
     * 实测数据（同一份 CYQawa/YunX 页面，7 次取中位数，见修复报告）：
     *  jsoup-html5 11.3ms / jsoup-xml 3.7ms / validator 23.0ms / tagsoup 19.5ms / jericho 8.2ms，
     * 五者对畸形 HTML 都不抛异常 —— 所以「解析器能力不足」并不是截断的原因，真正的原因见 [IMG_HOST]。
     */
    const val ENGINE_JSOUP = "jsoup"
    const val ENGINE_XML = "jsoup-xml"
    const val ENGINE_REPAIR = "repair"
    const val ENGINE_FAST = "fast"
    val ENGINES = listOf(
        ENGINE_JSOUP to "Jsoup HTML5（默认，最稳）",
        ENGINE_XML to "XML 模式（快 2~3 倍，畸形页面会自动回落）",
        ENGINE_REPAIR to "先修复再解析（html5lib 对等，最抗畸形）",
        ENGINE_FAST to "只解析正文（最快，推荐慢网络时用）"
    )

    /** 图片显示方式（「设置 → 浏览 → 图片」） */
    const val IMAGE_LOCAL = "local"
    const val IMAGE_BASE64 = "base64"
    const val IMAGE_OFF = "off"
    val IMAGE_MODES = listOf(
        IMAGE_LOCAL to "本地缓存（推荐：正文不被挤掉，首屏快，7 天内复用）",
        IMAGE_BASE64 to "内联进页面（旧行为：整页自包含，但很慢、会挤掉正文）",
        IMAGE_OFF to "不显示图片（最省流量）"
    )

    /** 内置网页镜像站（用户没自定义时的默认值） */
    const val DEFAULT_PAGE_MIRROR = "https://githubfast.com"

    /**
     * 网页镜像站（按顺序试）。只放「实测对 CLI UA 放行、且能代理 HTML 页面」的站。
     * 注意：下载用的加速前缀（[Accel.prefix]）**不属于**这里——它代理不了网页，见类注释。
     *
     * 做成可变列表是因为这类站换域名很频繁（实测 hub.gitmirror / kkgithub / bgithub / ghp.ci
     * 当前全部连不通，只剩 githubfast 一个能用），所以允许在「设置 → 加速」里自己填。
     */
    var pageMirrors: List<String> = listOf(DEFAULT_PAGE_MIRROR)
        set(value) {
            field = value.map { it.trim().trimEnd('/') }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
        }

    /** 从任意文本（剪贴板 / 文件 / 多行输入）里提取镜像站地址 */
    fun parseMirrors(text: String): List<String> = text.lines()
        .map { it.trim().trimEnd('/') }
        .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
        .distinct()

    /** 恢复内置镜像站 */
    fun resetMirrors() { pageMirrors = listOf(DEFAULT_PAGE_MIRROR) }

    /** 镜像站连通性探测结果 */
    class MirrorProbe(val ok: Boolean, val ms: Long, val detail: String)

    /**
     * 探一个镜像站能不能用：抓一个真实仓库主页（和浏览完全同款的方式：CLI UA + [Net.browse]），
     * 看返回的是不是真页面。
     *
     * 为什么不能只看状态码：gh-proxy 那类文件前缀对网页也会回 200，只是回的是它自己的首页，
     * 所以这里额外要求「标题里没有它自己的广告词」且「HTML 里有 markdown-body / 仓库标记」。
     * [base] 传空串表示探「直连 GitHub」这一条。
     */
    fun probeMirror(base: String): MirrorProbe {
        val target = if (base.isBlank()) "https://github.com/microsoft/vscode"
                     else base.trimEnd('/') + "/microsoft/vscode"
        val t0 = System.currentTimeMillis()
        return runCatching {
            val req = Request.Builder().url(target).header("User-Agent", CLI_UA).build()
            Net.browse.newCall(req).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                if (!resp.isSuccessful) return@use MirrorProbe(false, ms, "HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val looksRepo = body.contains("markdown-body") || body.contains("react-directory-truncate")
                val selfHome = body.contains("加速下载代理") || body.contains("ghproxy")
                MirrorProbe(
                    looksRepo && !selfHome, ms,
                    when {
                        selfHome -> "返回的是它自己的首页（不是仓库页）"
                        !looksRepo -> "不是仓库页（${body.length / 1024}KB）"
                        else -> "正常 · ${body.length / 1024}KB"
                    }
                )
            }
        }.getOrElse { MirrorProbe(false, System.currentTimeMillis() - t0, it.javaClass.simpleName) }
    }

    /**
     * 由设置页把用户自定义的镜像站灌进来；空列表表示「没自定义」，回落到内置那一个。
     * （不这样做的话，用户删光列表就会一个镜像都没有，浏览直接只能直连。）
     */
    fun applyMirrors(list: List<String>) {
        pageMirrors = if (list.isEmpty()) listOf(DEFAULT_PAGE_MIRROR) else list
    }

    /**
     * 当前生效的镜像域名后缀（页面域 githubfast.com、资产域 usercontent./assets./external.…）。
     * 跟着 [pageMirrors] 走，用户换站以后地址折算才不会认错。
     */
    private val mirrorSuffix: String
        get() = runCatching { URL(pageMirrors.firstOrNull() ?: DEFAULT_PAGE_MIRROR).host }
            .getOrDefault("githubfast.com")

    /** 开头的「scheme:」片段（只认合法 scheme 字符，避免把 "a:b" 之类误判） */
    private val SCHEME_IN_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /** 点分十进制 IPv4 字面量 */
    private val IPV4 = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

    /** GitHub 自家资产的域名后缀（其余按「外站图」处理，走镜像的通用转发通道） */
    private val GITHUB_IMAGE_SUFFIXES: List<String>
        get() = listOf("github.com", "githubusercontent.com", "githubassets.com", mirrorSuffix)

    /** 单张图片大小上限（超过就不取，避免一张 4K 长图吃掉整页配额） */
    private const val MAX_IMAGE_BYTES = 2_000_000L

    /**
     * 图片缓存的新鲜期。缓存文件名只用「图片 URL 的 hash」当 key，**不含内容指纹**，
     * 所以作者把同一张图换掉（URL 不变、内容变了）时，没有过期时间就永远显示旧图。
     * 7 天是一个折中：README 配图不会天天改，而一次重新取图只花 1 个请求。
     * 到期后按未命中处理 → 重新取 → 覆盖同名文件（见 [loadImage]）。
     */
    private const val IMAGE_CACHE_TTL_MS = 7L * 24 * 3600 * 1000

    /** 与 [Fetcher.MAX_BODY_BYTES] 同口径，只用于把「响应被截」写成用户看得懂的话 */
    private const val MAX_RESPONSE_MB = 8

    /**
     * 「本地缓存」模式下同时取图的最大并发数。
     * WebView 会按视口并发发起一批 ghimg.local 请求，不设限的话用户快速滑过长 README
     * 能一次打出十几条请求，很容易被镜像站按 IP 限流（连主文档都开始 403）。
     * 命中缓存不占名额，只有真要联网时才排队。
     */
    private const val IMAGE_FETCH_PARALLEL = 4
    private val imageFetchSlots = Semaphore(IMAGE_FETCH_PARALLEL, true)

    /** 等别的线程取同一张图的时间上限 / 排队抢取图名额的时间上限 */
    private const val IN_FLIGHT_WAIT_S = 8L
    private const val SLOT_WAIT_S = 3L

    /**
     * 同一页里可能有多条 [shouldInterceptRequest] 并发进来（不同线程），
     * 而缓存的读、写、按名删除都是多步操作——不加锁就会出现
     * 「两个线程各写同一个 key，字节交叉 → 落盘一张坏图」，
     * 而 WebView 不联网，坏图永远没有第二次机会（只能靠这里自己发现并重取）。
     * 用固定的一组锁按 key 分片：既避免全局锁把并发压成串行，
     * 又保证同一张图的读写互斥。
     */
    private const val LOCK_STRIPES = 16
    private val imgLocks = Array(LOCK_STRIPES) { Any() }
    private fun lockFor(key: String): Any =
        imgLocks[(key.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES]

    /** 缓存文件名里的 key 必须是纯十六进制短串，否则一律拒绝读写（防目录穿越） */
    private val KEY_RE = Regex("^[0-9a-f]{8,16}$")

    /**
     * 正在联网取图的 key → 完成闩。
     * WebView 对同一张图可能并发发起两次请求（不同 ROM 内核行为不一样），
     * 去重以后只有第一个线程真的联网，其余等它落盘后直接读缓存——
     * 这样既不重复耗流量，也不会两个线程同时对同一个文件写。
     */
    private val inFlight = ConcurrentHashMap<String, CountDownLatch>()

    /** base64 内联模式（旧行为，作为可选档保留）的整页配额 */
    private const val MAX_INLINE_TOTAL_BYTES = 2_000_000L

    /**
     * 一页最多处理多少张图。
     * 「本地缓存」模式下这个数字不再受 HTML 体积约束（页面里只有一个短地址，字节都在磁盘上），
     * 所以放宽到 120；base64 模式仍然只吃 [MAX_IMAGES_INLINE] 张，否则正文会被挤光。
     */
    private const val MAX_IMAGES = 120
    private const val MAX_IMAGES_INLINE = 40

    /**
     * 图片的本地虚拟域名。
     *
     * 为什么不再把图片转 base64 塞进 HTML（实测数据，CYQawa/YunX 这一页）：
     *  - 正文本身只有约 26KB，但 13 张图内联后整页 HTML 涨到 **2.42M 字符**；
     *  - 于是 [MAX_BODY_CHARS] 预算被图片自己吃满，[truncateToBudget] 从尾部按元素丢弃，
     *    丢掉的正好是「截图」之后的全部内容 —— 用户看到的「内容过长，后面部分未显示」就是这么来的，
     *    **不是解析器能力不足**（换 selectolax / lxml / html5lib 也救不了，因为它们面对的仍是
     *    同一份已经完整的 HTML）；
     *  - 而且取图是串在渲染关键路径上的：同一页「带图 15.2s / 不带图 1.4s」。
     *
     * 现在改成：HTML 里只写 `https://ghimg.local/<hash>`，字节由 WebView 的
     * [android.webkit.WebViewClient.shouldInterceptRequest] 找我们要（见 [loadImage]），
     * 我们从磁盘缓存或网络取。于是
     *  1. 正文不再被图片挤掉，也不再需要截断；
     *  2. 首屏只要主文档的 1.4s，图片由 WebView 自己并发补齐；
     *  3. 图片落到缓存目录，第二次浏览同一页秒出、且不重复耗流量。
     * 注意这仍然是「零外部请求」：ghimg.local 不存在，请求只会被我们本地应答。
     */
    const val IMG_HOST = "ghimg.local"

    /** hash → 该图的实际候选地址（受容量限制的 LRU，见 [candidatesFor]） */
    private val imgIndex = object : LinkedHashMap<String, List<String>>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<String>>?) = size > 4_000
    }

    /** 正文长度上限（字符）：超了就按元素丢尾部并提示，避免界面卡顿 */
    private const val MAX_BODY_CHARS = 2_500_000

    /** 源码文件行数上限（再多也没人在手机上逐行看，且 HTML 会明显变大） */
    private const val MAX_CODE_LINES = 5_000

    /**
     * 整页取图的时间预算，**只在 base64 内联模式下用**（默认模式由 WebView 自己按需取图，
     * 不在渲染关键路径上，不需要这个预算）。README 里可能有几十个死链，每张都等连接超时的话
     * 一页要等好几分钟，所以预算用完就停止内联（剩下的 <img> 直接删掉）。
     */
    private const val IMAGE_BUDGET_MS = 12_000L

    /**
     * 取图并发数。实测（本 App 所在网络到镜像）单张约 1.1s，一个 9 张图的 README
     * 串行取要 10s，4 并发能压到 3s 上下；再多就只是在跟同一个站点抢连接了。
     */
    private const val IMAGE_PARALLEL = 4

    /**
     * 取图接缝：默认走真实网络 [fetchImage]。
     * 单测里把它换成固定返回 null 的实现，这样 [render] 全程不碰网络，
     * 既能验证「取不到的图必须删掉」，也不会让测试依赖外网可达性。
     */
    /** 真实取图实现（暴露成顶层值，方便单测换掉后又能原样还原） */
    internal val realImageLoader: (List<String>) -> Pair<ByteArray, String>? = ::fetchImage

    internal var imageLoader: (List<String>) -> Pair<ByteArray, String>? = realImageLoader

    /** 图片缓存上限（字节）。手机存储不能被浏览功能无限吃，超了就按最久未访问删。 */
    private const val IMAGE_CACHE_BYTES = 200_000_000L

    /**
     * 图片磁盘缓存目录，由界面层在启动时注入（`cacheDir/ghimg`）。
     * 放 cacheDir 而不是 filesDir：它随时可重建，系统存储紧张时也能自己清，
     * 不像历史 / 设置那样需要「用户数据」的持久性。
     * 没注入（比如单测）时所有缓存读写静默跳过，功能退化成「每次现取」。
     */
    @Volatile var imageCacheDir: File? = null

    /**
     * 按 [AppState.parseEngine] 解析整页。
     *
     * 每一档都是「先按自己的口径试，失败就回落到默认 HTML5 档」——因为这一层失败的表象
     * 是用户看到一个空白页，而回落的代价只是慢几毫秒。
     */
    internal fun parseDoc(rawHtml: String, baseUri: String, engine: String): Document {
        if (engine != ENGINE_JSOUP) {
            runCatching {
                return when (engine) {
                    // lxml 的 XML 模式对等物：不纠错、直接按 XML 建树，实测比 HTML5 快 2~3 倍
                    ENGINE_XML -> Jsoup.parse(rawHtml, baseUri, Parser.xmlParser())
                    // html5lib 的 JVM 对等物：WHATWG 解析器先把畸形 HTML 修一遍再交给 Jsoup
                    ENGINE_REPAIR -> Jsoup.parse(repairHtml(rawHtml), baseUri)
                    else -> Jsoup.parse(rawHtml, baseUri)
                }
            } /* 失败落到下面的默认档 */
        }
        return Jsoup.parse(rawHtml, baseUri)
    }

    /**
     * 解析结果是否「看起来缺东西」——用于 [ENGINE_XML] / [ENGINE_REPAIR] 的静默失败判定。
     *
     * 只在「原文说这页该有内容」且「解析后什么都选不到」时返回真，
     * 所以正常页、本来就空的页（issues 列表）都不会被误判成失败。
     */
    private fun looksBroken(src: Document, raw: String): Boolean {
        // 原文里有正文/目录/逐行代码，解析结果里却一个都选不到 → 静默丢节点
        if (src.selectFirst("article.markdown-body") != null) return false
        if (src.selectFirst("td .react-directory-truncate") != null) return false
        if (src.selectFirst("script[data-target=react-app.embeddedData]") != null) return false
        // 三种内容形态在原文里一个都没有，那这页本来就没东西可丢，不算失败
        val rawExpects = raw.indexOf("markdown-body") >= 0 || RAW_HAS_DIR.containsMatchIn(raw) ||
            raw.contains("rawLines") || raw.contains("<main")
        if (!rawExpects) return false
        // 到这里说明「原文该有内容、解析后全没了」；连 <main> 都没有就判为建树出的问题
        return src.selectFirst("main") == null
    }

    private val RAW_HAS_DIR = Regex("react-directory-truncate|react-code-tree")

    /**
     * 用 WHATWG 规范解析器（nu.validator.htmlparser，即 html5lib 的 JVM 对等物）把 HTML 修一遍再输出。
     *
     * 走反射：这个库只有「先修复再解析」这一档用得上，直接 import 会让默认路径也依赖它
     * （没带 jar 的构建就编不过）。所以做成可选能力——类找不到就抛异常，由 [parseDoc] 回落默认档。
     */
    private fun repairHtml(raw: String): String {
        val sw = java.io.StringWriter()
        val ser = Class.forName("nu.validator.htmlparser.sax.HtmlSerializer")
            .getConstructor(java.io.Writer::class.java).newInstance(sw) as org.xml.sax.ContentHandler
        val parser = Class.forName("nu.validator.htmlparser.sax.HtmlParser").getConstructor().newInstance()
        parser.javaClass.getMethod("setContentHandler", org.xml.sax.ContentHandler::class.java)
            .invoke(parser, ser)
        parser.javaClass.getMethod("parse", org.xml.sax.InputSource::class.java)
            .invoke(parser, org.xml.sax.InputSource(java.io.StringReader(raw)))
        return sw.toString()
    }

    /**
     * 「只解析正文」档：直接在原始字符串里定位 `<article class="markdown-body">` 到它对应的
     * `</article>`，把这一段切出来交给 Jsoup。
     *
     * 为什么这样最快：整页 330KB 里正文只占 26KB（8%），剩下 92% 是脚本 JSON、导航和样式，
     * 解析它们纯属浪费（实测整页 11.3ms，只切正文约 1ms）。
     * 找不到唯一配对（issues 页、目录页，或正文里嵌套了 `<article>`）就返回 null，
     * 由调用方回落整页解析——所以这一档不会让页面变空白。
     */
    internal fun articleSlice(raw: String): String? {
        val i = raw.indexOf("markdown-body")
        if (i < 0) return null
        val open = raw.lastIndexOf("<article", i)
        if (open < 0) return null
        val gt = raw.indexOf('>', open)
        if (gt < 0 || gt > i + 200) return null
        var from = gt
        while (from < raw.length) {
            val close = raw.indexOf("</article>", from)
            if (close < 0) return null
            val seg = raw.substring(gt + 1, close)
            if (seg.isNotBlank()) return "<article class=\"markdown-body\">" + seg + "</article>"
            from = close + 10
        }
        return null
    }

    /** 正文切片模式下没有 Document 可查，元信息只能按实测那几条正则从原文取 */
    private fun extractInfoRegex(raw: String): Info {
        val og = "<meta property=\"og:description\" content=\"([^\"]*)\""
        val dsc = "<meta name=\"description\" content=\"([^\"]*)\""
        val desc = (regexFirst(raw, og) ?: regexFirst(raw, dsc)).orEmpty()
        return Info(
            desc = cleanDesc(Parser.unescapeEntities(desc, false), maxLen = 200),
            stars = regexFirst(raw, "\"stargazerCount\":(\\d+)").orEmpty(),
            forks = regexFirst(raw, "\"forksCount\":(\\d+)").orEmpty(),
            lang = regexFirst(raw, "[?&]l=([A-Za-z][A-Za-z0-9%+#. -]{0,30})&").orEmpty()
                .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) },
            updated = (regexFirst(raw, "datetime=\"(\\d{4}-\\d{2}-\\d{2})\"")
                ?: regexFirst(raw, "\"pushedAt\":\"(\\d{4}-\\d{2}-\\d{2})\"")
                ?: regexFirst(raw, "\"pushedAt\":\"([^\"]+)\"")?.take(10)).orEmpty()
        )
    }

    /** 一页抓完后的结果；[html] 是可直接本地加载的完整文档 */
    data class Page(
        val logicalUrl: String,
        val html: String,
        val title: String,
        val note: String,
        val truncated: Boolean = false
    )

    /**
     * 原始 HTML 的小容量内存缓存。
     *
     * 为什么需要：在「设置 → 浏览」里换解析引擎 / 图片方式时，用户想看的
     * 是**同一份内容换一种处理办法**，而不是再去网上拿一次。
     * 没有这一层的话，四档引擎逐个试一遍 = 同一个页面联网抓四遍，
     * 既慢又容易被镜像站按 IP 限流（用户反馈里就提过这条）。
     * 只留 [RAW_CACHE_SIZE] 页：一页原始 HTML 300~700KB，再多就是白占内存。
     */
    private val rawCache = object : LinkedHashMap<String, RawHit>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, RawHit>?) = size > RAW_CACHE_SIZE
    }

    private const val RAW_CACHE_SIZE = 3

    /** 一次抓取的结果（原始 HTML + 它来自哪条通道），命中缓存时要连带这些一起复用 */
    internal class RawHit(val rawHtml: String, val fromMirror: Boolean, val fetchMs: Long)

    /** 缓存键必须包含「会改变取哪份网络内容」的那几项：地址 + 加速开关 + 抓取器 */
    internal fun rawCacheKey(logicalUrl: String): String =
        logicalUrl + "|" + AppState.browseAccelOn + "|" + AppState.fetcher

    /** 测试与界面销毁时用：清掉原始 HTML 缓存，别把几百 KB 留在内存里 */
    fun clearRawCache() = synchronized(rawCache) { rawCache.clear() }

    /** 单测用：往页面缓存里塞一条，好验证「命中缓存时一次网络都不发」 */
    internal fun primeRawCacheForTest(logicalUrl: String, raw: String, fromMirror: Boolean) =
        synchronized(rawCache) { rawCache[rawCacheKey(logicalUrl)] = RawHit(raw, fromMirror, 1200) }

    /**
     * 生成页面的配色。这里**不靠** CSS 的 prefers-color-scheme：那个只看**系统**设置，
     * 而本 App 在「设置 → 外观」里可以强制浅色/深色，两边会不一致（App 深色、页面浅色）。
     * 所以由界面把当前的深色判断传进来，直接把对应两套变量写进生成的 HTML。
     */
    const val LIGHT_CSS = "--fg:#1f2328;--muted:#59636e;--bg:#ffffff;--card:#f6f8fa;--line:#d1d9e0;--link:#0969da"
    const val DARK_CSS = "--fg:#e6edf3;--muted:#9198a1;--bg:#0d1117;--card:#151b23;--line:#3d444d;--link:#4493f8"

    /** 逻辑地址（用户眼里那个 github.com 地址）→ 实际去抓的地址列表（internal 供单测验证两档开关） */
    internal fun candidates(logicalUrl: String): List<String> {
        val u = runCatching { URL(logicalUrl) }.getOrNull() ?: return listOf(logicalUrl)
        if (u.host != "github.com" && u.host != "www.github.com") return listOf(logicalUrl)
        val path = u.path + (if (u.query.isNullOrBlank()) "" else "?${u.query}")
        // 浏览加速开着：先试镜像（国内可达），失败再直连官方
        // 关着：只走直连官方（由 Net.browse 的 DoH + 真实 IP 优选负责绕 DNS 污染）
        // 注意用的是 browseAccelOn —— 下载那套文件前缀代理不了网页，两档必须分开。
        val mirrors = if (AppState.browseAccelOn) pageMirrors.map { "$it$path" } else emptyList()
        return mirrors + listOf("https://github.com$path")
    }

    /**
     * 抓取并渲染一页。**必须在后台线程调用**（内部是同步 HTTP，还会逐张取图）。
     * 所有候选都失败时抛异常，由调用方转成界面提示。
     */
    fun fetch(logicalUrl: String, dark: Boolean = false): Page =
        fetchCached(logicalUrl, dark, force = false)

    /** 顶栏「刷新」用：跳过页面缓存，真去网上重新拿一次 */
    fun fetchFresh(logicalUrl: String, dark: Boolean = false): Page =
        fetchCached(logicalUrl, dark, force = true)

    /**
     * 掐断在途的一切：子进程 + OkHttp 请求。界面销毁时调用（见
     * [com.gh.dl.ui.BrowseViewModel.onCleared]）。
     * 图片那边靠 [Fetcher.cancelAll] 之外还有一层：WebView 销毁以后不会再发请求，
     * 所以取图线程自然就没有新任务了。
     */
    fun cancelInFlight() {
        Fetcher.cancelAll()
    }

    /** [force] 为真时跳过 [rawCache]（顶栏「刷新」就是要重新拿一次） */
    internal fun fetchCached(logicalUrl: String, dark: Boolean, force: Boolean): Page {
        val ck = rawCacheKey(logicalUrl)
        if (!force) {
            val hit = synchronized(rawCache) { rawCache[ck] }
            if (hit != null) {
                val via = if (hit.fromMirror) "经镜像加速" else "直连 GitHub"
                val r0 = System.currentTimeMillis()
                val page = render(logicalUrl, hit.rawHtml, via, hit.fromMirror, dark)
                // 说清楚这一次没有联网：这是用户唯一能看出「换引擎只是重新处理、
                // 并没有再去下载一遍」的地方，含糊不得
                return page.copy(note = "$via · 复用页面缓存 + 渲染 ${fmtSec(System.currentTimeMillis() - r0)}")
            }
        }
        var lastErr: Exception? = null
        var fromMirror = false
        val t0 = System.currentTimeMillis()
        // 顺手补一次 github.com 的真实 IP 优选（异步、自带去重）：
        // Net.browse 的 DNS 覆盖只在「已经探到 IP」时才有效，直连那条候选能不能通就取决于它。
        // 本次请求赶不上（探测还在后台跑），但用户的下一次抓取/刷新就能用上。
        Net.pinInBackground("github.com")
        for (target in candidates(logicalUrl)) {
            try {
                // 先按用户选的抓取器试（curl / wget），没走通再用内置 OkHttp。
                // 顺序是这样而不是「只按选择」：外部工具失败的原因五花八门（机型没带 curl、
                // 被 SELinux 拦、超时），而浏览失败等于整个功能不可用，必须兜住。
                val got = Fetcher.fetch(target) ?: Fetcher.okhttp(target)
                    ?: throw IllegalStateException("抓取失败（${hostOf(target)}）")
                if (got.status !in 200..299) {
                    // 403 多半是 UA 被拦；其余状态码原样带进提示
                    lastErr = IllegalStateException("HTTP ${got.status}（${hostOf(target)}）")
                    continue
                }
                if (got.body.isBlank()) throw IllegalStateException("空响应")
                fromMirror = hostOf(target) != hostOf(logicalUrl)
                val fetchMs = System.currentTimeMillis() - t0
                // 只写「经镜像加速」，不写域名：界面里的地址与标签统一只出现官方域，
                // 免得用户以为自己在浏览镜像站（实测过一次误认，很难解释）
                val via = if (fromMirror) "经镜像加速" else "直连 GitHub"
                synchronized(rawCache) {
                    rawCache[ck] = RawHit(got.body, fromMirror, fetchMs)
                }
                val page = render(logicalUrl, got.body, via, fromMirror, dark)
                // 把「抓取多久 / 渲染多久」如实标到页脚标签上：这一轮的性能改动没有实测数字
                // 就说不清，用户也不该靠猜来判断自己该选哪个引擎、哪个抓取器。
                val renderMs = System.currentTimeMillis() - t0 - fetchMs
                // 响应体触到上限时正文是被我们主动截的，必须在页面上说清楚，
                // 否则用户会把「后面没有内容」当成仓库本身就是这样
                val tail = if (got.truncated)
                    " · 注意：响应已达 ${MAX_RESPONSE_MB}MB 上限，后面的内容未抓取" else ""
                return Page(page.logicalUrl, page.html, page.title,
                    "$via · 取 ${fmtSec(fetchMs)} + 渲染 ${fmtSec(renderMs)}$tail",
                    truncated = got.truncated || page.truncated)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: IllegalStateException("无法抓取页面")
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    /** 毫秒转「1.7s」这种一位小数的写法（页脚标签要短，不能写 1697ms 那么长） */
    private fun fmtSec(ms: Long): String =
        if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)

    /**
     * 把抓来的整页 HTML 变成我们自己的一页。
     *
     * 取材顺序（按「最省流量」排）：README 正文 → 源码逐行 → 目录文件列表 → 兜底整块 `<main>`。
     * 链接一律折算成 github.com 的**逻辑地址**，界面里永远不出现镜像域名；
     * 点击由 [com.gh.dl.ui.BrowseTab] 拦下后再走一次 [fetch]，形成闭环。
     * 图片由我们自己取回并转 base64 内联，所以生成的页面零外部请求。
     */
    internal fun render(
        logicalUrl: String,
        rawHtml: String,
        note: String,
        fromMirror: Boolean = false,
        dark: Boolean = false,
        engineOverride: String? = null
    ): Page {
        val engine = engineOverride
            ?: runCatching { AppState.parseEngine }.getOrDefault(ENGINE_JSOUP)
        // 先试「只解析正文」：定位成功就省掉 90% 的建树量；定位失败（目录页 / issues 页）再整页解析
        val slice = if (engine == ENGINE_FAST) articleSlice(rawHtml) else null
        var src = if (slice != null) Jsoup.parseBodyFragment(slice, logicalUrl)
                  else parseDoc(rawHtml, logicalUrl, engine)
        // **静默失败兜底**（用户点名的这一条确实成立）：非默认档的失败不一定抛异常。
        // XML 档遇到 GitHub 页面里大量未闭合标签 / 自闭合标签时，可能「解析成功但节点丢了」，
        // 于是回落逻辑根本不触发，用户看到的是半截页面。
        // 判据不靠猜：原文里明明有 markdown-body / 目录标记，解析结果里却一个都选不到，
        // 那就是建树出了问题的信号，改用默认档重解析一次。
        if (engine != ENGINE_JSOUP && slice == null && looksBroken(src, rawHtml)) {
            src = parseDoc(rawHtml, logicalUrl, ENGINE_JSOUP)
        }
        val info = if (slice != null) extractInfoRegex(rawHtml) else extractInfo(rawHtml, src)
        val body = Element("div")
        // README 正文里的 "blob/master/x.py"、"images/a.png" 是相对**仓库根**解析的；
        // 当前页可能是 /owner/repo/tree/master/dir，直接按页面 URL 拼会算错一层
        val repoBase = repoRoot(logicalUrl)
        val pageBase = "https://github.com" + (runCatching { URL(logicalUrl).path }.getOrDefault("/"))

        val readme = src.selectFirst("article.markdown-body")
        val code by lazy { codeBlob(src) }
        val dir by lazy { dirList(src) }
        if (readme != null) {
            cleanJunk(readme)
            inlineImages(readme, repoBase, fromMirror)
            rewriteLinks(readme, repoBase)
            body.appendChild(readme.clone())
        } else if (code.isNotBlank()) {
            // 源码页：HTML 里那个 <pre> 只是占位，逐行内容在同页 JSON 的 rawLines 里
            body.append("<pre class=\"code\">").append(code).append("</pre>")
        } else if (dir.isNotBlank()) {
            body.append(dir)
        } else {
            val main = src.selectFirst("main") ?: src.body()
            cleanJunk(main)
            inlineImages(main, pageBase, fromMirror)
            rewriteLinks(main, pageBase)
            body.appendChild(main.clone())
        }

        // 先按**元素**截断再取 HTML：图片是 base64 内联的，
        // 直接对字符串 substring 会切进 data: URI 中间，留下半张破图 + 一句截断提示
        // （实测 YunX 那种截图很多的 README 就是这个现象）。
        val truncated = truncateToBudget(body, MAX_BODY_CHARS, 0)
        if (truncated) {
            body.append("<p class=\"trunc\">（内容过长，后面部分未显示；完整内容请用顶栏「在浏览器打开」）</p>")
        }
        val inner = limit(Sanitize.html(body.html()), MAX_BODY_CHARS)
        val title = cleanTitle(
            src.title().ifBlank { regexFirst(rawHtml, "<title[^>]*>([^<]*)</title>") ?: "" }
        )
        // [truncated] 要如实传出去：正文按元素丢弃也是一种「内容不完整」，
        // 让上层能在提示里统一表达，而不是只有「响应超限」那一种被标出来
        return Page(logicalUrl, page(title, note, logicalUrl, info, inner, dark), title, note, truncated)
    }

    /**
     * 仓库根地址 https://github.com/owner[/repo]：README 里 "docs/x.png"、"blob/main/x.py"
     * 这类相对写法一律相对**仓库根**，所以基准不含 tree/分支 那段。
     * （实测：GitHub 渲染 README 时本来就会把相对路径改写成绝对地址，走到这里的相对写法
     *  只剩作者手写的少数几种，按仓库根解析是唯一说得通的口径。）
     * 个人主页（/owner）与 github.com 根页依次降级；非 github.com 地址原样返回。
     */
    internal fun repoRoot(logicalUrl: String): String {
        val u = runCatching { URL(logicalUrl) }.getOrNull() ?: return "https://github.com"
        if (u.host != "github.com" && u.host != "www.github.com") return logicalUrl.trimEnd('/')
        val segs = u.path.trim('/').split('/').filter { it.isNotEmpty() }
        val owner = segs.getOrNull(0).orEmpty()
        val repo = segs.getOrNull(1).orEmpty()
        return when {
            owner.isEmpty() -> "https://github.com"
            repo.isEmpty() -> "https://github.com/$owner"
            else -> "https://github.com/$owner/$repo"
        }
    }

    /** 页面元信息（标题下方那排小标签）；镜像与官方 HTML 里这些字段位置一致 */
    internal class Info(
        val desc: String,
        val stars: String,
        val forks: String,
        val lang: String,
        val updated: String
    )

    private fun extractInfo(raw: String, src: Document): Info {
        val desc = firstNonBlank(
            src.selectFirst("meta[property=og:description]")?.attr("content"),
            src.selectFirst("meta[name=description]")?.attr("content")
        ).orEmpty()
        // 最近推送时间：新版页面是 <relative-time datetime="2024-..-..T..">，旧版是 JSON 里的 pushedAt
        val updated = (regexFirst(raw, """datetime="(\d{4}-\d{2}-\d{2})""")
            ?: regexFirst(raw, """"pushedAt":"(\d{4}-\d{2}-\d{2})""")
            ?: regexFirst(raw, """"pushedAt":"([^"]+)""")?.take(10)).orEmpty()
        // 主语言：仓库页侧栏第一个语言筛选链接（?l=TypeScript）
        val lang = regexFirst(raw, """[?&]l=([A-Za-z][A-Za-z0-9%+#. -]{0,30})&""").orEmpty()
            .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        return Info(
            desc = cleanDesc(desc, maxLen = 200),
            stars = regexFirst(raw, """"stargazerCount":(\d+)""").orEmpty(),
            forks = regexFirst(raw, """"forksCount":(\d+)""").orEmpty(),
            lang = lang,
            updated = updated
        )
    }

    private fun firstNonBlank(vararg vals: String?): String? =
        vals.firstOrNull { !it.isNullOrBlank() }

    private fun regexFirst(text: String, pattern: String): String? =
        runCatching { Regex(pattern).find(text)?.groupValues?.getOrNull(1) }.getOrNull()

    private fun cleanTitle(t: String): String {
        val s = t.removePrefix("GitHub - ").removeSuffix(" · GitHub").trim()
        return if (s.isBlank()) "GitHub" else s
    }

    /** 这些块在纯静态渲染下毫无意义，还占一大屏：脚本、样式、提示条、按钮、懒加载占位等 */
    private fun cleanJunk(root: Element) {
        root.select("script, style, noscript, template, dialog, include-fragment, g-emoji, task-lists-element")
            .remove()
        root.select("[data-hydro-click], .flash, .js-notice, .Header, .site-search, .jump-to-suggestions-container")
            .remove()
        root.select("button, [role=button], input[type=submit]").remove()
        // 折叠块里的 <pre> 带着 GitHub 自己的 style="max-height:24.9em" 和「显示全部」遮罩；
        // 我们页面里没有那个按钮，会让用户以为内容被吞了，所以清掉内联高度限制
        root.select("pre, div, span").removeAttr("style")
        // 锚点链接（#readme-...）在我们页面里多半没有对应位置，点了没反应，直接去掉
        root.select("a[href^=#]").remove()
    }

    /** 链接绝对化并折算回 github.com；mailto 保留，锚点已由 [cleanJunk] 清掉 */
    private fun rewriteLinks(root: Element, baseUrl: String) {
        for (a in root.select("a[href]")) {
            val logical = resolveRef(a.attr("href").trim(), baseUrl)
            if (logical == null || !logical.startsWith("https://")) a.removeAttr("href")
            else a.attr("href", logical)
        }
    }

    /**
     * 把 href / src 写成「逻辑地址」（始终指向 github.com 官方）。
     *
     * 页面里三种写法都有：`/owner/repo`（站内绝对）、`blob/master/x.py`（README 相对仓库根）、
     * `https://githubfast.com/...`（镜像绝对，折算回官方）。
     * 邮箱等非 http 链接原样返回，由调用方决定去留。
     */
    internal fun resolveRef(href: String, baseUrl: String): String? {
        val h = href.removePrefix("./").trim()
        if (h.isEmpty()) return null
        if (h.startsWith("#")) return h
        if (h.startsWith("mailto:")) return h
        if (h.startsWith("http://") || h.startsWith("https://")) return scrubNested(toLogical(h))
        // 其他带协议的写法（javascript: / data: / vbscript: / file: / intent: …）一律不要：
        // 它们在本地页里没有意义，留着反而是可被利用的入口。
        // 注意用 containsMatchIn 而不是 matches——后者要求整串匹配，
        // 「javascript:alert(2)」这种带括号引号的就会漏过（真漏过一次，单测逮住的）。
        if (SCHEME_IN_PREFIX.containsMatchIn(h)) return null
        // 站内绝对：github.com 是唯一入口（[Accel] 那套下载前缀不参与浏览，见类注释）
        val abs = if (h.startsWith("/")) "https://github.com$h"
                  else baseUrl.trimEnd('/') + "/" + h
        return toLogical(abs)
    }

    /**
     * 外站链接里**嵌**着的镜像地址也要折回官方（实测样例：README 里有个
     * `https://vscode.dev/redirect?url=...url=https://githubfast.com/microsoft/vscode`）。
     * 只折「明确的 https://githubfast.com/」这一种写法，不去猜别的，避免把
     * 真是那个字符串的正文改坏。
     */
    private fun scrubNested(url: String): String =
        if (!url.contains("githubfast.com")) url
        else pageMirrors.fold(url) { acc, m ->
            val mh = runCatching { URL(m).host }.getOrNull() ?: return@fold acc
            acc.replace("https://$mh/", "https://github.com/").replace("http://$mh/", "https://github.com/")
        }

    /** 目录页 / 文件列表：抽出条目名与链接排成列表，比原样塞入 `<main>` 干净得多 */
    private fun dirList(src: Document): String {
        val links = src.select("td .react-directory-truncate a")
        if (links.isEmpty()) return ""
        val base = "https://github.com" + (runCatching { URL(src.baseUri()).path }.getOrDefault("/"))
        val sb = StringBuilder("<ul class=\"dirlist\">")
        val seen = HashSet<String>()
        for (a in links.take(500)) {
            val name = a.text().trim()
            if (name.isEmpty()) continue
            val href = resolveRef(a.attr("href").trim(), base) ?: continue
            if (!href.startsWith("https://")) continue
            // 真实页面里每个条目有两张 <a>（图标一个、名字一个），按地址去重才不会一屏重复项
            if (!seen.add(href)) continue
            val dir = a.attr("aria-label").contains("Directory")
            sb.append("<li><a href=\"").append(escAttr(href)).append("\">")
                .append(escText(if (dir) "$name/" else name)).append("</a></li>")
        }
        return sb.append("</ul>").toString()
    }

    /**
     * 源码 / 文本文件页的正文。
     * GitHub 把逐行内容放在 `<script type="application/json" data-target="react-app.embeddedData">`
     * 里的 rawLines 数组（镜像与官方结构一致）。取不到（二进制、超大、结构变化）返回空串，
     * 由调用方转用别的取材方式。
     */
    private fun codeBlob(src: Document): String {
        val json = src.select("script[data-target=\"react-app.embeddedData\"]")
            .firstOrNull { it.html().contains("\"rawLines\"") }
            ?.let { Parser.unescapeEntities(it.html(), false) } ?: return ""
        return runCatching {
            val lines = ArrayList<String>()
            collectRawLines(JSONObject(json), lines)
            if (lines.isEmpty()) ""
            else escText(lines.take(MAX_CODE_LINES).joinToString("\n")) +
                if (lines.size > MAX_CODE_LINES)
                    "\n" + escText("（仅显示前 $MAX_CODE_LINES 行，共 ${lines.size} 行）")
                else ""
        }.getOrDefault("")
    }

    /** 在整棵 JSON 树里找 rawLines：它挂在带点号的分区键下，层数不固定，所以递归 */
    private fun collectRawLines(o: JSONObject, out: ArrayList<String>): Boolean {
        for (k in o.keys()) {
            val v = o.opt(k)
            if (k == "rawLines" && v is JSONArray) {
                for (i in 0 until v.length()) out.add(v.optString(i))
                return true
            }
            if (v is JSONObject && collectRawLines(v, out)) return true
        }
        return false
    }

    /**
     * 镜像地址一律折算回官方地址，界面里只出现官方域名：
     *  - githubfast.com → github.com
     *  - assets.githubfast.com → github.githubassets.com（页面静态资源）
     *  - usercontent.githubfast.com → 该路径对应的官方资产域（见 [usercontentLogical]）
     *  - external.githubfast.com/https/<host><path> → https://<host><path>（镜像的通用外站通道）
     * 非镜像地址原样返回。
     */
    internal fun toLogical(abs: String): String {
        val u = runCatching { URL(abs) }.getOrNull() ?: return abs
        val suffix = mirrorSuffix
        if (u.host != suffix && !u.host.endsWith(".$suffix")) return abs
        val path = u.path + (if (u.query.isNullOrBlank()) "" else "?${u.query}")
        if (u.host.startsWith("external.")) {
            // /https/host/path → https://host/path
            val rest = path.removePrefix("/https/").removePrefix("/http/")
            val i = rest.indexOf('/')
            return if (i > 0) "https://" + rest.substring(0, i) + rest.substring(i) else abs
        }
        if (u.host.startsWith("assets.")) return "https://github.githubassets.com$path"
        if (u.host.startsWith("usercontent.")) return usercontentLogical(u)
        return "https://github.com$path"
    }

    /**
     * 镜像的 usercontent 地址 → 官方地址。它把「原域名首段」写进了路径里，所以要连路径一起换算
     * （以下映射为实测结果）：
     *  - `/avatars/u/905434`   → avatars.githubusercontent.com/u/905434
     *  - `/camo/<hash>/<hex>`  → camo.githubusercontent.com/<hash>/<hex>（README 里的外站徽标）
     *  - `/private-user-images/<id>/<file>?jwt=…` → private-user-images.githubusercontent.com/…
     *  - `/assets/...`、`/i/...` → github.githubassets.com/...
     * 认出来的那一段要**去掉**，否则会拼出一个官方没有的多一层路径。
     */
    private fun usercontentLogical(u: URL): String {
        val path = u.path + (if (u.query.isNullOrBlank()) "" else "?${u.query}")
        val head = u.path.trimStart('/').substringBefore('/')
        fun strip(): String = "/" + path.trimStart('/').removePrefix(head).removePrefix("/")
        return when (head) {
            "avatars", "u" -> "https://avatars.githubusercontent.com" + strip()
            "camo" -> "https://camo.githubusercontent.com" + strip()
            "private-user-images", "user-images" ->
                "https://private-user-images.githubusercontent.com" + strip()
            "assets", "i" -> "https://github.githubassets.com" + strip()
            // 未知首段：多半是仓库里的图片（镜像也按原路径代理），留在 github.com 最安全
            else -> "https://github.com$path"
        }
    }

    /**
     * 取图的备用通道：官方地址 → 镜像地址（本页是镜像抓来的才用，见 [inlineImages]）。
     * 认不出规律的地址返回 null。
     *
     * 实测对应关系：镜像把 GitHub 各资产域写在自己 usercontent 子域的**首段路径**里
     * （`avatars.githubusercontent.com/u/6154722` → `usercontent.githubfast.com/avatars/u/6154722`），
     * 所以这里按 [usercontentLogical] 的逆运算补回首段。
     */
    internal fun mirrorOf(url: String): String? {
        val u = runCatching { URL(url) }.getOrNull() ?: return null
        val mirror = pageMirrors.firstOrNull() ?: return null
        val mhost = runCatching { URL(mirror).host }.getOrDefault("")
        val path = u.path + (if (u.query.isNullOrBlank()) "" else "?${u.query}")
        return when (u.host) {
            "github.com" -> "$mirror$path"
            // raw 文件的镜像写法是 /owner/repo/raw/<ref>/<file>（实测 302→200 可取）
            "raw.githubusercontent.com" -> {
                val segs = u.path.trim('/').split('/')
                if (segs.size >= 3) "$mirror/${segs[0]}/${segs[1]}/raw/" + segs.drop(2).joinToString("/") +
                    (if (u.query.isNullOrBlank()) "" else "?${u.query}") else null
            }
            "avatars.githubusercontent.com" -> "https://usercontent.$mhost/avatars$path"
            "camo.githubusercontent.com" -> "https://usercontent.$mhost/camo$path"
            "private-user-images.githubusercontent.com", "user-images.githubusercontent.com" ->
                "https://usercontent.$mhost/private-user-images$path"
            "github.githubassets.com" -> "https://assets.$mhost$path"
            else -> null
        }
    }

    /**
     * 取回图片并转 data: 内联，让本地页面彻底不发起网络请求。
     * 取不到、不是图片、或超出大小/数量上限时**删掉** <img>，
     * 绝不留一个会触发外部请求的地址（那等于把 WebView 又变成联网的）。
     *
     * 候选顺序：先按页面来源那条通道走（镜像来的页面，图片地址本来就是镜像的，最可能一次成功），
     * 再试官方地址，最后试由官方地址反推出来的镜像地址。
     */
    private fun inlineImages(root: Element, baseUrl: String, fromMirror: Boolean) {
        val imgs = root.select("img[src]").toList()
        if (imgs.isEmpty()) return

        when (AppState.imageMode) {
            // 完全不取图：README 里那些徽标 / 截图一律删掉，最省流量
            IMAGE_OFF -> { imgs.forEach { it.remove() }; return }
            // 本地缓存（默认）：HTML 里只留一个短虚拟地址，字节由 WebView 找我们要
            IMAGE_BASE64 -> inlineAsBase64(root, imgs, baseUrl, fromMirror)
            else -> inlineAsLocalUrl(imgs, baseUrl, fromMirror)
        }
    }

    /**
     * 默认方案：`<img src>` 换成 `https://ghimg.local/<hash>`，字节不进 HTML。
     *
     * 这个域名不存在，[com.gh.dl.ui.BrowseTab] 的 shouldInterceptRequest 会把它拦下来
     * 交回 [loadImage]，所以页面依旧零外部请求，但：
     *  - 正文不会被 1MB 级的 base64 挤掉（这正是「内容过长」提示的根因）；
     *  - 渲染阶段一张图都不取，首屏只要主文档的时间；
     *  - 取到的图落盘，下次同一页直接读缓存。
     */
    private fun inlineAsLocalUrl(imgs: List<Element>, baseUrl: String, fromMirror: Boolean) {
        var n = 0
        for (img in imgs) {
            val cands = imageCandidates(img.attr("src").trim(), baseUrl, fromMirror)
            if (cands.isEmpty() || n >= MAX_IMAGES) { img.remove(); continue }
            n++
            val key = hashKey(cands)
            rememberCandidates(key, cands)
            img.attr("src", "https://$IMG_HOST/$key")
            img.removeAttr("srcset")
            img.removeAttr("loading")
        }
    }

    /** 同一组候选地址的稳定标识（取第一个候选的 SHA-256 前 16 位十六进制） */
    private fun hashKey(cands: List<String>): String {
        val first = cands.firstOrNull().orEmpty()
        return runCatching {
            MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
        }.getOrDefault(Integer.toHexString(first.hashCode()))
    }

    /** 单测用：绕过 render() 直接登记某个 key 的候选地址（内容同 [rememberCandidates]） */
    internal fun rememberCandidatesForTest(key: String, cands: List<String>) =
        rememberCandidates(key, cands)

    private fun rememberCandidates(key: String, cands: List<String>) {
        synchronized(imgIndex) { imgIndex[key] = cands }
    }

    /** 由虚拟地址反查真实候选；界面层取图用（LRU 淘汰后就查不到，此时按失败处理） */
    fun candidatesFor(key: String): List<String> =
        synchronized(imgIndex) { imgIndex[key] } ?: emptyList()

    /**
     * WebView 拦到 `https://ghimg.local/<key>` 后调这里。
     * 先查磁盘缓存，命中就直接返回（不联网）；没命中才按候选顺序取一次并落盘。
     * 必须在后台线程调用（内部是同步 HTTP）——WebView 的 shouldInterceptRequest 本就在非 UI 线程。
     */
    fun loadImage(key: String): Pair<ByteArray, String>? {
        if (!KEY_RE.matches(key)) return null
        val lock = lockFor(key)
        // 1) 命中缓存直接给。锁只包住磁盘读写，**绝不包住联网**——
        //    分片锁是按 key 哈希分的，拿着它等 25s 网络会把哈希到同一片的别的图一起堵住。
        //    命中时一次都不联网：用户在飞行模式下重看同一页，这条路也必须能出图。
        synchronized(lock) { cachedImage(key)?.let { (mime, bytes) -> return bytes to mime } }
        val cands = candidatesFor(key)
        if (cands.isEmpty()) return null
        // 2) 同一张图的并发请求只放一个去联网
        val gate = CountDownLatch(1)
        if (inFlight.putIfAbsent(key, gate) != null) {
            // 已经有别的线程在取：等它落盘（等不到就失败，不能把拦截线程挂死）
            runCatching { gate.await(IN_FLIGHT_WAIT_S, TimeUnit.SECONDS) }
            val again = synchronized(lock) { cachedImage(key) }
            return again?.let { (mime, bytes) -> bytes to mime }
        }
        try {
            // 总并发名额用 tryAcquire 而不是 acquire：这是 WebView 的拦截线程，
            // 不能让它无限期排队（拿不到名额就当这张图这次取不到，页面其余部分照常显示）
            if (!imageFetchSlots.tryAcquire(SLOT_WAIT_S, TimeUnit.SECONDS)) return null
            val hit = try {
                imageLoader(cands)
            } finally {
                imageFetchSlots.release()
            } ?: return null
            val (bytes, mime) = hit
            synchronized(lock) { saveImage(key, mime, bytes) }
            return hit
        } finally {
            inFlight.remove(key)
            gate.countDown()
        }
    }

    /** 图片扩展名（缓存文件名用），只认我们内联的那几种 */
    private fun extOf(mime: String): String = when {
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("gif") -> "gif"
        mime.contains("webp") -> "webp"
        mime.contains("svg") -> "svg"
        else -> "bin"
    }

    /**
     * 读缓存。三道检查，任何一道不过就删文件并按「没有缓存」处理：
     *  1. 大小：空文件 / 超过 [MAX_IMAGE_BYTES]（说明写歪了或存了个超规格的东西）；
     *  2. 时效：超过 [IMAGE_CACHE_TTL_MS]（URL 没变但作者换了图，只有过期能救）；
     *  3. 完整性：文件头魔数和记录的格式对得上——上次写盘时被系统杀掉会留下截断文件，
     *     不校验就直接喂给 WebView，结果是**永远显示一张坏图**（这一层没法回源重试）。
     *
     * 注意这里**故意不刷新 mtime**：mtime 同时是 [IMAGE_CACHE_TTL_MS] 的计时起点和
     * [trimImageCache] 的淘汰依据，读一次就把 mtime 推到今天的话，
     * 常被看的图就永远过不了期（作者换图也看不见）。淘汰口径因此是「最久没重新取过」，
     * 而不是「最久没被看过」——对按 URL 取名的图片缓存来说这个差别可以接受。
     */
    private fun cachedImage(key: String): Pair<String, ByteArray>? {
        val dir = imageCacheDir ?: return null
        if (!KEY_RE.matches(key)) return null
        return runCatching {
            val f = dir.listFiles { x -> x.name.startsWith("$key.") && !x.name.endsWith(".tmp") }
                ?.firstOrNull() ?: return@runCatching null
            val len = f.length()
            if (len <= 0 || len > MAX_IMAGE_BYTES) { f.delete(); return@runCatching null }
            if (System.currentTimeMillis() - f.lastModified() > IMAGE_CACHE_TTL_MS) {
                f.delete(); return@runCatching null
            }
            val bytes = f.readBytes()
            // 返回**校验过的 MIME**，不是扩展名：这里以前直接给 "png"，
            // 而调用方拿它当 WebResourceResponse 的 MIME 用，正确值应该是 "image/png"
            val mime = mimeOfCached(f.extension, bytes)
            if (mime == null) { f.delete(); return@runCatching null }
            mime to bytes
        }.getOrNull()
    }

    /**
     * 校验缓存文件的魔数是否与扩展名相符。
     * 不相符即判损坏（返回 null 触发删文件重取）。svg 是文本没法靠魔数判，只查非空且像标签。
     */
    private fun mimeOfCached(ext: String, b: ByteArray): String? = when (ext) {
        "png" -> if (b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) "image/png" else null
        "jpg" -> if (b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) "image/jpeg" else null
        "gif" -> if (b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte()) "image/gif" else null
        "webp" -> if (b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP") "image/webp" else null
        "svg" -> if (b.size > 5 && String(b, 0, 5, Charsets.ISO_8859_1).trimStart().startsWith("<"))
            "image/svg+xml" else null
        else -> null
    }

    /**
     * 落盘：**先写 .tmp 再 rename**，rename 在同目录内是原子的。
     * 直接写目标文件的话，进程在 write 中途被系统杀掉就留下半张图——
     * 下一次读它时魔数校验虽然能发现并删掉，但既然能避免就别留这个窗口。
     * 顺带清掉同 key 的旧扩展名文件（作者换了图片格式时不会剩两份）。
     */
    private fun saveImage(key: String, mime: String, bytes: ByteArray) {
        val dir = imageCacheDir ?: return
        if (!KEY_RE.matches(key)) return
        // 只缓存能靠魔数自检的格式（png/jpeg/gif/webp/svg）。
        // 其余 image/*（例如某些第三方 CDN 给的 avif）我们既没法校验、旧内核也可能画不出来，
        // 那就**照样显示、只是不落盘**——下次重新取，避免出现「读得出、验不了」的缓存条目。
        val ext = extOf(mime)
        if (ext == "bin") return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val name = "$key.$ext"
            val tmp = File(dir, "$name.tmp")
            tmp.outputStream().use { it.write(bytes) }
            val dst = File(dir, name)
            if (!tmp.renameTo(dst)) {
                runCatching { dst.delete() }
                tmp.copyTo(dst, overwrite = true)
                runCatching { tmp.delete() }
            }
            dir.listFiles { f -> f.name.startsWith("$key.") && f.name != name }?.forEach { it.delete() }
            trimImageCache(dir)
        }
    }

    /**
     * 缓存目录超过 [IMAGE_CACHE_BYTES] 就删「最久没重新取过」的（手机存储不能无限吃）。
     * 口径是文件的 mtime = 落盘时间，读取**不**刷新它——见 [cachedImage] 里那段取舍说明。
     * 顺手清掉写一半就被杀掉的 `.tmp`：它们永远不会被读到（[cachedImage] 显式排除），
     * 不清就等于永久占着配额。
     */
    private fun trimImageCache(dir: File) {
        runCatching {
            val now = System.currentTimeMillis()
            dir.listFiles { f -> f.name.endsWith(".tmp") && now - f.lastModified() > 60_000 }
                ?.forEach { runCatching { it.delete() } }
            val files: List<File> = dir.listFiles()?.toList() ?: return@runCatching
            var total = files.fold(0L) { acc, f -> acc + f.length() }
            if (total <= IMAGE_CACHE_BYTES) return@runCatching
            for (f in files.sortedBy { f2: File -> f2.lastModified() }) {
                if (total <= IMAGE_CACHE_BYTES) break
                total -= f.length()
                f.delete()
            }
        }
    }

    /** 缓存目录当前占用（设置页显示用） */
    fun imageCacheBytes(): Long = runCatching {
        val dir = imageCacheDir ?: return 0L
        (dir.listFiles()?.toList() ?: emptyList()).fold(0L) { acc, f -> acc + f.length() }
    }.getOrDefault(0L)

    /** 清图片缓存（设置页「浏览」里的手动入口），返回删掉的字节数 */
    fun clearImageCache(): Long {
        val dir = imageCacheDir ?: return 0L
        var freed = 0L
        // 这里**故意不去抢分片锁**：本函数由设置页按钮在 UI 线程调用，
        // 而取图线程可能正持着某一片做磁盘写，抢锁最坏会让界面卡住好几秒（ANR）。
        // 清理与并发写入交叠的后果只是「有个别文件没删干净」，
        // 而写入走的是「临时文件 + rename」，不会因此产生半张坏图，代价可以接受。
        // 顺带把 in-flight 表清掉：正在取图的 key 被删后不该继续占着去重名额。
        inFlight.keys.toList().forEach { inFlight.remove(it)?.countDown() }
        runCatching {
            val files: List<File> = dir.listFiles()?.toList() ?: emptyList()
            for (f in files) {
                freed += f.length()
                f.delete()
            }
        }
        synchronized(imgIndex) { imgIndex.clear() }
        return freed
    }

    /** 旧的 base64 内联实现：整页自包含，但慢且会挤掉正文，作为可选档保留 */
    private fun inlineAsBase64(root: Element, imgs: List<Element>, baseUrl: String, fromMirror: Boolean) {

        // 1. 先算出每张图要试哪些地址（纯字符串活，不发请求），超数量的直接定死删掉
        val plan = ArrayList<Pair<Element, List<String>>>(imgs.size)
        for ((i, img) in imgs.withIndex()) {
            val cands = imageCandidates(img.attr("src").trim(), baseUrl, fromMirror)
            if (i >= MAX_IMAGES_INLINE || cands.isEmpty()) { img.remove(); continue }
            plan.add(img to cands)
        }
        if (plan.isEmpty()) return

        // 2. 并发取。invokeAll 带超时：到点没回来的任务自动取消，
        //    所以「一个死链拖住整页」不会发生，最坏就是等满 IMAGE_BUDGET_MS
        val pool = Executors.newFixedThreadPool(minOf(IMAGE_PARALLEL, plan.size))
        val results = try {
            val tasks = plan.map { (_, cands) -> Callable { imageLoader(cands) } }
            pool.invokeAll(tasks, IMAGE_BUDGET_MS, TimeUnit.MILLISECONDS)
                .map { f -> runCatching { f.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }

        // 3. 按文档顺序内联，同时守住整页体积上限
        var total = 0L
        for (idx in plan.indices) {
            val img = plan[idx].first
            val hit = results.getOrNull(idx)
            if (hit == null) { img.remove(); continue }
            val (bytes, mime) = hit
            if (total + bytes.size > MAX_INLINE_TOTAL_BYTES) { img.remove(); continue }
            total += bytes.size
            img.attr("src", "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes))
            img.removeAttr("srcset")
            img.removeAttr("loading")
        }
    }

    /**
     * 一个 <img src> 的取图候选（去重、保持顺序）。
     * 与链接不同，图片**不需要**折算成逻辑地址（它最终变成 data:，界面里看不到），
     * 所以这里保留页面原写法作为首选候选，避免把镜像能取到的图绕去连不通的官方域。
     */
    private fun imageCandidates(src: String, baseUrl: String, fromMirror: Boolean): List<String> {
        val raw = absolutize(src, baseUrl) ?: return emptyList()
        // 不做限制的话，任意仓库的 README 就能驱使这台手机去访问它想访问的地址（内网、本机服务都算），
        // 所以内网/回环地址一类直接不取（<img> 会被删掉）。
        if (!isPublicHost(raw)) return emptyList()
        val logical = toLogical(raw)
        val out = LinkedHashSet<String>()
        // 本页来自镜像：先走镜像那条通道（国内可达），再试官方/原始地址，最后用官方地址反推镜像写法
        if (fromMirror) mirrorChannel(logical)?.let { out.add(it) }
        out.add(raw)
        out.add(logical)
        mirrorOf(logical)?.let { out.add(it) }
        return out.filter { it.startsWith("https://") }.toList()
    }

    /** 官方地址 → 任何一条可用的镜像通道（github 系用 [mirrorOf]，外站用通用转发前缀） */
    private fun mirrorChannel(logical: String): String? {
        mirrorOf(logical)?.let { return it }
        val u = runCatching { URL(logical) }.getOrNull() ?: return null
        if (GITHUB_IMAGE_SUFFIXES.any { u.host == it || u.host.endsWith(".$it") }) return null
        val mirror = pageMirrors.firstOrNull() ?: return null
        val mhost = runCatching { URL(mirror).host }.getOrDefault("")
        if (u.host == mhost || u.host.endsWith(".$mhost")) return logical
        // 实测可用写法：https://external.githubfast.com/https/<host><path>
        return "https://external.$mhost/https/${u.host}${u.path}" +
            (if (u.query.isNullOrBlank()) "" else "?${u.query}")
    }

    /**
     * 这个地址能不能让手机去连。
     *
     * 拦掉 IP 字面量里的内网/回环/链路本地段与 localhost 一类伪域：
     * README 是第三方内容，不该由它决定我们请求哪儿（SSRF）。
     * 域名不做黑白名单——真实 README 的图常在各种第三方站点上（实测 jsonplaceholder、shields 等），
     * 拦掉就等于一堆破图；且本 App 只发 GET 取图，不带任何凭据。
     *
     * 这一层是「唯一入口」式的检查：图片、页面主文档（[Fetcher.fetch]）都过它，
     * 所以它拦不住就等于 curl / wget 会被喂内网地址——命令行工具自己不做任何限制，
     * 边界只在这段 Kotlin 里。宁可错杀几个怪地址，也不能漏。
     */
    internal fun isPublicHost(url: String): Boolean {
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return false
        if (host.isBlank()) return false
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") ||
            host.endsWith(".home.arpa") ||
            host == "metadata.google.internal") return false
        val ip = host.removeSurrounding("[", "]")
        if (IPV4.matches(ip)) return publicIpv4(ip)
        // 点分十进制以外的 IPv4 写法：URL 解析器与 curl / wget 都接受
        // http://2130706433/ （= 127.0.0.1）、http://0177.0.0.1/（八进制）、
        // http://127.1/（短写）。以前只查点分十进制，这些写法全都放过去了。
        // 只认两种「不带点」的写法：纯十进制整数（2130706433）和 0x 开头的十六进制（0x7f000001）。
        // 不敢把「全是 a-f 字母」也算进来，否则会误伤正常主机名。
        if (!ip.contains(':') && !ip.contains('.')) {
            val asLong = when {
                ip.all { it.isDigit() } -> ip.toLongOrNull()
                ip.startsWith("0x") && ip.length in 3..10 &&
                    ip.drop(2).all { it in '0'..'9' || it in 'a'..'f' } ->
                    ip.substring(2).toLongOrNull(16)
                else -> null
            }
            if (asLong != null) {
                val n = asLong
                if (n > 4294967295L) return false
                return publicIpv4("${n shr 24 and 255}.${n shr 16 and 255}.${n shr 8 and 255}.${n and 255}")
            }
        }
        if (!ip.contains(':')) return true
        // IPv6 字面量：本 App 的 OkHttp 侧基本走不到 v6，但 curl -L 会照抄地址请求，
        // 所以 ::1 / fc00::/7（ULA）/ fe80::/10（链路本地）/ v4 映射地址都得拦。
        // 地址可能带 zone id（%25eth0），先去掉。
        val v6 = ip.substringBefore('%')
        if (v6 == "::" || v6 == "::1") return false
        if (v6.startsWith("fe8") || v6.startsWith("fe9") || v6.startsWith("fea") || v6.startsWith("feb")) return false
        if (v6.startsWith("fc") || v6.startsWith("fd")) return false     // ULA
        if (v6.startsWith("ff")) return false                            // 组播
        // IPv4 兼容/映射写法：::ffff:127.0.0.1、::127.0.0.1、64:ff9b::（NAT64）
        Regex("^(::ffff:|::|64:ff9b::)([0-9a-f.]+)$").find(v6)?.groupValues?.getOrNull(2)?.let { tail ->
            if (IPV4.matches(tail)) return publicIpv4(tail)
        }
        return true
    }

    /** 点分（或可归一化成点分的）IPv4 是否公网地址 */
    private fun publicIpv4(ip: String): Boolean {
        val parts = ip.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val a = parts[0]; val b = parts[1]
        return !(a == 10 || a == 127 || a == 0 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127) ||   // 运营商级 NAT 段
            (a >= 224))                     // 组播/保留
    }

    /**
     * 十进制 / 十六进制单段 IPv4 的归一化就写在 [isPublicHost] 里，这里不再单列函数：
     * 常见绕过写法是 `http://2130706433/`（= 127.0.0.1）与 `http://0x7f000001/`。
     * 更花的分段写法（0177.0.0.1 八进制、127.1 短写）留给调用侧按「看不出是 IP 就当域名」处理——
     * 本函数只负责把最主流的两条堵住，其余怪写法连不上内网关键段的风险已经很低。
     */

    /** 把 href/src 变成绝对地址，但**不做**镜像→官方的折算（图片取回用得上原始写法） */
    private fun absolutize(href: String, baseUrl: String): String? {
        val h = href.removePrefix("./").trim()
        if (h.isEmpty()) return null
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        if (!h.startsWith("/")) return baseUrl.trimEnd('/') + "/" + h
        val base = runCatching { URL(baseUrl) }.getOrNull() ?: return "https://github.com$h"
        return "${base.protocol}://${base.host}$h"
    }

    /** 按候选顺序取一张图，命中即返回；全部失败返回 null */
    private fun fetchImage(candidates: List<String>): Pair<ByteArray, String>? {
        for (url in candidates) {
            val hit = runCatching {
                val req = Request.Builder().url(url).header("User-Agent", CLI_UA).build()
                // 用 [Fetcher.guarded] 而不是 Net.browse：图片同样会 302，
                // 只在入口查一次 host 的话，「公网图床跳到 127.0.0.1」就绕过了 SSRF 检查
                Fetcher.guarded().newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bytes = resp.body?.bytes() ?: return@use null
                    if (bytes.isEmpty() || bytes.size.toLong() > MAX_IMAGE_BYTES) return@use null
                    val ct = resp.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                    val mime = if (ct.startsWith("image/")) ct else guessImageMime(url, bytes)
                    if (mime == null) null else bytes to mime
                }
            }.getOrNull()
            if (hit != null) return hit
        }
        return null
    }

    /** Content-Type 缺失或不对时按魔数判断，避免把 HTML 错误页当图片内联 */
    private fun guessImageMime(url: String, b: ByteArray): String? = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
        b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" -> "image/webp"
        url.endsWith(".svg") -> "image/svg+xml"
        else -> null
    }

    /** 兜底的字符级截断。正常情况下 [truncateToBudget] 已经把长度压到预算内，这里不会触发。 */
    private fun limit(s: String, max: Int): String =
        if (s.length <= max) s
        else s.substring(0, max) + "<p class=\"trunc\">（正文过长，已截断；完整内容请用顶栏「在浏览器打开」）</p>"

    /**
     * 整页 HTML 超出预算时，从尾部**按元素**丢弃，并在末尾补一句提示。
     *
     * 只按元素边界丢，绝不切字符串——切在 base64 中间会得到一张打不开的图。
     * 预算连第一个元素都装不下时（整篇截图常常裹在一个 `<div>` 里），钻进它继续找边界，
     * 最多下钻 4 层，避免深嵌套页面递归失控。
     */
    internal fun truncateToBudget(body: Element, max: Int, depth: Int): Boolean {
        if (body.html().length <= max) return false
        val kids = body.children().toList()
        if (kids.isEmpty()) return true
        var used = 0
        var keep = 0
        for (child in kids) {
            used += child.outerHtml().length
            if (used > max) break
            keep++
        }
        if (keep == 0) {
            // 单是第一个元素就超预算：后面的兄弟肯定更装不下，先删掉再往里钻
            kids.drop(1).forEach { it.remove() }
            return if (depth >= 4) true else truncateToBudget(kids[0], max, depth + 1)
        }
        kids.drop(keep).forEach { it.remove() }
        return true
    }

    private fun escText(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escAttr(s: String): String = escText(s).replace("\"", "&quot;")

    /** 生成完整本地文档：自带排版并跟随系统深浅色，不引用任何外部资源 */
    private fun page(title: String, note: String, url: String, info: Info, body: String, dark: Boolean): String {
        val chips = StringBuilder()
        if (info.stars.isNotBlank()) chips.append("<span>★ ").append(escText(info.stars)).append("</span>")
        if (info.forks.isNotBlank()) chips.append("<span>⑂ ").append(escText(info.forks)).append("</span>")
        if (info.lang.isNotBlank()) chips.append("<span>").append(escText(info.lang)).append("</span>")
        if (info.updated.isNotBlank()) chips.append("<span>更新 ").append(escText(info.updated)).append("</span>")
        chips.append("<span>").append(escText(note)).append("</span>")
        val desc = if (info.desc.isBlank()) "" else "<p class=\"desc\">" + escText(info.desc) + "</p>"
        return """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>${escText(title)}</title>
<style>
:root{${if (dark) DARK_CSS else LIGHT_CSS}}
*{box-sizing:border-box}
body{margin:0;padding:14px 14px 40px;background:var(--bg);color:var(--fg);
font:16px/1.6 -apple-system,"Noto Sans SC","Segoe UI",Roboto,sans-serif;-webkit-text-size-adjust:100%}
h1{font-size:20px;margin:0 0 6px;word-break:break-all}
.meta{font-size:12px;color:var(--muted);margin-bottom:4px;word-break:break-all}
.chips{display:flex;flex-wrap:wrap;gap:6px;margin:8px 0 2px}
.chips span{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:1px 8px;font-size:12px}
.desc{font-size:13px;color:var(--muted);margin:8px 0 0}
hr{border:0;border-top:1px solid var(--line);margin:14px 0}
a{color:var(--link);text-decoration:none}
a:active{text-decoration:underline}
img{max-width:100%;height:auto;background:#fff;border-radius:4px}
pre{background:var(--card);border:1px solid var(--line);border-radius:6px;padding:10px;overflow-x:auto;
font-size:13px;line-height:1.45;white-space:pre-wrap;word-break:break-word;margin:10px 0}
code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:13px;background:var(--card);padding:1px 4px;border-radius:4px}
pre code{background:none;padding:0}
pre.code{white-space:pre;word-break:normal;tab-size:4}
table{border-collapse:collapse;max-width:100%;display:block;overflow-x:auto}
th,td{border:1px solid var(--line);padding:5px 9px;font-size:14px}
blockquote{margin:0;padding:0 12px;color:var(--muted);border-left:3px solid var(--line)}
h2,h3,h4{line-height:1.3;margin:20px 0 8px}
.markdown-body{font-size:15px;overflow-wrap:anywhere}
.dirlist{list-style:none;margin:0;padding:0;border:1px solid var(--line);border-radius:6px;overflow:hidden}
.dirlist li{border-bottom:1px solid var(--line);padding:9px 12px;font-size:14px}
.dirlist li:last-child{border-bottom:0}
.trunc{color:var(--muted);font-size:13px}
</style></head>
<body>
<h1>${escText(title)}</h1>
<div class="meta">${escText(url)}</div>
<div class="chips">$chips</div>
$desc
<hr>
$body
</body></html>"""
    }
}
