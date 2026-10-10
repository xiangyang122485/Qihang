package com.gh.dl.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.gh.dl.data.Accel
import com.gh.dl.data.Fetcher
import com.gh.dl.data.GhView

/**
 * 「浏览」Tab 的状态：**命令行式抓取 + 本地渲染**，界面里的 WebView 只显示我们生成的页面。
 *
 * 为什么不是「WebView 直接开网页」：
 *  - 下载用的加速前缀只代理文件，喂它仓库主页会返回它自己的首页（真机截图证实）；
 *  - 网页镜像 githubfast.com 只放行 git/curl 类 UA，浏览器 UA 一律 403。
 * 也因此本 Tab 的加速开关是 [AppState.browseAccelOn]（浏览加速），与下载那档独立。
 * 所以网络全部走 [GhView.fetch]（CLI UA + [com.gh.dl.data.Net.browse] 的 DoH/真实 IP 优选），
 * WebView 退化成一个纯本地的 HTML 显示器，不联网、不开 JS。
 *
 * 线程约定：抓取在后台线程（[fetch]），结果统一用 [MAIN] 回主线程后再写 Compose 状态；
 * 只有 [seq] 是跨线程读写的普通 Int，用来丢弃过期响应（见 [fetch]）。
 */
class BrowseViewModel : ViewModel() {

    companion object {
        private val MAIN = Handler(Looper.getMainLooper())
    }

    /** 当前浏览的地址（github.com 逻辑地址，界面里不会出现镜像域名）；空串表示还没浏览过 */
    var url by mutableStateOf("")
        private set

    /** 当前要显示的本地页面（已含自己的 CSS 与内联图片）；null 表示还没有内容 */
    var page by mutableStateOf<GhView.Page?>(null)
        private set

    /** 正在抓取：抓取是一次性阻塞请求，没有中间百分比，所以界面用不确定进度条 */
    var loading by mutableStateOf(false)
        private set

    /**
     * 失败提示（如「HTTP 403（githubfast.com）」）；null 表示没有错误。
     * 国内访问 GitHub 最常见就是连不上，光留白屏用户不知道干什么，所以显式给提示。
     */
    var loadError by mutableStateOf<String?>(null)
        private set

    /** 顶栏标题：页面标题太长，交给界面截断 */
    var pageTitle by mutableStateOf("")
        private set

    /**
     * 「该把 [page] 交给 WebView 了」的信号：每次成功抓取递增一次。
     * UI 侧比较 [loadedToken] 决定要不要 loadDataWithBaseURL，
     * 这样普通重组不会重复加载，同一个地址再点刷新也能生效。
     */
    var loadToken by mutableStateOf(0L)
        private set
    var loadedToken by mutableStateOf(0L)

    /** 后退 / 前进栈（存逻辑地址）。WebView 自己的历史不用：它只加载本地页，跳转全由我们拦 */
    private val backStack = ArrayDeque<String>()
    private val forwardStack = ArrayDeque<String>()
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set

    /**
     * 界面同步过来的「本次抓取该用的选项」快照：加速开关决定走镜像还是直连，
     * 深色决定生成页面的配色。两者都不是用户点「刷新」能触发的，所以由 [syncOptions] 比对。
     */
    private var optionsSig = ""
    @Volatile private var dark = false

    /**
     * 请求序号：切页、刷新、拨闪电都会递增它。后台线程回来时只有序号仍是最新的那个
     * 才允许写状态，否则慢返回的旧页面会把新页面盖掉（这是本类唯一的跨线程字段）。
     */
    @Volatile private var seq = 0

    /** 是否有一条页面请求还在飞（只为 [fetch] 决定要不要先掐掉上一条） */
    @Volatile private var inFlight = false

    /**
     * 本页「该按什么口径重做一次」的快照：加速开关、抓取器、解析引擎、图片方式，加界面传进来的深色。
     * 少了任何一项都会有改了就看不见效果的情况（用户提过：换引擎要能立刻见效）。
     */
    fun signature(): String = AppState.browseAccelOn.toString() +
        "|" + AppState.parseEngine + "|" + AppState.imageMode + "|" + AppState.fetcher

