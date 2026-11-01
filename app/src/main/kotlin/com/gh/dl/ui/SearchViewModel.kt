package com.gh.dl.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.gh.dl.data.HttpException
import com.gh.dl.data.Search
import com.gh.dl.data.Settings
import com.gh.dl.data.Threads

/**
 * 搜索页的状态与动作。原 [AppState] 中与搜索相关的字段与 [com.gh.dl.ui.doSearch] 迁到本 ViewModel。
 * 跨页共享的加速开关 / 自建节点由 [settings] 持有（搜索走加速代理触达 GitHub 时需要）。
 */
class SearchViewModel : ViewModel() {

    private companion object {
        /** 从异常文本中提取 HTTP 状态码（OkHttp 的 HttpException 消息形如 "HTTP 404 Not Found"） */
        val HTTP_CODE = Regex("HTTP (\\d+)")
    }

    lateinit var settings: SettingsViewModel

    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<Search.Hit>>(emptyList())
    var searchBusy by mutableStateOf(false)
    var searchMsg by mutableStateOf("")
    var searchSource by mutableStateOf("github")

    /**
     * 「启用 GitHub 索引」开关：是否调用 GitHub Search API（https://api.github.com）。
     *  - 开：搜 GitHub 官方源（走 api.github.com，按 [Accel] 规则 3 直连优先、失败回退前缀）；
     *  - 关：不搜 GitHub，只搜 Gitee / GitLab / Codeberg（取决于选了哪个搜索源 tab）。
     * 与「加速前缀开关」是两个独立的东西：加速关了 GitHub 索引仍能搜（直连 api.github.com）；
     * GitHub 索引关了，加速开关仍影响 Release 文件下载。
     */
    var githubIndexOn by mutableStateOf(true)

    /** 搜索历史关键词（最近优先，最多 10 条），本地持久化 */
    var searchHistory by mutableStateOf<List<String>>(emptyList())

    /** 分页加载更多：当前页（首屏=1）、是否还有下一页、是否正在加载下一页 */
    var searchPage by mutableStateOf(1)
    var searchHasMore by mutableStateOf(false)
    var searchLoadingMore by mutableStateOf(false)

    /** 子请求限流/跳过的灰色提示（如「Gitee 暂时限流，仅显示 GitHub 结果」），与错误文案分开 */
    var searchHint by mutableStateOf("")

    /** 当前 searchMsg 是否为「网络异常」类错误（UI 上可点击重试） */
    var searchIsError by mutableStateOf(false)

    /** 清除搜索历史（内存 + 磁盘） */
    fun clearSearchHistory(ctx: Context) {
        searchHistory = emptyList()
        Settings.saveSearchHistory(ctx, emptyList())
    }

    fun refreshSearch(ctx: Context) {
        if (searchQuery.isBlank()) {
            toast(ctx, "请输入关键词后再刷新")
            return
        }
        doSearch(ctx)
    }

    /** 按关键词搜项目：重置到第 1 页并替换结果。 */
    fun doSearch(ctx: Context) {
        val q = searchQuery.trim()
        if (q.isBlank()) {
            toast(ctx, "请输入关键词，例如 Python、Java")
            return
        }
        // 记录搜索历史：去重后置顶，最多保留 10 条，并落盘
        searchHistory = (listOf(q) + searchHistory.filter { it != q }).take(10)
        Settings.saveSearchHistory(ctx, searchHistory)
        runSearch(ctx, page = 1, append = false)
    }

    /** 分页加载更多：在现有结果后追加下一页（需已有关键词且上一次搜索存在下一页）。 */
    fun loadMoreSearch(ctx: Context) {
        val q = searchQuery.trim()
        if (q.isBlank() || !searchHasMore || searchBusy || searchLoadingMore) return
        runSearch(ctx, page = searchPage + 1, append = true)
    }

    private fun runSearch(ctx: Context, page: Int, append: Boolean) {
        val q = searchQuery.trim()
        if (q.isBlank()) return
        // 选中的优先镜像站（解析器下拉）中文短名；仅用于为 GitHub 官方源结果标注「可走 XX 镜像下载」。
        val mirror = runCatching { settings.parser.domestic }.getOrNull()?.let { Search.mirrorLabel(it) }
        if (append) searchLoadingMore = true else searchBusy = true
        if (!append) {
            searchMsg = "正在搜索「$q」…"
            searchHint = ""
            searchIsError = false
            searchResults = emptyList()
        }
        Threads.start("搜索") {
            // 各分支内部各有 runCatching，但镜像列表解析等步骤仍可能抛出；
            // 外层兜底保证 busy 标志必然复位，避免搜索框长期停留在「正在搜索」。
            val outcome = try {
                when (searchSource) {
                    "foreign" -> searchForeign(q, page)
                    "domestic" -> searchDomestic(q, mirror, page)
                    else -> searchGithubOnly(q, mirror, page)
                }
            } catch (e: Throwable) {
                Outcome(emptyList(), "搜索失败：${e.message ?: e.javaClass.simpleName}", isError = true)
            }
            MAIN.post {
                if (append) searchLoadingMore = false else searchBusy = false
                searchResults = if (append) searchResults + outcome.results else outcome.results
                searchPage = page
                searchHasMore = outcome.hasMore
                searchMsg = outcome.msg
                if (!append) { searchHint = outcome.hint; searchIsError = outcome.isError }
            }
        }
    }

    /** 单个源的搜索结果 + 展示文案（区分「有结果 / 全部成功但空 / 部分或全部失败」三种状态） */
    private data class Outcome(
        val results: List<Search.Hit>,
        val msg: String,
        val hint: String = "",
        val isError: Boolean = false,
        val hasMore: Boolean = false
    )

