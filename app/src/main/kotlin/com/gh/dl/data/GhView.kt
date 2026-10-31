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
 * 「浏览」功能的抓取与本地渲染，WebView 不联网。
 *
 * WebView 不能直接打开网页：
 *  1. 下载加速前缀仅中转文件，处理仓库主页时返回其自身首页（HTTP 200）；
 *  2. 网页镜像站拦截浏览器 UA（403），仅放行 git/curl 类 UA（200，330KB~690KB）。
 *
 * 实现方式：以 CLI UA 抓取 HTML，本地解析后生成页面交 WebView 显示，
 * 页内不含外部资源。
 *
 * 页面结构依据（服务端渲染，无需 JS）：
 *  - README 正文位于 `<article class="markdown-body">`，链接与图片已完成转换；
 *  - 源码文件页的逐行内容位于同份 HTML 的 rawLines JSON；
 *  - 目录页条目位于 `td .react-directory-truncate a`；
 *  - releases / issues / blob / tree / raw 页均可用同一方式抓取。
 */
object GhView {

    /** 命令行特征 UA：镜像站放行 git/curl/wget 类，拦截浏览器类 */
    const val CLI_UA = "curl/8.4.0"

    /**
     * HTML 解析引擎（「设置 → 浏览 → 解析引擎」）。
     *
     * 四档均为 JVM 实现，对应关系：
     *  - [ENGINE_JSOUP]  默认，Jsoup HTML5 解析器（html5lib 建树规则，自带 CSS 选择器）
     *  - [ENGINE_XML]    Jsoup XML 解析器，速度快 2~3 倍；未闭合标签会导致整页失败并回落默认档
     *  - [ENGINE_REPAIR] 先用 nu.validator 的 WHATWG 解析器修复畸形 HTML，再交 Jsoup 建树，容错最强
     *  - [ENGINE_FAST]   先在原文定位 `<article class="markdown-body">` 区间（20~30KB），
     *                    仅解析该区间，处理量最小（整页 330KB 的解析量降至约 1/10）
     * 四档均不对畸形 HTML 抛异常。页面截断与解析器能力无关，成因见 [IMG_HOST]。
     */
    const val ENGINE_JSOUP = "jsoup"
    const val ENGINE_XML = "jsoup-xml"
    const val ENGINE_REPAIR = "repair"
    const val ENGINE_FAST = "fast"
    val ENGINES = listOf(
        ENGINE_JSOUP to "Jsoup HTML5（默认，最稳定）",
        ENGINE_XML to "XML 模式（速度快 2~3 倍，畸形页面自动回落）",
        ENGINE_REPAIR to "先修复再解析（容错最强）",
        ENGINE_FAST to "仅解析正文（处理量最小）"
    )

    /** 图片显示方式（「设置 → 浏览 → 图片」） */
    const val IMAGE_LOCAL = "local"
    const val IMAGE_BASE64 = "base64"
    const val IMAGE_OFF = "off"
    val IMAGE_MODES = listOf(
        IMAGE_LOCAL to "本地缓存（字节按需获取，7 天内复用）",
        IMAGE_BASE64 to "内联至页面（整页自包含，占用正文预算）",
        IMAGE_OFF to "不显示图片（最省流量）"
    )

    /** 内置网页镜像站（用户未自定义时的默认值） */
    const val DEFAULT_PAGE_MIRROR = "https://githubfast.com"

    /**
     * 网页镜像站列表（按顺序尝试），仅包含对 CLI UA 放行且能代理 HTML 页面的站点。
     * 下载加速前缀（[Accel.prefix]）不属于此列表，差异见类注释。
     *
     * 设为可变：此类站点域名更换频繁，允许在「设置 → 加速 → 浏览加速」中自行填写。
     */
    var pageMirrors: List<String> = listOf(DEFAULT_PAGE_MIRROR)
        set(value) {
            field = value.map { it.trim().trimEnd('/') }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
        }

    /** 从任意文本（剪贴板 / 文件 / 多行输入）中提取镜像站地址 */
    fun parseMirrors(text: String): List<String> = text.lines()
        .map { it.trim().trimEnd('/') }
        .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
        .distinct()

    /** 恢复内置镜像站 */
    fun resetMirrors() { pageMirrors = listOf(DEFAULT_PAGE_MIRROR) }

    /** 镜像站连通性探测结果 */
    class MirrorProbe(val ok: Boolean, val ms: Long, val detail: String)

    /**
     * 探测单个镜像站是否可用：fetch 一个真实仓库主页（与浏览功能完全相同的方式：CLI UA + [Net.browse]），
     * 验证返回内容是否为真实页面。
     *
     * 仅检查状态码不足以判定：文件加速前缀对网页同样返回 200，内容为其自身首页，
     * 故额外要求标题不含自身广告词、HTML 含 markdown-body 或仓库标记。
     * [base] 传空串表示探测「直连 GitHub 官方」。
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
                        selfHome -> "返回的是其自身首页（非仓库页）"
                        !looksRepo -> "非仓库页（${body.length / 1024}KB）"
                        else -> "正常 · ${body.length / 1024}KB"
                    }
                )
            }
        }.getOrElse { MirrorProbe(false, System.currentTimeMillis() - t0, it.javaClass.simpleName) }
    }

    /**
     * 由设置页注入用户自定义的镜像站列表；空列表表示「未自定义」，回落至内置默认站。
     * 若不做此处理，用户清空列表后将无任何镜像可用，浏览功能只能直连官方。
     */
    fun applyMirrors(list: List<String>) {
        pageMirrors = if (list.isEmpty()) listOf(DEFAULT_PAGE_MIRROR) else list
    }

    /**
     * 当前生效的镜像域名后缀（页面域 githubfast.com、资产域 usercontent./assets./external.…）。
     * 跟随 [pageMirrors] 变化，用户更换站点后地址需相应折算。
     */
    private val mirrorSuffix: String
        get() = runCatching { URL(pageMirrors.firstOrNull() ?: DEFAULT_PAGE_MIRROR).host }
            .getOrDefault("githubfast.com")

    /** 开头的「scheme:」片段（仅识别合法 scheme 字符，避免将 "a:b" 等误判） */
    private val SCHEME_IN_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /** 点分十进制 IPv4 字面量 */
    private val IPV4 = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

    /** 以 IPv6 字面量书写的 IPv4：映射地址、兼容地址、NAT64 前缀 */
    private val RE_V4_MAPPED = Regex("^(::ffff:|::|64:ff9b::)([0-9a-f.]+)$")

    /** GitHub 官方资产的域名后缀（其余按「外站图」处理，走镜像的通用转发通道） */
    private val GITHUB_IMAGE_SUFFIXES: List<String>
        get() = listOf("github.com", "githubusercontent.com", "githubassets.com", mirrorSuffix)

    /** 单张图片大小上限（超过则不 fetch，避免单张大图耗尽整页配额） */
    private const val MAX_IMAGE_BYTES = 2_000_000L

    /**
     * 图片缓存有效期。缓存文件名仅使用「图片 URL 的 hash」作为 key，**不含内容指纹**，
     * 因此当作者替换同一张图片（URL 不变、内容变化）时，若无过期机制将永远显示旧图。
     * 7 天为折中值：README 配图不会频繁变更，而一次重新 fetch 仅需 1 个请求。
     * 到期后按未命中处理 → 重新 fetch → 覆盖同名文件（见 [loadImage]）。
     */
    private const val IMAGE_CACHE_TTL_MS = 7L * 24 * 3600 * 1000

