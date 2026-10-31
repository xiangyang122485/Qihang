package com.gh.dl.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.gh.dl.data.Accel
import com.gh.dl.data.Fetcher
import com.gh.dl.data.GhView
import com.gh.dl.data.Threads
import com.gh.dl.data.Translator

/**
 * 「浏览」Tab 的状态：采用命令行方式抓取页面并在本地渲染，界面中的 WebView 仅显示生成的页面。
 *
 * 不使用 WebView 直接加载网页的原因：
 *  - 下载用的加速前缀仅代理文件，加载仓库主页会返回前缀站点自身的首页（经真机验证）；
 *  - 网页镜像 githubfast.com 仅放行 git/curl 类 UA，浏览器 UA 一律返回 403。
 * 因此本 Tab 的加速开关为 [AppState.browseAccelOn]（浏览加速），与下载加速相互独立。
 * 网络请求全部走 [GhView.fetch]（命令行 UA + [com.gh.dl.data.Net.browse] 的 DoH/真实 IP 优选），
 * WebView 作为纯本地 HTML 显示器使用，不联网、不启用 JS。
 *
 * 线程约定：抓取在后台线程（[fetch]）执行，结果统一通过 [MAIN] 回到主线程后写入 Compose 状态；
 * 仅 [seq] 为跨线程读写的普通 Int，用于丢弃过期响应（详见 [fetch]）。
 */
class BrowseViewModel : ViewModel() {

    companion object {
        private val MAIN = Handler(Looper.getMainLooper())
    }

    /** 当前浏览的地址（github.com 逻辑地址，界面中不显示镜像域名）；空串表示尚未浏览过任何页面 */
    var url by mutableStateOf("")
        private set

    /** 当前要显示的本地页面（已包含自定义 CSS 与内联图片）；null 表示尚无内容 */
    var page by mutableStateOf<GhView.Page?>(null)
        private set

    /** 正在抓取：抓取为一次性阻塞请求，无中间进度百分比，界面使用不确定进度条 */
    var loading by mutableStateOf(false)
        private set

    /**
     * 失败提示（如「HTTP 403（githubfast.com）」）；null 表示无错误。
     * 国内访问 GitHub 最常见的情况为连接失败，留空白页面无任何反馈不利于用户判断，因此显式提供提示。
     */
    var loadError by mutableStateOf<String?>(null)
        private set

    /** 顶栏标题：页面标题过长时由界面负责截断 */
    var pageTitle by mutableStateOf("")
        private set

    // ---- 翻译（顶栏按钮手动触发，或在「设置 → 浏览」开启静默翻译后自动执行） ----
    /** 正在翻译：界面仅显示细进度条，不弹出窗口 */
    var translating by mutableStateOf(false)
        private set

    /** 当前显示的是否为译文；true 时顶栏按钮执行「显示原文」 */
    var translated by mutableStateOf(false)
        private set

    /** 手动翻译失败提示；静默翻译失败不写该字段，保持页面原文且不打扰用户 */
    var translateError by mutableStateOf<String?>(null)
        private set

    /** 翻译前的原始页面：用于「显示原文」回退 */
    private var originalPage: GhView.Page? = null

    /** 用户在本页主动选择「显示原文」后，静默翻译不再对同一地址重复触发 */
    private var autoSkippedUrl = ""

    /** 翻译请求序号：换页 / 取消 / 重新翻译时递增，旧请求返回后不再覆盖界面 */
    @Volatile private var translateSeq = 0

    /**
     * 通知 UI 将 [page] 加载到 WebView 的信号：每次成功抓取递增一次。
     * UI 侧通过比较 [loadedToken] 决定是否需要调用 loadDataWithBaseURL，
     * 避免普通重组导致重复加载，同时保证同一地址再次刷新时能够生效。
     */
    var loadToken by mutableLongStateOf(0L)
        private set
    var loadedToken by mutableLongStateOf(0L)

    /** 后退 / 前进栈（存储逻辑地址）。不使用 WebView 自身的历史记录：WebView 仅加载本地页面，全部跳转由本类拦截处理 */
    private val backStack = ArrayDeque<String>()
    private val forwardStack = ArrayDeque<String>()
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set

    /**
     * 从界面同步的当前抓取选项快照：加速开关决定使用镜像站还是直连，
     * 深色模式决定生成页面的配色。两者均非用户点击「刷新」所能触发，因此由 [syncOptions] 比对检测变化。
     */
    private var optionsSig = ""
    @Volatile private var dark = false

    /**
     * 请求序号：切换页面、刷新、切换加速均会递增该值。后台线程返回结果时，仅序号为最新值的请求
     * 允许写入状态，避免延迟返回的旧页面覆盖新页面（这是本类唯一的跨线程字段）。
     */
    @Volatile private var seq = 0