    /** 给 GitHub 官方源结果打上优先镜像站标签（仅 github 源；镜像站只代理 Release，不代理搜索） */
    private fun tagMirror(h: Search.Hit, mirror: String?): Search.Hit =
        if (mirror != null && h.source == "github") h.copy(mirrorLabel = mirror) else h

    /** GitHub 源（或国内源里的 GitHub 索引部分）：直连 api.github.com，失败回退前缀已由 [com.gh.dl.data.Accel] 处理 */
    private fun searchGithubOnly(q: String, mirror: String?, page: Int): Outcome {
        if (!githubIndexOn) return Outcome(emptyList(), "未找到与「$q」相关的仓库")
        return try {
            val (hits, more) = Search.github(q, page)
            val tagged = hits.map { tagMirror(it, mirror) }
            val msg = if (tagged.isEmpty()) "未找到与「$q」相关的仓库"
                      else "已加载 ${searchResults.size + tagged.size} 个仓库" + if (more) "，可能还有更多" else "（已到末尾）"
            Outcome(tagged, msg, hasMore = more)
        } catch (e: Exception) {
            Log.e("Search", "github search failed; q=$q", e)
            Outcome(emptyList(), "GitHub API 暂时无响应，点此重试", isError = true)
        }
    }

    /** 外国源：GitLab + Codeberg 分页；某一后端失败时静默跳过，不影响另一个。 */
    private fun searchForeign(q: String, page: Int): Outcome {
        var glFailed = false
        var cbFailed = false
        val (gl, glMore) = runCatching { Search.gitlab(q, page) }.getOrElse { glFailed = true; emptyList<Search.Hit>() to false }
        val (cb, cbMore) = runCatching { Search.codeberg(q, page) }.getOrElse { cbFailed = true; emptyList<Search.Hit>() to false }
        val all = gl + cb
        val failed = listOfNotNull(
            if (glFailed) "GitLab" else null,
            if (cbFailed) "Codeberg" else null
        )
        return when {
            all.isNotEmpty() -> Outcome(
                results = all,
                msg = "已加载 ${searchResults.size + all.size} 个仓库",
                hasMore = glMore || cbMore
            )
            failed.isNotEmpty() ->
                Outcome(emptyList(), "网络异常，${failed.joinToString("、")}请求失败，点此重试", isError = true)
            else -> Outcome(emptyList(), "未找到与「$q」相关的仓库")
        }
    }

    /** 国内源：GitHub 索引 + Gitee + GitCode 三个后端各自独立，一个失败不影响其它 */
    private fun searchDomestic(q: String, mirror: String?, page: Int): Outcome {
        var ghFailed = false; var ghCode: Int? = null
        var geFailed = false; var geCode: Int? = null
        var gcFailed = false
        var geRate = false
        var ghMore = false; var geMore = false; var gcMore = false

        // a. GitHub 官方索引（高校镜像不提供搜索 API，复用官方索引）
        val gh = if (githubIndexOn) {
            try {
                val (hits, more) = Search.github(q, page)
                ghMore = more
                hits.map { tagMirror(it, mirror).copy(domestic = true) }
            } catch (e: Exception) {
                ghFailed = true; ghCode = httpCode(e)
                Log.e("Search", "domestic github part failed; q=$q", e)
                emptyList()
            }
        } else emptyList()

        // b. Gitee 匿名搜索；403/429 视为限流，结果区显示一行灰色提示，不将整个搜索标红
        val ge = try {
            val (hits, more) = Search.gitee(q, page)
            geMore = more
            hits
        } catch (e: Exception) {
            geFailed = true; geCode = httpCode(e)
            if (geCode == 403 || geCode == 429) geRate = true
            Log.e("Search", "gitee part failed code=$geCode; q=$q", e)
            emptyList()
        }

        // c. GitCode：未配置 token 时直接跳过（不发 401）；已配置时携带令牌请求
        val gc = if (AppState.gitcodeToken.isNotBlank()) {
            try {
                val (hits, more) = Search.gitcode(q, page = page)
                gcMore = more
                hits
            } catch (e: Exception) {
                gcFailed = true
                Log.e("Search", "gitcode part failed (skipped); q=$q", e)
                emptyList()
            }
        } else emptyList()

        val all = gh + ge + gc
        val failed = listOfNotNull(
            if (ghFailed) "GitHub" else null,
            if (geFailed) "Gitee" else null,
            if (gcFailed) "GitCode" else null
        )
        val codeText = listOfNotNull(ghCode, geCode).firstOrNull()?.let { "（错误码 $it）" } ?: ""
        return when {
            all.isNotEmpty() -> {
                val hint = if (geRate) "Gitee 暂时限流，仅显示 GitHub 结果" else ""
                val more = ghMore || geMore || gcMore
                Outcome(
                    results = all,
                    msg = "已加载 ${searchResults.size + all.size} 个仓库",
                    hint = hint,
                    hasMore = more
                )
            }
            failed.isNotEmpty() ->
                Outcome(emptyList(), "网络异常，${failed.joinToString("、")}请求失败$codeText，点此重试", isError = true)
            else -> Outcome(emptyList(), "未找到与「$q」相关的仓库")
        }
    }

    /** 从异常中提取 HTTP 状态码（[HttpException] 直接获取；回退解析 "HTTP nnn" 文本） */
    private fun httpCode(e: Throwable): Int? = when (e) {
        is HttpException -> e.code
        else -> HTTP_CODE.find(e.message ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}