    /**
     * 界面在组合时把选项快照同步进来，变了就重做本页。
     *
     * 「重做」不一定等于「重新联网」——真正决定要不要联网的是 [GhView.rawCacheKey]：
     * 它只含「地址 + 加速开关 + 抓取器」（会改变取哪份网络内容的那几项）。
     * 所以只换解析引擎 / 图片方式 / 深色时，这里走的 [fetch] 会命中页面缓存，
     * 就地用已在内存里的原始 HTML 重渲染一遍，**不发第二个请求**
     * （用户提过这条：四档引擎逐个试一遍等于同一页抓四遍，浪费网络还招镜像站限流）。
     * 首次调用只记录快照不干活——否则每次切到浏览 Tab 都会白抓一次当前页。
     */
    fun syncOptions(darkMode: Boolean) {
        dark = darkMode
        val sig = signature() + if (darkMode) "|d" else "|l"
        if (optionsSig.isEmpty()) { optionsSig = sig; return }
        if (optionsSig == sig) return
        optionsSig = sig
        if (url.isNotBlank()) fetch(url)
    }

    /** 外部（解析器页 / 搜索结果页的「浏览」按钮）打开一个地址并切到浏览 Tab */
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
     * 页面内点击链接：github.com 系的地址在这里换页抓取，其余地址交外部浏览器（见 [BrowseTab]）。
     * 后退栈压入当前地址，这样顶栏的「后退」能回到上一页。
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
     * 必须跳过页面缓存：用户点刷新就是想拿最新内容，
     * 拿内存里的旧 HTML 重新渲染一遍不叫刷新（[force] 一路传到 [GhView.fetchFresh]）。
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
     * ViewModel 销毁（Activity 真的结束）时收尾：掐掉在途的子进程与网络请求。
     *
     * 为什么不能只靠 [seq] 丢弃结果：seq 只保证「旧结果不显示」，
     * 那个线程和它开着的 curl 子进程 / OkHttp 连接**还在跑**，最坏再跑满 25s。
     * 用户「打开一个仓库 → 立刻退出 App」这个动作就会留下一个后台 curl，
     * 流量、内存、CPU 都还占着，看起来就像 App 没退干净。
     */
    override fun onCleared() {
        seq++               // 让任何还在飞的回调用不上号
        GhView.cancelInFlight()
        // 页面缓存存的是整份原始 HTML（300~700KB × 3 页），Activity 真结束时没必要留着
        GhView.clearRawCache()
        super.onCleared()
    }

    fun goForward() {
        val next = forwardStack.removeLastOrNull() ?: return
        if (url.isNotBlank()) backStack.addLast(url)
        fetch(next)
    }

    /**
     * 后台抓取一页。先立刻进「加载中」并清掉旧错误，成功后统一回主线程写状态。
     * 失败时保留上一页内容（不清空），只在顶部提示条说明——白屏比旧页面更难用。
     */
    private fun fetch(target: String, force: Boolean = false) {
        // 上一条还没跑完就被用户翻页 / 刷新顶掉了：光靠 [seq] 丢弃结果不够——
        // 那个请求（连同 curl 子进程或 OkHttp 连接）还在网上跑，白烧流量还占名额。
        // 既然结果注定不用，就现在掐掉。只掐页面请求，不影响正在显示的页面图片。
        if (inFlight) Fetcher.cancelAll()
        val my = ++seq
        inFlight = true
        url = target
        loading = true
        loadError = null
        syncNavState()
        Thread {
            val result = runCatching {
                if (force) GhView.fetchFresh(target, dark) else GhView.fetch(target, dark)
            }
            MAIN.post {
                if (my == seq) inFlight = false
                // 过期响应（期间用户又切了页 / 点了刷新）直接丢，否则会盖掉新页面
                if (my != seq) return@post
                loading = false
                result.onSuccess { p ->
                    page = p
                    pageTitle = p.title
                    loadToken++
                }.onFailure { e ->
                    loadError = e.message?.take(80) ?: e.javaClass.simpleName
                }
            }
        }.start()
    }

    private fun syncNavState() {
        canGoBack = backStack.isNotEmpty()
        canGoForward = forwardStack.isNotEmpty()
    }

    /** 地址是否该在 App 内继续浏览（github.com 及其子域）；其余走外部浏览器 */
    fun isBrowseable(link: String): Boolean {
        val host = runCatching { java.net.URL(link).host }.getOrNull() ?: return false
        return Accel.isWebGithubHost(host) && !host.endsWith(".githubfast.com")
    }
}