    /** 是否有页面请求仍在进行中（仅用于 [fetch] 决定是否取消上一次请求） */
    @Volatile private var inFlight = false

    /**
     * 本页重新渲染条件的快照：加速开关、抓取器、解析引擎、图片方式，以及界面传入的深色模式。
     * 四项缺一都会导致更改后效果不可见。
     */
    fun signature(): String = AppState.browseAccelOn.toString() +
        "|" + AppState.parseEngine + "|" + AppState.imageMode + "|" + AppState.fetcher

    /**
     * 翻译当前页面正文，并把译文直接写回原 HTML 的文本节点。
     * 仅处理已抓取的本地页面，不联网获取页面本身；相同文字命中缓存时不再请求。
     * [silent] 为 true 表示由静默翻译触发：失败时不写入提示，保留原文。
     */
    fun translatePage(silent: Boolean = false, budgetMs: Long = 45_000L) {
        val p = originalPage ?: page ?: return
        if (translated || translating) return
        val target = p.logicalUrl
        val my = ++translateSeq
        translating = true
        translateError = null
        Threads.start("翻译页面") {
            val ex = runCatching { Translator.collect(p.html) }
            val nodes = ex.getOrNull()?.nodes.orEmpty()
            if (nodes.isEmpty()) {
                MAIN.post {
                    if (my != translateSeq || target != url) return@post
                    translating = false
                    if (!silent) {
                        translateError = ex.exceptionOrNull()?.message?.take(80)
                            ?: "页面文字以中文为主，无需翻译"
                    }
                }
                return@start
            }
            // 解析 HTML 与写回译文均可能耗时，放在工作线程完成后再回主线程
            val r = runCatching {
                val res = Translator.translate(nodes, ex.getOrNull()?.truncated == true, budgetMs)
                Translator.apply(p.html, res.lines)
            }
            MAIN.post {
                // 期间已切换页面、取消或再次发起翻译：丢弃本次结果
                if (my != translateSeq || target != url) return@post
                translating = false
                r.onSuccess { html ->
                    if (html == p.html) {
                        if (!silent) translateError = "翻译服务未返回可用译文"
                        return@post
                    }
                    originalPage = p
                    page = p.copy(html = html)
                    translated = true
                    loadToken++
                }.onFailure { e ->
                    if (!silent) {
                        translateError = e.message?.take(80) ?: e.javaClass.simpleName
                    }
                }
            }
        }
    }

    /** 顶栏按钮：翻译中则取消，已翻译则回到原文，否则开始翻译 */
    fun toggleTranslation() {
        when {
            translating -> {
                translateSeq++
                translating = false
                translateError = null
            }
            translated -> showOriginal()
            else -> translatePage()
        }
    }

    /** 显示翻译前的原文；本页不再自动翻译，直到用户主动翻译或切换页面 */
    fun showOriginal() {
        val orig = originalPage ?: return
        if (!translated) return
        translateSeq++
        translating = false
        translateError = null
        page = orig
        translated = false
        autoSkippedUrl = orig.logicalUrl
        loadToken++
    }

    /** 界面提示一次后清除，避免重组时重复弹出 */
    fun consumeTranslateError() {
        translateError = null
    }

    /**
     * 界面在组合时同步选项快照，检测到变化后重新渲染本页。
     *
     * 「重新渲染」不一定需要「重新联网」——实际决定是否联网的是 [GhView.rawCacheKey]：
     * 该键仅包含「地址 + 加速开关 + 抓取器」（能够改变网络内容来源的选项）。
     * 因此仅切换解析引擎 / 图片方式 / 深色模式时，此处调用的 [fetch] 会命中页面缓存，
     * 直接使用内存中的原始 HTML 重新渲染，不发起额外的网络请求
     * （四档引擎逐个尝试不应重复抓取同一页面四次，避免浪费网络与触发镜像站限流）。
     * 首次调用仅记录快照不执行操作——避免每次切换到浏览 Tab 时都产生一次无效抓取。
     */
    fun syncOptions(darkMode: Boolean) {
        dark = darkMode
        val sig = signature() + if (darkMode) "|d" else "|l"
        if (optionsSig.isEmpty()) { optionsSig = sig; return }
        if (optionsSig == sig) return
        optionsSig = sig
        if (url.isNotBlank()) fetch(url)
    }