    /** 与 [Fetcher.MAX_BODY_BYTES] 同一取值标准，仅用于将「响应被截断」转换为用户可读提示 */
    private const val MAX_RESPONSE_MB = 8

    /**
     * 「本地缓存」模式下同时 fetch 图片的最大并发数。
     * WebView 会按视口并发发起一批 ghimg.local 请求，若不加限制，用户快速滑过长 README
     * 可能一次触发十余条请求，易被镜像站按 IP 限流（连主文档都开始返回 403）。
     * 命中缓存不占名额，仅在实际联网时排队。
     */
    private const val IMAGE_FETCH_PARALLEL = 4
    private val imageFetchSlots = Semaphore(IMAGE_FETCH_PARALLEL, true)

    /** 等待其他线程 fetch 同一张图片的时间上限 / 排队获取 fetch 名额的时间上限 */
    private const val IN_FLIGHT_WAIT_S = 8L
    private const val SLOT_WAIT_S = 3L

    /**
     * 同一页面中可能存在多条 [shouldInterceptRequest] 并发调用（不同线程），
     * 而缓存的读、写、按名删除均为多步操作。若不加锁，可能出现
     * 「两个线程各写同一个 key，字节交叉 → 落盘一张损坏的图片」的情况，
     * 而 WebView 不联网，损坏图片无法自动重试（仅能依赖此处自行发现并重 fetch）。
     * 采用固定一组锁按 key 分片：既避免全局锁将并发压成串行，
     * 又保证同一张图片的读写互斥。
     */
    private const val LOCK_STRIPES = 16
    private val imgLocks = Array(LOCK_STRIPES) { Any() }
    private fun lockFor(key: String): Any =
        imgLocks[(key.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES]

    /** 缓存文件名中的 key 必须为纯十六进制短串，否则一律拒绝读写（防止目录穿越） */
    private val KEY_RE = Regex("^[0-9a-f]{8,16}$")

    /**
     * 正在联网 fetch 图片的 key → 完成闩。
     * WebView 对同一张图片可能并发发起两次请求（不同 ROM 内核行为不同），
     * 去重后仅第一个线程实际联网，其余等待其落盘后直接读取缓存，
     * 既不重复消耗流量，也避免两个线程同时写入同一文件。
     */
    private val inFlight = ConcurrentHashMap<String, CountDownLatch>()

    /** base64 内联模式（作为可选档保留）的整页配额 */
    private const val MAX_INLINE_TOTAL_BYTES = 2_000_000L

    /**
     * 单页最多处理多少张图片。
     * 「本地缓存」模式下此数值不再受 HTML 体积约束（页面中仅含一个短地址，字节存储在磁盘），
     * 因此放宽至 120；base64 模式仍限制为 [MAX_IMAGES_INLINE] 张，否则正文空间将被挤占。
     */
    private const val MAX_IMAGES = 120
    private const val MAX_IMAGES_INLINE = 40

    /**
     * 图片的本地虚拟域名。
     *
     * 不再将图片转为 base64 嵌入 HTML 的原因（实测数据，CYQawa/YunX 页面）：
     *  - 正文本身仅约 26KB，但 13 张图片内联后整页 HTML 膨胀至 **2.42M 字符**；
     *  - [MAX_BODY_CHARS] 预算被图片自身耗尽，[truncateToBudget] 从尾部按元素丢弃，
     *    丢弃的内容恰好为「截图」之后的全部 —— 「内容过长，后面部分未显示」即由此产生，
     *    **并非解析器能力不足**（更换 selectolax / lxml / html5lib 也无法解决，因为它们面对的仍是
     *    同一份已完整的 HTML）；
     *  - 取图操作串行于渲染关键路径：同一页面「带图 15.2s / 不带图 1.4s」。
     *
     * 当前实现：HTML 中仅写入 `https://ghimg.local/<hash>`，字节由 WebView 的
     * [android.webkit.WebViewClient.shouldInterceptRequest] 回调获取（见 [loadImage]），
     * 由本地磁盘缓存或网络 fetch。因此：
     *  1. 正文不再被图片挤占，也不再需要截断；
     *  2. 首屏仅需主文档的 1.4s，图片由 WebView 自行并发补齐；
     *  3. 图片落盘缓存，第二次浏览同一页面时直接读取，不重复消耗流量。
     * 注意这仍然是「零外部请求」：ghimg.local 域名不存在，请求仅由本地应答。
     */
    const val IMG_HOST = "ghimg.local"

    /** hash → 该图的实际候选地址（受容量限制的 LRU，见 [candidatesFor]） */
    private val imgIndex = object : LinkedHashMap<String, List<String>>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<String>>?) = size > 4_000
    }

    /** 正文长度上限（字符）：超出则按元素丢弃尾部并提示，避免界面卡顿 */
    private const val MAX_BODY_CHARS = 2_500_000

    /** 源码文件行数上限（手机端逐行阅读意义有限，且 HTML 体积会显著增大） */
    private const val MAX_CODE_LINES = 5_000

    /**
     * 整页取图的时间预算，**仅在 base64 内联模式下使用**（默认模式由 WebView 自行按需取图，
     * 不在渲染关键路径上，无需此预算）。README 中可能存在数十个死链，若每个都等待连接超时，
     * 单页可能耗时数分钟，因此预算耗尽后停止内联（剩余 <img> 直接删除）。
     */
    private const val IMAGE_BUDGET_MS = 12_000L

    /**
     * 取图并发数。实测（本 App 所在网络到镜像）单张约 1.1s，一个 9 张图的 README
     * 串行 fetch 需 10s，4 并发可压缩至 3s 左右；更多并发仅会与同一站点争抢连接。
     */
    private const val IMAGE_PARALLEL = 4

    /**
     * 取图实现注入点：默认使用真实网络 [fetchImage]。
     * 单测中将其替换为固定返回 null 的实现，使 [render] 全程不访问网络，
     * 既可验证「无法获取的图片必须删除」的逻辑，也不依赖外网可达性。
     */
    /** 真实取图实现（暴露为顶层值，便于单测替换后还原） */
    internal val realImageLoader: (List<String>) -> Pair<ByteArray, String>? = ::fetchImage

    internal var imageLoader: (List<String>) -> Pair<ByteArray, String>? = realImageLoader

    /** 图片磁盘缓存上限（字节）。手机存储不应被浏览功能无限占用，超出后按最久未访问删除。 */
    private const val IMAGE_CACHE_BYTES = 200_000_000L

    /**
     * 图片磁盘缓存目录，由界面层在启动时注入（`cacheDir/ghimg`）。
     * 使用 cacheDir 而非 filesDir 的原因：该目录可随时重建，系统存储紧张时也可自行清理，
     * 不像历史 / 设置那样需要「用户数据」级别的持久性。
     * 未注入时（如单测环境）所有缓存读写静默跳过，功能退化为「每次实时 fetch」。
     */
    @Volatile var imageCacheDir: File? = null

    /**
     * 按 [AppState.parseEngine] 解析整页。
     *
     * 每一档均为「先按自身规则尝试，失败则回落至默认 HTML5 档」，因为此层失败的表象
     * 是用户看到空白页，而回落的代价仅为数毫秒延迟。
     */
    internal fun parseDoc(rawHtml: String, baseUri: String, engine: String): Document {
        if (engine != ENGINE_JSOUP) {
            runCatching {
                return when (engine) {
                    // lxml 的 XML 模式对等物：不纠正标签，直接按 XML 建树，实测比 HTML5 快 2~3 倍
                    ENGINE_XML -> Jsoup.parse(rawHtml, baseUri, Parser.xmlParser())
                    // html5lib 的 JVM 对等物：WHATWG 解析器先修正畸形 HTML 再交给 Jsoup
                    ENGINE_REPAIR -> Jsoup.parse(repairHtml(rawHtml), baseUri)
                    else -> Jsoup.parse(rawHtml, baseUri)
                }
            } /* 失败回落至下面的默认档 */
        }
        return Jsoup.parse(rawHtml, baseUri)
    }

    /**
     * 判断解析结果是否「缺失内容」，用于 [ENGINE_XML] / [ENGINE_REPAIR] 的静默失败判定。
     *
     * 仅在「原文预期该页有内容」且「解析后无法选中任何内容」时返回 true，
     * 因此正常页面、本身即为空的页面（如 issues 列表）不会被误判为失败。
     */
    private fun looksBroken(src: Document, raw: String): Boolean {
        // 原文中包含正文/目录/逐行代码，解析结果中却无法选中任何一项 → 静默丢失节点
        if (src.selectFirst("article.markdown-body") != null) return false
        if (src.selectFirst("td .react-directory-truncate") != null) return false
        if (src.selectFirst("script[data-target=react-app.embeddedData]") != null) return false
        // 三种内容形态在原文中均不存在，说明该页本身无内容可丢失，不算失败
        val rawExpects = raw.indexOf("markdown-body") >= 0 || RAW_HAS_DIR.containsMatchIn(raw) ||
            raw.contains("rawLines") || raw.contains("<main")
        if (!rawExpects) return false
        // 至此说明「原文预期有内容、解析后全部丢失」；连 <main> 都不存在则判定为建树问题
        return src.selectFirst("main") == null
    }

    private val RAW_HAS_DIR = Regex("react-directory-truncate|react-code-tree")

    /**
     * 使用 WHATWG 规范解析器（nu.validator.htmlparser，即 html5lib 的 JVM 对等物）修正 HTML 后输出。
     *
     * 通过反射调用：此库仅在「先修正再解析」这一档位使用，直接 import 会导致默认路径也依赖它
     * （未携带 jar 的构建将无法编译）。因此设计为可选能力，类找不到时抛出异常，
     * 由 [parseDoc] 回落至默认档。
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
     * 「仅解析正文」档位：直接在原始字符串中定位 `<article class="markdown-body">` 及其对应的
     * `</article>`，将该段切出后交给 Jsoup。
     *
     * 此方式效率最高的原因：整页 330KB 中正文仅占 26KB（8%），其余 92% 为脚本、JSON、导航与样式，
     * 解析它们纯属浪费（实测整页 11.3ms，仅切正文约 1ms）。
     * 若无法找到唯一配对（issues 页、目录页，或正文中嵌套了 `<article>`）则返回 null，
     * 由调用方回落至整页解析，因此此档位不会导致页面空白。
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

    // 元信息模式：Document 路径与正文切片路径共用，预编译一次
    /** 标题中的「owner/repo」前缀，用于顶栏单行显示 */
    private val RE_TITLE_SLUG = Regex("^[\\w.-]+/[\\w.-]+$")
    private val RE_OG_DESC = Regex("<meta property=\"og:description\" content=\"([^\"]*)\"")
    private val RE_META_DESC = Regex("<meta name=\"description\" content=\"([^\"]*)\"")
    private val RE_TITLE_TAG = Regex("<title[^>]*>([^<]*)</title>")
    private val RE_STARS = Regex("\"stargazerCount\":(\\d+)")
    private val RE_FORKS = Regex("\"forksCount\":(\\d+)")
    private val RE_LANG = Regex("[?&]l=([A-Za-z][A-Za-z0-9%+#. -]{0,30})&")
    private val RE_REL_TIME = Regex("datetime=\"(\\d{4}-\\d{2}-\\d{2})\"")
    private val RE_PUSHED_DAY = Regex("\"pushedAt\":\"(\\d{4}-\\d{2}-\\d{2})\"")
    private val RE_PUSHED_ANY = Regex("\"pushedAt\":\"([^\"]+)\"")

    /** 正文切片模式下无 Document 可查，元信息只能通过正则从原文提取 */
    private fun extractInfoRegex(raw: String): Info {
        val desc = (regexFirst(raw, RE_OG_DESC) ?: regexFirst(raw, RE_META_DESC)).orEmpty()
        return Info(
            desc = cleanDesc(Parser.unescapeEntities(desc, false), maxLen = 200),
            stars = regexFirst(raw, RE_STARS).orEmpty(),
            forks = regexFirst(raw, RE_FORKS).orEmpty(),
            lang = regexFirst(raw, RE_LANG).orEmpty()
                .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) },
            updated = (regexFirst(raw, RE_REL_TIME)
                ?: regexFirst(raw, RE_PUSHED_DAY)
                ?: regexFirst(raw, RE_PUSHED_ANY)?.take(10)).orEmpty()
        )
    }

    /** 一次 fetch 的结果；[html] 为可直接本地加载的完整页面 */
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
     * 存在原因：在「设置 → 浏览」中切换解析引擎 / 图片方式时，用户期望的是
     * **同一份内容换一种处理方式**，而非重新从网络 fetch。
     * 若无此缓存，四档引擎逐个尝试 = 同一页面联网 fetch 四次，
     * 既耗时又易被镜像站按 IP 限流。
     * 仅保留 [RAW_CACHE_SIZE] 页：单页原始 HTML 约 300~700KB，更多则浪费内存。
     */
    private val rawCache = object : LinkedHashMap<String, RawHit>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, RawHit>?) = size > RAW_CACHE_SIZE
    }

    private const val RAW_CACHE_SIZE = 3

    /** 一次 fetch 的结果（原始 HTML 及其来源通道），命中缓存时需连带复用 */
    internal class RawHit(val rawHtml: String, val fromMirror: Boolean, val fetchMs: Long)

    /** 缓存键必须包含「会影响 fetch 哪份网络内容」的字段：地址 + 加速开关 + fetch 器 */
    internal fun rawCacheKey(logicalUrl: String): String =
        logicalUrl + "|" + AppState.browseAccelOn + "|" + AppState.fetcher

    /** 测试与界面销毁时使用：清除原始 HTML 缓存，避免数百 KB 数据滞留内存 */
    fun clearRawCache() = synchronized(rawCache) { rawCache.clear() }

    /** 单测使用：向页面缓存中注入一条记录，验证「命中缓存时不发起任何网络请求」 */
    internal fun primeRawCacheForTest(logicalUrl: String, raw: String, fromMirror: Boolean) =
        synchronized(rawCache) { rawCache[rawCacheKey(logicalUrl)] = RawHit(raw, fromMirror, 1200) }

    /**
     * 生成页面的配色方案。此处**不依赖** CSS 的 prefers-color-scheme：该媒体查询仅检测**系统**设置，
     * 而本 App 在「设置 → 外观」中可强制浅色/深色，两者可能不一致（App 深色、页面浅色）。
     * 因此由界面将当前深色判断传入，直接将对应两套变量写入生成的 HTML。
     */
    const val LIGHT_CSS = "--fg:#1f2328;--muted:#59636e;--bg:#ffffff;--card:#f6f8fa;--line:#d1d9e0;--link:#0969da"
    const val DARK_CSS = "--fg:#e6edf3;--muted:#9198a1;--bg:#0d1117;--card:#151b23;--line:#3d444d;--link:#4493f8"

    /** 逻辑地址（用户所见的 github.com 地址）→ 实际 fetch 地址列表（internal 供单测验证两档开关） */
    internal fun candidates(logicalUrl: String): List<String> {
        val u = runCatching { URL(logicalUrl) }.getOrNull() ?: return listOf(logicalUrl)
        if (u.host != "github.com" && u.host != "www.github.com") return listOf(logicalUrl)
        val path = u.path + (if (u.query.isNullOrBlank()) "" else "?${u.query}")
        // 浏览加速开启：先尝试镜像（国内可达），失败后直连官方
        // 浏览加速关闭：仅直连官方（由 Net.browse 的 DoH + 真实 IP 优选负责绕过 DNS 污染）
        // 注意使用 browseAccelOn，下载加速前缀无法代理网页，两档必须分开，理由见类注释。
        val mirrors = if (AppState.browseAccelOn) pageMirrors.map { "$it$path" } else emptyList()
        return mirrors + listOf("https://github.com$path")
    }

    /**
     * fetch 并渲染一页。**必须在后台线程调用**（内部为同步 HTTP，还会逐张 fetch 图片）。
     * 所有候选地址均失败时抛出异常，由调用方转换为界面提示。
     */
    fun fetch(logicalUrl: String, dark: Boolean = false): Page =
        fetchCached(logicalUrl, dark, force = false)

    /** 顶栏「刷新」使用：跳过页面缓存，重新从网络 fetch */
    fun fetchFresh(logicalUrl: String, dark: Boolean = false): Page =
        fetchCached(logicalUrl, dark, force = true)

    /**
     * 终止所有在途操作：子进程 + OkHttp 请求。界面销毁时调用（见
     * [com.gh.dl.ui.BrowseViewModel.onCleared]）。
     * 图片侧除 [Fetcher.cancelAll] 外还有一层保障：WebView 销毁后不再发起请求，
     * 因此取图线程自然不会再收到新任务。
     */
    fun cancelInFlight() {
        Fetcher.cancelAll()
    }

    /** [force] 为 true 时跳过 [rawCache]（顶栏「刷新」即为重新 fetch） */
    internal fun fetchCached(logicalUrl: String, dark: Boolean, force: Boolean): Page {
        val ck = rawCacheKey(logicalUrl)
        if (!force) {
            val hit = synchronized(rawCache) { rawCache[ck] }
            if (hit != null) {
                val via = if (hit.fromMirror) "经镜像加速" else "直连 GitHub"
                val r0 = System.currentTimeMillis()
                val page = render(logicalUrl, hit.rawHtml, via, hit.fromMirror, dark)
                // 明确标注此次未联网：这是用户唯一能判断「切换引擎仅为重新处理、
                // 并未重新下载」的依据，表述必须清晰
                return page.copy(note = "$via · 复用页面缓存 + 渲染 ${fmtSec(System.currentTimeMillis() - r0)}")
            }
        }
        var lastErr: Exception? = null
        var fromMirror = false
        val t0 = System.currentTimeMillis()
        // 顺便触发一次 github.com 的真实 IP 优选（异步、自带去重）：
        // Net.browse 的 DNS 覆盖仅在「已探测到 IP」时生效，直连候选能否连通取决于此。
        // 本次请求可能无法利用（探测仍在后台进行），但用户的下一次 fetch/刷新即可使用。
        Net.pinInBackground("github.com")
        for (target in candidates(logicalUrl)) {
            try {
                // 先按用户选择的 fetch 器尝试（curl / wget），失败后使用内置 OkHttp。
                // 顺序如此而非「仅按选择」的原因：外部工具失败原因多样（机型未安装 curl、
                // 被 SELinux 拦截、超时），而浏览失败意味着整个功能不可用，必须有后备方案。
                val got = Fetcher.fetch(target) ?: Fetcher.okhttp(target)
                    ?: throw IllegalStateException("fetch 失败（${hostOf(target)}）")
                if (got.status !in 200..299) {
                    // 403 多为 UA 被拦截；其余状态码原样带入提示
                    lastErr = IllegalStateException("HTTP ${got.status}（${hostOf(target)}）")
                    continue
                }
                if (got.body.isBlank()) throw IllegalStateException("空响应")
                fromMirror = hostOf(target) != hostOf(logicalUrl)
                val fetchMs = System.currentTimeMillis() - t0
                // 仅标注「经镜像加速」，不显示域名：界面中的地址与标签统一仅出现官方域名，
                // 避免用户误认为自己在浏览镜像站（曾出现一次误认，解释成本较高）
                val via = if (fromMirror) "经镜像加速" else "直连 GitHub"
                synchronized(rawCache) {
                    rawCache[ck] = RawHit(got.body, fromMirror, fetchMs)
                }
                val page = render(logicalUrl, got.body, via, fromMirror, dark)
                // 将「fetch 耗时 / 渲染耗时」如实标注在页脚标签：性能改动若无实测数据
                // 则无法评估，用户也不应靠猜测判断应选择哪个引擎、哪个 fetch 器。
                val renderMs = System.currentTimeMillis() - t0 - fetchMs
                // 响应体触及上限时正文为主动截断，必须在页面上明确说明，
                // 否则用户会误认为「后续无内容」是仓库本身的状态
                val tail = if (got.truncated)
                    " · 注意：响应已达 ${MAX_RESPONSE_MB}MB 上限，后续内容未 fetch" else ""
                return Page(page.logicalUrl, page.html, page.title,
                    "$via · fetch ${fmtSec(fetchMs)} + 渲染 ${fmtSec(renderMs)}$tail",
                    truncated = got.truncated || page.truncated)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: IllegalStateException("无法 fetch 页面")
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    /** 毫秒转「1.7s」格式的一位小数写法（页脚标签需简短，不能写 1697ms） */
    private fun fmtSec(ms: Long): String =
        if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)

    /**
     * 将 fetch 的整页 HTML 转换为本地渲染页面。
     *
     * 取材顺序（按「最省流量」排列）：README 正文 → 源码逐行 → 目录文件列表 → 回退整块 `<main>`。
     * 链接统一折算为 github.com 的**逻辑地址**，界面中永不出现镜像域名；
     * 点击由 [com.gh.dl.ui.BrowseTab] 拦截后再走一次 [fetch]，形成闭环。
     * 图片由本地 fetch 并转 base64 内联，因此生成的页面零外部请求。
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
        // 先尝试「仅解析正文」：定位成功可省去 90% 的建树量；定位失败（目录页 / issues 页）再整页解析
        val slice = if (engine == ENGINE_FAST) articleSlice(rawHtml) else null
        var src = if (slice != null) Jsoup.parseBodyFragment(slice, logicalUrl)
                  else parseDoc(rawHtml, logicalUrl, engine)
        // **静默失败回落**：非默认档位的失败不一定抛出异常。
        // XML 档位遇到 GitHub 页面中大量未闭合标签 / 自闭合标签时，可能「解析成功但丢失节点」，
        // 此时回落逻辑无法触发，用户看到的是不完整页面。
        // 判据：原文中存在 markdown-body / 目录标记，解析结果中却无法选中任何一项，
        // 即为建树异常的信号，改用默认档重新解析。
        if (engine != ENGINE_JSOUP && slice == null && looksBroken(src, rawHtml)) {
            src = parseDoc(rawHtml, logicalUrl, ENGINE_JSOUP)
        }
        val info = if (slice != null) extractInfoRegex(rawHtml) else extractInfo(rawHtml, src)
        val body = Element("div")
        // README 正文中的 "blob/master/x.py"、"images/a.png" 是相对**仓库根**解析的；
        // 当前页可能为 /owner/repo/tree/master/dir，直接按页面 URL 拼接会多出一层
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
            // 源码页：HTML 中的 <pre> 仅为占位，逐行内容位于同页 JSON 的 rawLines 中
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

        // 先按**元素**截断再取 HTML：图片为 base64 内联，
        // 直接对字符串 substring 会切进 data: URI 中间，留下半张损坏图片与一句截断提示
        // （实测 YunX 等截图较多的 README 即出现此现象）。
        val truncated = truncateToBudget(body, MAX_BODY_CHARS, 0)
        if (truncated) {
            body.append("<p class=\"trunc\">（内容过长，后续部分未显示；完整内容请使用顶栏「在浏览器打开」）</p>")
        }
        val inner = limit(Sanitize.html(body.html()), MAX_BODY_CHARS)
        val title = cleanTitle(
            src.title().ifBlank { regexFirst(rawHtml, RE_TITLE_TAG) ?: "" }
        )
        // [truncated] 需如实传出：正文按元素丢弃也属于「内容不完整」，
        // 使上层能在提示中统一表达，而非仅标注「响应超限」一种情况
        return Page(logicalUrl, page(title, logicalUrl, info, inner, dark), title, note, truncated)
    }

    /**
     * 仓库根地址 https://github.com/owner[/repo]：README 中 "docs/x.png"、"blob/main/x.py"
     * 等相对路径均相对**仓库根**解析，因此基准不含 tree/分支段。
     * （实测：GitHub 渲染 README 时会将相对路径改写为绝对地址，到达此处的相对写法
     *  仅剩作者手写的少数几种，按仓库根解析是唯一合理的标准。）
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

    /** 页面元信息（标题下方标签排）；镜像与官方 HTML 中这些字段位置一致 */
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
        // 最近推送时间：新版页面为 <relative-time datetime="2024-..-..T..">，旧版为 JSON 中的 pushedAt
        val updated = (regexFirst(raw, RE_REL_TIME)
            ?: regexFirst(raw, RE_PUSHED_DAY)
            ?: regexFirst(raw, RE_PUSHED_ANY)?.take(10)).orEmpty()
        // 主语言：仓库页侧栏第一个语言筛选链接（?l=TypeScript）
        val lang = regexFirst(raw, RE_LANG).orEmpty()
            .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        return Info(
            desc = cleanDesc(desc, maxLen = 200),
            stars = regexFirst(raw, RE_STARS).orEmpty(),
            forks = regexFirst(raw, RE_FORKS).orEmpty(),
            lang = lang,
            updated = updated
        )
    }

    private fun firstNonBlank(vararg vals: String?): String? =
        vals.firstOrNull { !it.isNullOrBlank() }

    private fun regexFirst(text: String, re: Regex): String? =
        re.find(text)?.groupValues?.getOrNull(1)

    private fun cleanTitle(t: String): String {
        val s = t.removePrefix("GitHub - ").removeSuffix(" · GitHub").trim()
        if (s.isBlank()) return "GitHub"
        // 形如「owner/repo: 项目描述」：顶栏标题为单行，描述在正文中已有，
        // 保留整串会挤占顶栏并换行截断
        val i = s.indexOf(": ")
        if (i > 0) {
            val head = s.substring(0, i).trim()
            if (head.matches(RE_TITLE_SLUG)) return head
        }
        return s
    }

    /** 以下块在纯静态渲染下无意义且占用大量空间：脚本、样式、提示条、按钮、懒加载占位等 */
    private fun cleanJunk(root: Element) {
        root.select("script, style, noscript, template, dialog, include-fragment, g-emoji, task-lists-element")
            .remove()
        root.select("[data-hydro-click], .flash, .js-notice, .Header, .site-search, .jump-to-suggestions-container")
            .remove()
        root.select("button, [role=button], input[type=submit]").remove()
        // 折叠块中的 <pre> 携带 GitHub 自身的 style="max-height:24.9em" 与「显示全部」遮罩；
        // 本应用页面中无对应按钮，会导致用户误认为内容缺失，因此清除内联高度限制
        root.select("pre, div, span").removeAttr("style")
        // 锚点链接（#readme-...）在本应用页面中多半无对应位置，点击无响应，直接移除
        root.select("a[href^=#]").remove()
    }

    /** 链接绝对化并折算回 github.com；mailto 保留，锚点已由 [cleanJunk] 移除 */
    private fun rewriteLinks(root: Element, baseUrl: String) {
        for (a in root.select("a[href]")) {
            val logical = resolveRef(a.attr("href").trim(), baseUrl)
            if (logical == null || !logical.startsWith("https://")) a.removeAttr("href")
            else a.attr("href", logical)
        }
    }

    /**
     * 将 href / src 写为「逻辑地址」（始终指向 github.com 官方）。
     *
     * 页面中存在三种写法：`/owner/repo`（站内绝对路径）、`blob/master/x.py`（README 相对仓库根）、
     * `https://githubfast.com/...`（镜像绝对路径，折算回官方）。
     * 邮箱等非 http 链接原样返回，由调用方决定去留。
     */
    internal fun resolveRef(href: String, baseUrl: String): String? {
        val h = href.removePrefix("./").trim()
        if (h.isEmpty()) return null
        if (h.startsWith("#")) return h
        if (h.startsWith("mailto:")) return h
        if (h.startsWith("http://") || h.startsWith("https://")) return scrubNested(toLogical(h))
        // 其他带协议的写法（javascript: / data: / vbscript: / file: / intent: …）一律拒绝：
        // 它们在本地页面无意义，保留反而构成可利用入口。
        // 注意使用 containsMatchIn 而非 matches，后者要求整串匹配，
        // 「javascript:alert(2)」等带括号引号的写法会漏过（单测曾捕获此问题）。
        if (SCHEME_IN_PREFIX.containsMatchIn(h)) return null
        // 站内绝对路径：github.com 为唯一入口（[Accel] 的下载加速前缀不参与浏览，理由见类注释）
        val abs = if (h.startsWith("/")) "https://github.com$h"
                  else baseUrl.trimEnd('/') + "/" + h
        return toLogical(abs)
    }

    /**
     * 外站链接中**嵌入**的镜像地址也需折回官方（实测样例：README 中存在
     * `https://vscode.dev/redirect?url=...url=https://githubfast.com/microsoft/vscode`）。
     * 仅折算「明确的 https://githubfast.com/」写法，不做推测，避免将
     * 正文中真实出现的该字符串误改。
     */
    private fun scrubNested(url: String): String =
        if (!url.contains("githubfast.com")) url
        else pageMirrors.fold(url) { acc, m ->
            val mh = runCatching { URL(m).host }.getOrNull() ?: return@fold acc
            acc.replace("https://$mh/", "https://github.com/").replace("http://$mh/", "https://github.com/")
        }

    /** 目录页 / 文件列表：提取条目名与链接排列成列表，比原样塞入 `<main>` 更清晰 */
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
            // 真实页面中每个条目有两张 <a>（图标一张、名字一张），按地址去重避免重复项
            if (!seen.add(href)) continue
            val dir = a.attr("aria-label").contains("Directory")
            sb.append("<li><a href=\"").append(escAttr(href)).append("\">")
                .append(escText(if (dir) "$name/" else name)).append("</a></li>")
        }
        return sb.append("</ul>").toString()
    }

    /**
     * 源码 / 文本文件页的正文。
     * GitHub 将逐行内容置于 `<script type="application/json" data-target="react-app.embeddedData">`
     * 中的 rawLines 数组（镜像与官方结构一致）。无法获取时（二进制、超大、结构变化）返回空串，
     * 由调用方改用其他取材方式。
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

    /** 在整棵 JSON 树中查找 rawLines：其挂载于带点号的分区键下，层数不固定，因此递归 */
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
     * 镜像地址统一折算回官方地址，界面中仅出现官方域名：
     *  - githubfast.com → github.com
     *  - assets.githubfast.com → github.githubassets.com（页面静态资源）
     *  - usercontent.githubfast.com → 该路径对应的官方资产域（见 [usercontentLogical]）
     *  - external.githubfast.com/https/<host><path> → https://<host><path>（镜像的通用外站转发）
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
     * 镜像的 usercontent 地址 → 官方地址。镜像将「原域名首段」写入路径，因此需连路径一起换算
     * （以下映射为实测结果）：
     *  - `/avatars/u/905434`   → avatars.githubusercontent.com/u/905434
     *  - `/camo/<hash>/<hex>`  → camo.githubusercontent.com/<hash>/<hex>（README 中的外站徽标）
     *  - `/private-user-images/<id>/<file>?jwt=…` → private-user-images.githubusercontent.com/…
     *  - `/assets/...`、`/i/...` → github.githubassets.com/...
     * 识别出的首段需**去除**，否则会拼出官方不存在的多余路径层。
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
            // 未知首段：多为仓库内图片（镜像也按原路径代理），保留 github.com 最安全
            else -> "https://github.com$path"
        }
    }

    /**
     * 取图的备用通道：官方地址 → 镜像地址（仅当本页由镜像 fetch 时使用，见 [inlineImages]）。
     * 无法识别规律的地址返回 null。
     *
     * 实测对应关系：镜像将 GitHub 各资产域写入自身 usercontent 子域的**首段路径**
     * （`avatars.githubusercontent.com/u/6154722` → `usercontent.githubfast.com/avatars/u/6154722`），
     * 因此此处按 [usercontentLogical] 的逆运算补回首段。
     */
    internal fun mirrorOf(url: String): String? {
        val u = runCatching { URL(url) }.getOrNull() ?: return null
        val mirror = pageMirrors.firstOrNull() ?: return null
        val mhost = runCatching { URL(mirror).host }.getOrDefault("")
        val path = u.path + (if (u.query.isNullOrBlank()) "" else "?${u.query}")
        return when (u.host) {
            "github.com" -> "$mirror$path"
            // raw 文件的镜像写法为 /owner/repo/raw/<ref>/<file>（实测 302→200 可取）
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
     * fetch 图片并转为 data: 内联，使本地页面完全不发起网络请求。
     * 无法获取、非图片、或超出大小/数量上限时**删除** <img>，
     * 不留任何会触发外部请求的地址（否则等同于使 WebView 恢复联网状态）。
     *
     * 候选顺序：先按页面来源通道尝试（镜像 fetch 的页面，图片地址本即为镜像地址，最可能一次成功），
     * 再尝试官方地址，最后尝试由官方地址反推的镜像地址。
     */
    private fun inlineImages(root: Element, baseUrl: String, fromMirror: Boolean) {
        val imgs = root.select("img[src]").toList()
        if (imgs.isEmpty()) return

        when (AppState.imageMode) {
            // 完全不取图：README 中的徽标 / 截图一律删除，最省流量
            IMAGE_OFF -> { imgs.forEach { it.remove() }; return }
            // 本地缓存（默认）：HTML 中仅保留短虚拟地址，字节由 WebView 回调获取
            IMAGE_BASE64 -> inlineAsBase64(root, imgs, baseUrl, fromMirror)
            else -> inlineAsLocalUrl(imgs, baseUrl, fromMirror)
        }
    }

    /**
     * 默认方案：`<img src>` 替换为 `https://ghimg.local/<hash>`，字节不进入 HTML。
     *
     * 此域名不存在，[com.gh.dl.ui.BrowseTab] 的 shouldInterceptRequest 会将其拦截
     * 交回 [loadImage]，因此页面依旧零外部请求，但：
     *  - 正文不会被 MB 级 base64 挤占（这正是「内容过长」提示的根本原因）；
     *  - 渲染阶段不 fetch 任何图片，首屏仅需主文档耗时；
     *  - 已 fetch 图片落盘缓存，下次访问同一页面直接读取。
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

    /** 单测使用：绕过 render() 直接登记某个 key 的候选地址（内容同 [rememberCandidates]） */
    internal fun rememberCandidatesForTest(key: String, cands: List<String>) =
        rememberCandidates(key, cands)

    private fun rememberCandidates(key: String, cands: List<String>) {
        synchronized(imgIndex) { imgIndex[key] = cands }
    }

    /** 由虚拟地址反查真实候选；界面层取图使用（LRU 淘汰后无法查到，此时按失败处理） */
    fun candidatesFor(key: String): List<String> =
        synchronized(imgIndex) { imgIndex[key] } ?: emptyList()

    /**
     * WebView 拦截到 `https://ghimg.local/<key>` 后调用此方法。
     * 先查询磁盘缓存，命中则直接返回（不联网）；未命中时按候选顺序 fetch 一次并落盘。
     * 必须在后台线程调用（内部为同步 HTTP），WebView 的 shouldInterceptRequest 本即在非 UI 线程。
     */
    fun loadImage(key: String): Pair<ByteArray, String>? {
        if (!KEY_RE.matches(key)) return null
        val lock = lockFor(key)
        // 1) 命中缓存直接返回。锁仅包裹磁盘读写，**绝不包裹联网**，
        //    分片锁按 key 哈希分配，持锁等待 25s 网络会阻塞哈希到同一片的其他图片。
        //    命中时不发起任何网络请求：用户在飞行模式下重看同一页，此路径也必须能显示图片。
        synchronized(lock) { cachedImage(key)?.let { (mime, bytes) -> return bytes to mime } }
        val cands = candidatesFor(key)
        if (cands.isEmpty()) return null
        // 2) 同一张图片的并发请求仅放行一个去联网
        val gate = CountDownLatch(1)
        if (inFlight.putIfAbsent(key, gate) != null) {
            // 已有其他线程在 fetch：等待其落盘（等待超时则失败，不能阻塞拦截线程）
            runCatching { gate.await(IN_FLIGHT_WAIT_S, TimeUnit.SECONDS) }
            val again = synchronized(lock) { cachedImage(key) }
            return again?.let { (mime, bytes) -> bytes to mime }
        }
        try {
            // 总并发名额使用 tryAcquire 而非 acquire：此为 WebView 拦截线程，
            // 不能无限期排队（获取不到名额则视为本次该图片无法获取，页面其余部分正常显示）
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

    /** 图片扩展名（用于缓存文件名），仅识别内联支持的几种格式 */
    private fun extOf(mime: String): String = when {
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("gif") -> "gif"
        mime.contains("webp") -> "webp"
        mime.contains("svg") -> "svg"
        else -> "bin"
    }

    /**
     * 读取缓存。三项检查，任一不通过则删除文件并按「无缓存」处理：
     *  1. 大小：空文件 / 超过 [MAX_IMAGE_BYTES]（说明写入异常或存储了超规格内容）；
     *  2. 时效：超过 [IMAGE_CACHE_TTL_MS]（URL 未变但作者替换了图片，仅有过期机制可解决）；
     *  3. 完整性：文件头魔数与记录格式匹配，上次写盘时被系统终止会留下截断文件，
     *     不校验直接提供给 WebView 会导致**永远显示损坏图片**（此层无法回源重试）。
     *
     * 注意此处**故意不刷新 mtime**：mtime 同时作为 [IMAGE_CACHE_TTL_MS] 的计时起点与
     * [trimImageCache] 的淘汰依据，若每次读取都将 mtime 更新为当前时间，
     * 高频访问的图片将永不过期（作者替换图片也无法感知）。淘汰策略因此为「最久未重新 fetch」，
     * 而非「最久未访问」。对于按 URL 命名的图片缓存，此差异可以接受。
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
            // 返回**校验后的 MIME**，而非扩展名：此处曾直接返回 "png"，
            // 而调用方将其作为 WebResourceResponse 的 MIME 使用，正确值应为 "image/png"
            val mime = mimeOfCached(f.extension, bytes)
            if (mime == null) { f.delete(); return@runCatching null }
            mime to bytes
        }.getOrNull()
    }

    /**
     * 校验缓存文件的魔数是否与扩展名匹配。
     * 不匹配则判定为损坏（返回 null 触发删除文件并重新 fetch）。svg 为文本格式无法通过魔数判断，仅检查非空且包含标签特征。
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
     * 写入磁盘：**先写 .tmp 再 rename**，rename 在同目录内为原子操作。
     * 若直接写目标文件，进程在 write 中途被系统终止将留下半张图片。
     * 下一次读取时魔数校验虽能发现并删除，但应尽量避免此窗口。
     * 同时清理同 key 的旧扩展名文件（作者更换图片格式时不会残留两份）。
     */
    private fun saveImage(key: String, mime: String, bytes: ByteArray) {
        val dir = imageCacheDir ?: return
        if (!KEY_RE.matches(key)) return
        // 仅缓存可通过魔数自检的格式（png/jpeg/gif/webp/svg）。
        // 其余 image/*（例如某些第三方 CDN 提供的 avif）既无法校验，旧内核也可能无法渲染，
        // 因此**照常显示但不落盘**，下次重新 fetch，避免出现「可读但无法校验」的缓存条目。
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
     * 缓存目录超过 [IMAGE_CACHE_BYTES] 后删除「最久未重新 fetch」的文件（手机存储不应被无限占用）。
     * 判定依据为文件的 mtime（落盘时间），读取**不**刷新它，理由见 [cachedImage]。
     * 同时清理写入中途被终止的 `.tmp` 文件：它们永远不会被读取（[cachedImage] 显式排除），
     * 不清理则永久占用配额。
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

    /** 缓存目录当前占用空间（设置页显示使用） */
    fun imageCacheBytes(): Long = runCatching {
        val dir = imageCacheDir ?: return 0L
        (dir.listFiles()?.toList() ?: emptyList()).fold(0L) { acc, f -> acc + f.length() }
    }.getOrDefault(0L)

    /** 清除图片缓存（设置页「浏览」中的手动入口），返回释放的字节数 */
    fun clearImageCache(): Long {
        val dir = imageCacheDir ?: return 0L
        var freed = 0L
        // 此处**故意不获取分片锁**：本函数由设置页按钮在 UI 线程调用，
        // 而取图线程可能正持有某一片锁进行磁盘写入，抢锁最坏情况会导致界面卡死数秒（ANR）。
        // 清理与并发写入交叠的后果仅为「个别文件未删除干净」，
        // 而写入采用「临时文件 + rename」机制，不会因此产生半张损坏图片，代价可接受。
        // 同时清空 in-flight 表：正在 fetch 的 key 被删除后不应继续占用去重名额。
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

    /** 旧版 base64 内联实现：整页自包含，但速度慢且可能挤占正文空间，作为可选档保留 */
    private fun inlineAsBase64(root: Element, imgs: List<Element>, baseUrl: String, fromMirror: Boolean) {

        // 1. 计算每张图需尝试的候选地址（纯字符串处理，不发起请求），超出数量限制的直接删除
        val plan = ArrayList<Pair<Element, List<String>>>(imgs.size)
        for ((i, img) in imgs.withIndex()) {
            val cands = imageCandidates(img.attr("src").trim(), baseUrl, fromMirror)
            if (i >= MAX_IMAGES_INLINE || cands.isEmpty()) { img.remove(); continue }
            plan.add(img to cands)
        }
        if (plan.isEmpty()) return

        // 2. 并发 fetch。invokeAll 带超时：到期未完成的任务自动取消，
        //    因此「单个死链拖慢整页」的情况不会发生，最坏为等待满 IMAGE_BUDGET_MS
        val pool = Executors.newFixedThreadPool(minOf(IMAGE_PARALLEL, plan.size))
        val results = try {
            val tasks = plan.map { (_, cands) -> Callable { imageLoader(cands) } }
            pool.invokeAll(tasks, IMAGE_BUDGET_MS, TimeUnit.MILLISECONDS)
                .map { f -> runCatching { f.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }

        // 3. 按文档顺序内联，同时遵守整页体积上限
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
     * 单个 <img src> 的取图候选列表（去重、保持顺序）。
     * 与链接不同，图片**无需**折算为逻辑地址（最终变为 data:，界面中不可见），
     * 因此此处保留页面原始写法作为首选候选，避免将镜像可获取的图片绕道至无法连通的官方域。
     */
    private fun imageCandidates(src: String, baseUrl: String, fromMirror: Boolean): List<String> {
        val raw = absolutize(src, baseUrl) ?: return emptyList()
        // 若不加限制，任意仓库的 README 即可驱使本机访问其指定地址（内网、本机服务均算），
        // 因此内网/回环地址一律不 fetch（<img>  | 将被删除）。
        if (!isPublicHost(raw)) return emptyList()
        val logical = toLogical(raw)
        val out = LinkedHashSet<String>()
        // 本页来自镜像：先走镜像通道（国内可达），再尝试官方/原始地址，最后用官方地址反推镜像写法
        if (fromMirror) mirrorChannel(logical)?.let { out.add(it) }
        out.add(raw)
        out.add(logical)
        mirrorOf(logical)?.let { out.add(it) }
        return out.filter { it.startsWith("https://") }.toList()
    }

    /** 官方地址 → 任意可用镜像通道（github 系使用 [mirrorOf]，外站使用通用转发前缀） */
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
     * 判断该地址是否允许本机连接。
     *
     * 拦截 IP 字面量中的内网/回环/链路本地段与 localhost 等伪域：
     * README 为第三方内容，不应由其决定请求目标（SSRF 防护）。
     * 域名不做黑白名单，真实 README 的图片常位于各种第三方站点（实测包括 jsonplaceholder、shields 等），
     * 拦截会导致大量图片无法显示；且本应用仅发送 GET 请求获取图片，不携带任何凭据。
     *
     * 此层为「唯一入口」式检查：图片、页面主文档（[Fetcher.fetch]）均经过此检查，
     * 因此若此处拦截失效，curl / wget 将被喂入内网地址，命令行工具自身不做任何限制，
     * 安全边界仅在此段 Kotlin 代码中。宁可误拦截部分异常地址，也不能遗漏。
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
        // 点分十进制以外的 IPv4 写法：URL 解析器与 curl / wget 均接受
        // http://2130706433/（= 127.0.0.1）、http://0177.0.0.1/（八进制）、
        // http://127.1/（简写）。此前仅检查点分十进制，这些写法全部被放行。
        // 仅识别两种「不带点」的写法：纯十进制整数（2130706433）与 0x 开头的十六进制（0x7f000001）。
        // 不能将「全为 a-f 字母」也计入，否则会误伤正常主机名。
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
        // IPv6 字面量：本应用的 OkHttp 侧基本不会走到 v6，但 curl -L 会照抄地址请求，
        // 因此 ::1 / fc00::/7（ULA）/ fe80::/10（链路本地）/ v4 映射地址均需拦截。
        // 地址可能带 zone id（%25eth0），先去除。
        val v6 = ip.substringBefore('%')
        if (v6 == "::" || v6 == "::1") return false
        if (v6.startsWith("fe8") || v6.startsWith("fe9") || v6.startsWith("fea") || v6.startsWith("feb")) return false
        if (v6.startsWith("fc") || v6.startsWith("fd")) return false     // ULA
        if (v6.startsWith("ff")) return false                            // 组播
        // IPv4 兼容/映射写法：::ffff:127.0.0.1、::127.0.0.1、64:ff9b::（NAT64）
        RE_V4_MAPPED.find(v6)?.groupValues?.getOrNull(2)?.let { tail ->
            if (IPV4.matches(tail)) return publicIpv4(tail)
        }
        return true
    }

    /** 点分（或可归一化为点分的）IPv4 是否为公网地址 */
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
     * 十进制 / 十六进制单段 IPv4 的归一化已在 [isPublicHost] 中实现，此处不再单列函数：
     * 常见绕过写法为 `http://2130706433/`（= 127.0.0.1）与 `http://0x7f000001/`。
     * 更复杂的分段写法（0177.0.0.1 八进制、127.1 简写）交由调用侧按「无法识别为 IP 则视为域名」处理，
     * 本函数仅负责拦截主流两条，其余异常写法连不通内网关键段的风险已很低。
     */

    /** 将 href/src 转换为绝对地址，但**不做**镜像→官方折算（图片 fetch 需要原始写法） */
    private fun absolutize(href: String, baseUrl: String): String? {
        val h = href.removePrefix("./").trim()
        if (h.isEmpty()) return null
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        if (!h.startsWith("/")) return baseUrl.trimEnd('/') + "/" + h
        val base = runCatching { URL(baseUrl) }.getOrNull() ?: return "https://github.com$h"
        return "${base.protocol}://${base.host}$h"
    }

    /** 按候选顺序 fetch 单张图片，成功则返回；全部失败返回 null */
    private fun fetchImage(candidates: List<String>): Pair<ByteArray, String>? {
        for (url in candidates) {
            val hit = runCatching {
                val req = Request.Builder().url(url).header("User-Agent", CLI_UA).build()
                // 使用 [Fetcher.guarded] 而非 Net.browse：图片同样会 302，
                // 若仅在入口检查一次 host，「公网图床跳转至 127.0.0.1」即绕过 SSRF 检查
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

    /** Content-Type 缺失或不正确时按魔数判断，避免将 HTML 错误页当作图片内联 */
    private fun guessImageMime(url: String, b: ByteArray): String? = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
        b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" -> "image/webp"
        url.endsWith(".svg") -> "image/svg+xml"
        else -> null
    }

    /** 回退的字符级截断。正常情况下 [truncateToBudget] 已将长度控制在预算内，此处不会触发。 */
    private fun limit(s: String, max: Int): String =
        if (s.length <= max) s
        else s.substring(0, max) + "<p class=\"trunc\">（正文过长，已截断；完整内容请使用顶栏「在浏览器打开」）</p>"

    /**
     * 整页 HTML 超出预算时，从尾部**按元素**丢弃，并在末尾附加提示。
     *
     * 仅按元素边界丢弃，绝不切字符串，切在 base64 中间会产生无法显示的图片。
     * 预算连第一个元素都无法容纳时（整篇截图常包裹在单个 `<div>` 中），进入其内部继续查找边界，
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
            // 单个第一个元素即超预算：后续兄弟节点必然更无法容纳，先删除再向内递归
            kids.drop(1).forEach { it.remove() }
            return if (depth >= 4) true else truncateToBudget(kids[0], max, depth + 1)
        }
        kids.drop(keep).forEach { it.remove() }
        return true
    }

    private fun escText(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escAttr(s: String): String = escText(s).replace("\"", "&quot;")

    /**
     * 生成完整本地文档：自带排版并跟随系统深浅色，不引用任何外部资源。
     * [title] 仅写入文档 <title>；正文顶部不再输出标题行与获取状态（所用通道、耗时），
     * 二者已由应用顶栏显示，重复输出表现为页面上方多出一段悬浮文字。
     */
    private fun page(title: String, url: String, info: Info, body: String, dark: Boolean): String {
        val chips = StringBuilder()
        if (info.stars.isNotBlank()) chips.append("<span>★ ").append(escText(info.stars)).append("</span>")
        if (info.forks.isNotBlank()) chips.append("<span>⑂ ").append(escText(info.forks)).append("</span>")
        if (info.lang.isNotBlank()) chips.append("<span>").append(escText(info.lang)).append("</span>")
        if (info.updated.isNotBlank()) chips.append("<span>更新 ").append(escText(info.updated)).append("</span>")
        val chipRow = if (chips.isEmpty()) "" else "<div class=\"chips\">" + chips + "</div>"
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
$chipRow
$desc
<hr>
$body
</body></html>"""
    }
}
