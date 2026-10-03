package com.gh.dl.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.gh.dl.data.HttpException
import com.gh.dl.data.Search

/**
 * 搜索页的状态与动作。原 [AppState] 中与搜索相关的字段与 [com.gh.dl.ui.doSearch] 迁到本 ViewModel。
 * 跨页共享的加速开关 / 自建节点由 [settings] 持有（搜索走加速代理触达 GitHub 时需要）。
 */
class SearchViewModel : ViewModel() {

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

    /** 子请求限流/跳过的灰色提示（如「Gitee 暂时限流，仅显示 GitHub 结果」），与错误文案分开 */
    var searchHint by mutableStateOf("")

    /** 当前 searchMsg 是否为「网络异常」类错误（UI 上可点击重试） */
    var searchIsError by mutableStateOf(false)

    fun refreshSearch(ctx: Context) {
        if (searchQuery.isBlank()) {
            toast(ctx, "先输入关键词再刷新")
            return
        }
        doSearch(ctx)
    }

    /** 按关键词搜项目：三个源各自独立处理，一个失败不影响其它（修复「国内源」把空结果与失败混为一谈） */
    fun doSearch(ctx: Context) {
        val q = searchQuery.trim()
        if (q.isBlank()) {
            toast(ctx, "先输入关键词，例如 Python、Java")
            return
        }
        // 选中的优先镜像站（解析器下拉）中文短名；仅用于给 GitHub 官方源结果打「可走 XX 镜像下载」标。
        val mirror = runCatching { settings.parser.domestic }.getOrNull()?.let { Search.mirrorLabel(it) }
        searchBusy = true
        searchMsg = "正在搜索「$q」…"
        searchHint = ""
        searchIsError = false
        searchResults = emptyList()
        Thread {
            val outcome = when (searchSource) {
                "foreign" -> searchForeign(q)
                "domestic" -> searchDomestic(q, mirror)
                else -> searchGithubOnly(q, mirror)
            }
            MAIN.post {
                searchBusy = false
                searchResults = outcome.results
                searchMsg = outcome.msg
                searchHint = outcome.hint
                searchIsError = outcome.isError
            }
        }.start()
    }

    /** 单个源的搜索结果 + 展示文案（区分「有结果 / 全部成功但空 / 部分或全部失败」三种状态） */
    private data class Outcome(
        val results: List<Search.Hit>,
        val msg: String,
        val hint: String = "",
        val isError: Boolean = false
    )

    /** 给 GitHub 官方源结果打上优先镜像站标签（仅 github 源；镜像站只代理 Release，不代理搜索） */
    private fun tagMirror(h: Search.Hit, mirror: String?): Search.Hit =
        if (mirror != null && h.source == "github") h.copy(mirrorLabel = mirror) else h

    /** GitHub 源（或国内源里的 GitHub 索引部分）：直连 api.github.com，失败回退前缀已由 [com.gh.dl.data.Accel] 处理 */
    private fun searchGithubOnly(q: String, mirror: String?): Outcome {
        if (!githubIndexOn) return Outcome(emptyList(), "未找到与「$q」相关的仓库")
        return try {
            val hits = Search.github(q).map { tagMirror(it, mirror) }
            Outcome(hits, if (hits.isEmpty()) "未找到与「$q」相关的仓库" else "找到 ${hits.size} 个仓库")
        } catch (e: Exception) {
            // 友好文案，原始异常与响应体只写 Logcat，绝不在 Release 暴露给用户
            Log.e("Search", "github search failed; q=$q", e)
            Outcome(emptyList(), "GitHub API 暂时无响应，点此重试", isError = true)
        }
    }

    /** 外国源：GitLab + Codeberg 并发，任一失败静默跳过，不影响另一个。
     *  注意：网络层失败（超时/DNS/连接拒绝）没有 HTTP 状态码，必须用独立布尔标志记录，
     *  否则会把「全都失败」误判成「搜到但为空」。 */
    private fun searchForeign(q: String): Outcome {
        var glFailed = false
        var cbFailed = false
        val gl = runCatching { Search.gitlab(q) }.getOrElse { glFailed = true; emptyList() }
        val cb = runCatching { Search.codeberg(q) }.getOrElse { cbFailed = true; emptyList() }
        val all = gl + cb
        val failed = listOfNotNull(
            if (glFailed) "GitLab" else null,
            if (cbFailed) "Codeberg" else null
        )
        return when {
            all.isNotEmpty() -> Outcome(all, "找到 ${all.size} 个仓库")
            failed.isNotEmpty() ->
                Outcome(emptyList(), "网络异常，${failed.joinToString("、")}请求失败，点此重试", isError = true)
            else -> Outcome(emptyList(), "未找到与「$q」相关的仓库")
        }
    }

    /** 国内源：GitHub 索引 + Gitee + GitCode 三个后端各自独立，一个失败不影响其它 */
    private fun searchDomestic(q: String, mirror: String?): Outcome {
        // 失败标志与 HTTP 错误码分开记录：网络超时（无状态码）也要算失败
        var ghFailed = false; var ghCode: Int? = null
        var geFailed = false; var geCode: Int? = null
        var gcFailed = false
        var geRate = false

        // a. GitHub 官方索引（高校镜像不提供搜索，复用官方）
        val gh = if (githubIndexOn) {
            try {
                Search.github(q).map { tagMirror(it, mirror).copy(domestic = true) }
            } catch (e: Exception) {
                ghFailed = true; ghCode = httpCode(e)
                Log.e("Search", "domestic github part failed; q=$q", e)
                emptyList()
            }
        } else emptyList()

        // b. Gitee 匿名搜索；403/429 视为限流，结果区给一行灰色提示，不把整个搜索标红
        val ge = try {
            Search.gitee(q)
        } catch (e: Exception) {
            geFailed = true; geCode = httpCode(e)
            if (geCode == 403 || geCode == 429) geRate = true
            Log.e("Search", "gitee part failed code=$geCode; q=$q", e)
            emptyList()
        }

        // c. GitCode：没在设置里配 access_token 就直接跳过（不发出去碰 401）；配了才带令牌请求
        val gc = if (AppState.gitcodeToken.isNotBlank()) {
            try { Search.gitcode(q) } catch (e: Exception) {
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
                Outcome(all, "找到 ${all.size} 个仓库", hint = hint)
            }
            // 有结果时不受失败影响；无结果但确有后端失败 → 网络错误，绝不谎报「未找到」
            failed.isNotEmpty() ->
                Outcome(emptyList(), "网络异常，${failed.joinToString("、")}请求失败$codeText，点此重试", isError = true)
            else -> Outcome(emptyList(), "未找到与「$q」相关的仓库")
        }
    }

    /** 从异常里取出 HTTP 状态码（[HttpException] 直接给；兜底解析 "HTTP nnn" 文本） */
    private fun httpCode(e: Throwable): Int? = when (e) {
        is HttpException -> e.code
        else -> Regex("HTTP (\\d+)").find(e.message ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}