    /** 外部入口（解析器页 / 搜索结果页的「浏览」按钮）打开指定地址并切换到浏览 Tab */
    fun open(target: String) {
        if (target.isBlank()) return
        AppState.tab = AppState.TAB_BROWSE
        if (target == url && page != null) { reload(); return }
        val prev = url
        forwardStack.clear()
        if (prev.isNotBlank() && prev != target) backStack.addLast(prev)
        fetch(target)
    }

    /**
     * 页面内点击链接：GitHub 域名地址在此处切换页面并重新抓取，其余地址交由外部浏览器处理（见 [BrowseTab]）。
     * 当前地址压入后退栈，使顶栏「后退」能够返回上一页。
     */
    fun navigate(target: String) {
        if (target.isBlank() || target == url) return
        val prev = url
        forwardStack.clear()
        if (prev.isNotBlank()) backStack.addLast(prev)
        fetch(target)
    }

    /**
     * 重新抓取当前地址（顶栏「刷新」）。
     * 必须跳过页面缓存：用户点击刷新的目的是获取最新内容，
     * 使用内存中的旧 HTML 重新渲染不符合刷新的语义（[force] 参数传递至 [GhView.fetchFresh]）。
     */
    fun reload() {
        if (url.isBlank()) return
        fetch(url, force = true)
    }

    fun goBack() {
        val prev = backStack.removeLastOrNull() ?: return
        if (url.isNotBlank()) forwardStack.addLast(url)
        fetch(prev)
    }

    /**
 *     ViewModel 销毁（Activity 实际结束时）执行收尾操作：取消在途的子进程与网络请求。
     *
     * 不能仅依赖 [seq] 丢弃结果的原因：seq 仅保证「旧结果不显示」，
     * 但对应线程及其 curl 子进程 / OkHttp 连接仍在运行，最长可持续 25 秒。
     * 用户「打开一个仓库后立即退出应用」的操作会遗留一个后台 curl 进程，
     * 持续占用流量、内存和 CPU，表现为应用未完全退出。
     */
    override fun onCleared() {
        seq++               // 使所有在途回调失效
        translateSeq++
        GhView.cancelInFlight()
        // 页面缓存存储整份原始 HTML（300~700KB × 3 页），Activity 实际结束时无需保留
        GhView.clearRawCache()
        super.onCleared()
    }

    fun goForward() {
        val next = forwardStack.removeLastOrNull() ?: return
        if (url.isNotBlank()) backStack.addLast(url)
        fetch(next)
    }

    /**
     * 后台抓取一页。立即进入「加载中」状态并清除旧错误，成功后统一回到主线程写入状态。
     * 失败时保留上一页内容（不清空），仅在顶部提示条中说明——空白页面比旧页面更不利于用户使用。
     */
    private fun fetch(target: String, force: Boolean = false) {
        // 上一次请求尚未完成即被用户翻页 / 刷新覆盖：仅靠 [seq] 丢弃结果不够——
        // 该请求（连同 curl 子进程或 OkHttp 连接）仍在网络中运行，浪费流量并占用并发名额。
        // 既然结果已不再需要，应立即取消。仅取消页面请求，不影响正在显示的页面图片。
        if (inFlight) Fetcher.cancelAll()
        // 换页后旧翻译结果与旧页面不再有效
        translateSeq++
        translating = false
        translated = false
        translateError = null
        originalPage = null
        autoSkippedUrl = ""
        val my = ++seq
        inFlight = true
        url = target
        loading = true
        loadError = null
        syncNavState()
        Threads.start("页面抓取") {
            val result = runCatching {
                if (force) GhView.fetchFresh(target, dark) else GhView.fetch(target, dark)
            }
            MAIN.post {
                if (my == seq) inFlight = false
                // 过期响应（期间用户已切换页面 / 点击刷新）直接丢弃，避免覆盖新页面
                if (my != seq) return@post
                loading = false
                result.onSuccess { p ->
                    originalPage = p
                    page = p
                    pageTitle = p.title
                    loadToken++
                    // 静默翻译：仅当页面存在非中文正文时触发；失败保持原文，不打断阅读
                    if (AppState.autoTranslate) translatePage(silent = true, budgetMs = 30_000)
                }.onFailure { e ->
                    loadError = e.message?.take(80) ?: e.javaClass.simpleName
                }
            }
        }
    }

    private fun syncNavState() {
        canGoBack = backStack.isNotEmpty()
        canGoForward = forwardStack.isNotEmpty()
    }

    /** 判断地址是否应在应用内继续浏览（github.com 及其子域）；其余地址交由外部浏览器处理 */
    fun isBrowseable(link: String): Boolean {
        val host = runCatching { java.net.URL(link).host }.getOrNull() ?: return false
        return Accel.isWebGithubHost(host) && !host.endsWith(".githubfast.com")
    }
}
