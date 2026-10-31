package com.gh.dl.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.gh.dl.data.Channels
import com.gh.dl.data.Fetch
import com.gh.dl.data.History
import com.gh.dl.data.Jsd
import com.gh.dl.data.Mirror
import com.gh.dl.data.MirrorManager
import com.gh.dl.data.Net
import com.gh.dl.data.Search
import com.gh.dl.data.Settings
import com.gh.dl.data.Threads
import com.gh.dl.data.fmtSize
import com.gh.dl.data.osOf
import com.gh.dl.data.parseRepo
import com.gh.dl.data.parseReleases
import com.gh.dl.data.parseSlug
import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析器页的状态与动作。原 [AppState] 中与「解析 / 镜像 / jsDelivr / 源选择 / 打包」
 * 相关的 30+ 字段与动作函数，按页归属迁到本 ViewModel。
 *
 * 跨页共享配置（下载通道 channel/custom/accelOn 等）由 [settings] 持有；
 * 下载任务列表与打包执行由 [downloads] 持有。
 */
/**
 * 判断响应是否像 GitHub API 的 JSON。
 *
 * 仓库信息返回对象 `{}`，Release / 分支 / 标签返回数组 `[]`；
 * 旧实现只接受 `{`，把合法的 Release 数组误判为中转站首页，
 * 进而触发一次多余的直连重试，在 DNS 污染网络下最终显示「域名解析失败」。
 */
internal fun isGithubApiJson(body: String): Boolean {
    val t = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
    return t.startsWith("{") || t.startsWith("[")
}

class ParserViewModel : ViewModel() {

    lateinit var settings: SettingsViewModel
    lateinit var downloads: DownloadsViewModel

    // ---- 解析输入与结果 ----
    var input by mutableStateOf("")
    var slug by mutableStateOf("")
    var repo by mutableStateOf<com.gh.dl.data.RepoInfo?>(null)
    var releases by mutableStateOf<List<com.gh.dl.data.Release>>(emptyList())
    /** Release 列表正在单独刷新：仓库信息已显示，列表尚未返回 */
    var releasesLoading by mutableStateOf(false)
    /** Release 请求失败原因；与「仓库没有 Release」区分显示 */
    var releasesError by mutableStateOf("")
    var refs by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var selRef by mutableStateOf("")
    var filter by mutableStateOf("all")
    var loading by mutableStateOf(false)
    var error by mutableStateOf("")

    // ---- 网络结论 ----
    var netSummary by mutableStateOf("")
    var lineInfo by mutableStateOf("")
    var diagBusy by mutableStateOf(false)

    // ---- jsDelivr 整仓源码 ----
    var jsd by mutableStateOf(false)
    var jsdVersions by mutableStateOf<List<String>>(emptyList())
    var jsdVer by mutableStateOf("")
    var jsdTree by mutableStateOf<List<Jsd.Entry>>(emptyList())
    var packMsg by mutableStateOf("")
    var busy by mutableStateOf(false)

    // ---- 国内镜像 ----
    var mirror by mutableStateOf<Mirror.Hit?>(null)
    var mirrorAll by mutableStateOf<List<Pair<String, Mirror.Hit>>>(emptyList())
    var mirrorMsg by mutableStateOf("")
    var mirrorBusy by mutableStateOf(false)

    // ---- 源选择（官方 / 国内） ----
    var source by mutableStateOf("official")
    var domestic by mutableStateOf("tuna")

    // ---- 批量下载多选 ----
    var parserMultiSelect by mutableStateOf(false)
    val parserSel = mutableStateListOf<SelItem>()

    // ---- 添加链接弹窗 ----
    var showAddLink by mutableStateOf(false)
    var showParseDialog by mutableStateOf(false)
    var pendingInput by mutableStateOf("")

    // ---- 解析历史（可在「设置 → 浏览」中关闭） ----
    /** 内存镜像，界面直接渲染它；真值在 SharedPreferences（见 [com.gh.dl.data.History]） */
    val history = mutableStateListOf<History.Entry>()
    var showHistory by mutableStateOf(false)

    /** 启动 / 设置变更后重新读一遍（关掉开关时列表要立刻按用户预期变化，所以由界面主动调） */
    fun reloadHistory(ctx: android.content.Context) {
        history.clear()
        history.addAll(History.load(ctx))
    }

    fun removeHistory(ctx: android.content.Context, slug: String) {
        History.remove(ctx, slug)
        reloadHistory(ctx)
    }

    fun clearHistory(ctx: android.content.Context) {
        History.clear(ctx)
        reloadHistory(ctx)
    }

    /** 点历史条目：填回输入框并重新解析（用户要的就是「不用再去翻聊天记录找链接」） */
    fun openHistory(ctx: android.content.Context, e: History.Entry) {
        input = e.input
        showHistory = false
        doLoad(ctx, e.input)
    }

    // -----------------------------------------------------------------------
    // 解析：GitHub 官方 API → 国内镜像 → jsDelivr
    // -----------------------------------------------------------------------

    /** 解析请求序号：刷新 / 重新解析时递增，旧请求的结果不再写入界面 */
    @Volatile private var loadSeq = 0

    /**
     * 官方 API 请求结果：body、实际线路（是否绕开加速前缀）、以及触发绕行的原因。
     * [note] 用于在界面如实说明「前缀不可用、已直连」，避免把直连成功误报成加速可用。
     */
    private class ApiResult(val body: String, val direct: Boolean, val note: String = "")

    /**
     * 请求 GitHub API，失败时自动切换线路重试一次。
     *
     * 加速前缀为 gh-proxy 风格的**文件**中转，部分中转站不代理 api.github.com
     *（返回自身首页或 403），而直连在部分网络下又不可达。原实现只按当前配置发一次请求，
     * 任何异常都被当作「官方线路不可达」，用户必须手动刷新第二次或重启应用才可能成功。
     * 现在：首次按当前配置请求 → 清空优选 IP → 绕开前缀直连重试；
     * 任一成功即返回，并把实际生效的线路告诉后续可选请求。
     */
    private fun apiGet(
        path: String,
        timeoutMs: Long,
        requireRepo: Boolean = false
    ): ApiResult {
        val url = "https://api.github.com" + path
        // 加速前缀可能返回 HTTP 200 的中转站首页：内容不是 JSON 同样视为失败，
        // 不能等到 JSONObject 解析时才失败，否则不会触发直连重试
        fun fetch(direct: Boolean): String {
            val body = Net.get(url, settings.token, direct = direct, timeoutMs = timeoutMs)
            if (!isGithubApiJson(body)) {
                throw RuntimeException("返回内容不是 GitHub API 响应")
            }
            // 中转站可能以 HTTP 200 返回 {"message":"..."}：只校验首字符会把它当成仓库。
            if (requireRepo && !body.contains("\"full_name\"")) {
                throw RuntimeException("API 返回内容缺少仓库信息")
            }
            return body
        }
        try {
            return ApiResult(fetch(direct = false), false)
        } catch (e: Exception) {
            // 令牌无效属确定性错误，换线路无意义
            if (e is com.gh.dl.data.HttpException && e.code == 401) throw e
            // 未启用加速时不存在「另一条线路」：再发一次只会把超时翻倍。
            // 这也是 1.2.1 更快的原因之一，保持单次请求语义。
            if (!com.gh.dl.data.Accel.enabled()) throw e
            val note = describe(e)
            Net.clearPins()
            try {
                return ApiResult(fetch(direct = true), true, note)
            } catch (e2: Exception) {
                throw RuntimeException(
                    "加速前缀失败（$note）；直连失败（${describe(e2)}）", e2
                )
            }
        }
    }

    /** 把网络异常转成可读原因，避免所有失败都显示为同一句「官方线路不可达」 */
    private fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "域名解析失败"
        is java.net.SocketTimeoutException -> "连接超时"
        is javax.net.ssl.SSLException -> "TLS 连接被重置"
        is com.gh.dl.data.HttpException -> "HTTP ${e.code}"
        is org.json.JSONException -> "API 响应格式异常（可能被中转站改写）"
        else -> (e.message ?: e.javaClass.simpleName).take(80)
    }

    fun doLoad(ctx: Context, raw: String) {
        val s = parseSlug(raw)
        if (s == null) {
            error = "无法识别仓库地址，预期格式：github.com/owner/repo"
            return
        }
        val my = ++loadSeq
        slug = s
        loading = true
        error = ""
        netSummary = "正在连接 GitHub API…"
        repo = null
        releases = emptyList()
        releasesLoading = false
        releasesError = ""
        refs = emptyList()
        jsd = false
        jsdTree = emptyList()
        jsdVersions = emptyList()
        packMsg = ""
        mirror = null
        mirrorAll = emptyList()
        mirrorMsg = ""
        busy = false

        Threads.start("解析仓库") {
            // ---- 1. 官方 API：仓库基本信息 ----
            var officialError = ""
            var official: ApiResult? = null
            var r: com.gh.dl.data.RepoInfo? = null
            try {
                official = apiGet("/repos/$s", timeoutMs = 12_000, requireRepo = true)
                r = parseRepo(JSONObject(official.body))
            } catch (e: Exception) {
                officialError = describe(e)
            }

            if (my != loadSeq) return@start

            if (r != null && official != null) {
                val info = r
                val direct = official.direct
                // 先展示仓库本身，再拉取 Release / 分支 / 标签：后者属可选信息，
                // 串行等待会让用户在「解析」后长时间看不到任何内容
                MAIN.post {
                    if (my != loadSeq) return@post
                    repo = info
                    refs = listOf("heads/" + info.defaultBranch to "分支：${info.defaultBranch}（默认）")
                    selRef = "heads/" + info.defaultBranch
                    val accelProblem = when {
                        !AppState.accelOn -> ""
                        AppState.custom.isBlank() -> "加速已开启，但未设置前缀"
                        com.gh.dl.data.Accel.normalizePrefix(AppState.custom).isBlank() ->
                            "加速前缀格式无效"
                        else -> ""
                    }
                    netSummary = when {
                        accelProblem.isNotBlank() -> "$accelProblem · 官方线路可用（直连）"
                        !com.gh.dl.data.Accel.enabled() -> "官方线路可用"
                        direct -> "加速前缀不可用（${official?.note.orEmpty().ifBlank { "已直连" }}）· 官方线路可用"
                        else -> "加速前缀可用（经第三方中转）"
                    }
                    loading = false
                    // Release 在仓库信息之后单独请求，界面据此显示「正在获取」而不是空白
                    releasesLoading = true
                    releasesError = ""
                }

                var relError = ""
                val rel = try {
                    // 与仓库信息走同一套「前缀 → 直连」降级逻辑：
                    // 部分中转站只代理文件、不代理 API，单次请求失败不应让 Release 永久空白
                    val rr = apiGet("/repos/$s/releases?per_page=10", timeoutMs = 12_000)
                    parseReleases(JSONArray(rr.body))
                } catch (e: Exception) {
                    relError = describe(e)
                    emptyList()
                }
                if (my != loadSeq) return@start
                // Release 一到就先显示，不等待分支 / 标签请求，避免列表长时间空白
                MAIN.post {
                    if (my != loadSeq) return@post
                    releases = rel
                    releasesError = relError
                    releasesLoading = false
                    filter = if (rel.any { it.assets.any { x -> osOf(x.name) == "android" } }) {
                        "android"
                    } else {
                        "all"
                    }
                }
                val list = ArrayList<Pair<String, String>>()
                list.add("heads/" + info.defaultBranch to "分支：${info.defaultBranch}（默认）")
                try {
                    val a = JSONArray(Net.get(
                        "https://api.github.com/repos/$s/branches?per_page=40",
                        settings.token, direct = direct, timeoutMs = 8_000
                    ))
                    for (i in 0 until a.length()) {
                        val n = a.optJSONObject(i)?.optString("name").orEmpty()
                        if (n.isNotBlank() && n != info.defaultBranch) list.add("heads/$n" to "分支：$n")
                    }
                } catch (e: Exception) { /* 可选信息，失败忽略 */ }
                try {
                    val a = JSONArray(Net.get(
                        "https://api.github.com/repos/$s/tags?per_page=40",
                        settings.token, direct = direct, timeoutMs = 8_000
                    ))
                    for (i in 0 until a.length()) {
                        val n = a.optJSONObject(i)?.optString("name").orEmpty()
                        if (n.isNotBlank()) list.add("tags/$n" to "标签：$n")
                    }
                } catch (e: Exception) { /* 可选信息，失败忽略 */ }

                // 记录历史放在解析成功之后：失败链接只会成为必然报错的无效条目
                runCatching { History.record(ctx, raw, s, info.fullName) }
                MAIN.post {
                    if (my != loadSeq) return@post
                    reloadHistory(ctx)
                    refs = list
                    selRef = "heads/" + info.defaultBranch
                }
                if (source == "domestic") doMirror(ctx, preferBase = domesticBase(ctx))
                return@start
            }

            // ---- 2. 国内镜像（官方不可用时） ----
            MAIN.post {
                if (my != loadSeq) return@post
                netSummary = "官方 API 不可用（$officialError）· 正在查询国内镜像…"
            }
            val all = try {
                Mirror.openAll(s, 5, prefer = domesticBase(ctx), budgetMs = 12_000)
            } catch (e: Exception) {
                emptyList()
            }
            if (my != loadSeq) return@start
            if (all.isNotEmpty()) {
                val first = all.first().second
                MAIN.post {
                    if (my != loadSeq) return@post
                    mirrorAll = all
                    mirror = first
                    mirrorMsg = "官方 API 不可用（$officialError）· 已切换国内镜像（${first.mirror}）· 共 ${all.size} 个版本"
                    netSummary = "官方 API 不可用 · 国内镜像可用"
                    loading = false
                }
                return@start
            }

            // ---- 3. jsDelivr 源码通道 ----
            MAIN.post {
                if (my != loadSeq) return@post
                netSummary = "官方 API 与国内镜像均不可用 · 正在查询 jsDelivr…"
            }
            val vs = try {
                Jsd.versions(s, timeoutMs = 10_000)
            } catch (e: Exception) {
                emptyList()
            }
            if (my != loadSeq) return@start
            if (vs.isNotEmpty()) {
                MAIN.post {
                    if (my != loadSeq) return@post
                    jsd = true
                    jsdVersions = vs
                    jsdVer = vs.first()
                    packMsg = "正在读取文件树…"
                    netSummary = "官方 API 不可用 · jsDelivr 可用"
                    loading = false
                }
                try {
                    val t = Jsd.tree(s, vs.first(), timeoutMs = 20_000)
                    MAIN.post {
                        if (my != loadSeq) return@post
                        jsdTree = t
                        packMsg = "共 ${t.size} 个文件 · ${fmtSize(t.sumOf { it.size })}"
                    }
                } catch (e: Exception) {
                    // 原实现此处只捕获外层异常，文件树失败时界面永久停在「正在读取文件树…」
                    MAIN.post {
                        if (my != loadSeq) return@post
                        packMsg = "文件树读取失败（${describe(e)}），可切换版本重试"
                    }
                }
                return@start
            }

            // ---- 4. 全部通道失败 ----
            if (my != loadSeq) return@start
            MAIN.post {
                if (my != loadSeq) return@post
                netSummary = "三条通道均不可用"
                val accelHint = when {
                    !AppState.accelOn -> ""
                    AppState.custom.isBlank() -> "（加速已开启，但尚未设置前缀）"
                    com.gh.dl.data.Accel.normalizePrefix(AppState.custom).isBlank() ->
                        "（加速前缀格式无效：${AppState.custom.take(40)}）"
                    else -> ""
                }
                error = "GitHub API、国内镜像与 jsDelivr 均无法获取该仓库。\n\n" +
                    "官方 API 失败原因：$officialError$accelHint\n\n" +
                    "① 核对链接是否正确、仓库是否存在；\n" +
                    "② 当前网络对 GitHub 存在 DNS 污染、IP 段阻断或 TLS 重置；\n" +
                    "③ 国内镜像站仅同步部分热门项目。\n\n" +
                    "若仓库存在，可尝试下方「SSH 直连克隆」（22 端口）。"
                loading = false
            }
        }
    }

    fun doMirror(ctx: Context, preferVer: String? = null, preferBase: String? = null) {
        if (slug.isBlank()) {
            toast(ctx, "请先提供仓库链接")
            return
        }
        mirrorBusy = true
        mirrorMsg = "正在查询国内镜像站…"
        Threads.start("查询镜像站") {
            try {
                val hit = Mirror.open(slug, preferVer, preferBase)
                MAIN.post {
                    mirrorBusy = false
                    if (hit == null) {
                        mirror = null
                        mirrorMsg = "国内镜像站未同步该仓库（镜像站仅同步部分热门项目）"
                    } else {
                        mirror = hit
                        mirrorMsg = "镜像源：${hit.mirror} · 共 ${hit.assets.size} 个文件"
                    }
                }
            } catch (e: Exception) {
                MAIN.post {
                    mirrorBusy = false
                    mirrorMsg = "镜像查询失败：${e.message}"
                }
            }
        }
    }

    fun doTree(ctx: Context, v: String) {
        if (slug.isBlank()) return
        busy = true
        packMsg = "正在读取文件树…"
        Threads.start("读取文件树") {
            try {
                val t = Jsd.tree(slug, v, timeoutMs = 20_000)
                MAIN.post {
                    jsdVer = v
                    jsdTree = t
                    packMsg = "共 ${t.size} 个文件 · ${fmtSize(t.sumOf { it.size })}"
                    busy = false
                }
            } catch (e: Exception) {
                MAIN.post {
                    packMsg = "读取文件树失败：${e.message}"
                    busy = false
                }
            }
        }
    }

    fun doPack(ctx: Context) {
        if (slug.isBlank() || jsdTree.isEmpty()) return
        val job = DlJob(downloads.newDlId(), Fetch.safeName(
            slug.replace("/", "-") + "-" + jsdVer + ".zip"))
        job.kind = "pack"
        downloads.downloads.add(0, job)
        AppState.tab = AppState.TAB_DOWNLOADS
        downloads.runPack(ctx, job, slug, jsdVer, jsdTree)
    }

    /**
     * 通过已拼好前缀的 URL 取一次内容（direct=true 表示不再经过加速拦截器，避免重复拼接）。
     * 用于把「中转站不支持 API / 文件」与「网络完全不可达」区分开。
     */
    private fun probeThroughPrefix(url: String, expectJson: Boolean): String {
        val t0 = System.currentTimeMillis()
        return try {
            val body = Net.text(url, direct = true, timeoutMs = 12_000)
            val trimmed = body.trimStart()
            val ok = if (expectJson) trimmed.startsWith("{")
            else trimmed.isNotEmpty() && !trimmed.startsWith("<")
            if (ok) "可用（${System.currentTimeMillis() - t0}ms）"
            else "返回内容不符合预期（中转站可能不支持该类型）"
        } catch (e: Exception) {
            "失败（${describe(e)}）"
        }
    }

    fun doDiagnose(ctx: Context) {
        diagBusy = true
        lineInfo = "正在诊断…"
        Threads.start("线路诊断") {
            val sb = StringBuilder()
            try {
                // 加速前缀单独检测：很多中转站只代理文件、不代理 api.github.com，
                // 把「前缀不可用」与「GitHub 不可达」分开显示，避免误判成加速功能坏了。
                if (AppState.accelOn) {
                    val p = com.gh.dl.data.Accel.normalizePrefix(AppState.custom)
                    sb.append("加速前缀：")
                    if (p.isBlank()) {
                        sb.append(if (AppState.custom.isBlank()) "已开启，但未填写前缀\n"
                        else "已开启，但格式无效：${AppState.custom.take(40)}\n")
                    } else {
                        sb.append(p).append("\n")
                        sb.append("  前缀 API：")
                            .append(probeThroughPrefix(
                                "$p/https://api.github.com/repos/octocat/Hello-World",
                                expectJson = true
                            )).append("\n")
                        sb.append("  前缀文件：")
                            .append(probeThroughPrefix(
                                "$p/https://raw.githubusercontent.com/github/explore/main/README.md",
                                expectJson = false
                            )).append("\n")
                    }
                }
                sb.append("当前直连探测：").append(Net.plainCheck()).append("\n")
                for (d in Net.diagnose()) {
                    sb.append("\n【").append(d.host).append("】\n")
                    sb.append("  系统DNS: ").append(d.sysIps.joinToString().ifBlank { "无" }).append("\n")
                    sb.append("  DoH 真实: ").append(d.dohIps.joinToString().ifBlank { "无" }).append("\n")
                    sb.append("  结论: ").append(d.verdict)
                }
            } catch (e: Exception) {
                sb.append("\n诊断失败：").append(e.message)
            }
            MAIN.post {
                val txt = sb.toString()
                lineInfo = txt
                diagBusy = false
                netSummary = if (txt.contains("可直连")) "GitHub 官方线路可用"
                else "GitHub 官方线路不可达"
            }
        }
    }

    /**
     * 解析器页「源选择」生效：将官方源 / 国内源（高校镜像）映射到下载路由。
     *
     * [domestic] 的取值仅可能为 [com.gh.dl.data.MirrorManager] 管理的镜像站 id（tuna/ustc/…），
     * 界面中无「加速代理」选项，因此此处不再判断已删除的 "ghproxy" 分支——
     * 该判断始终为假，仅会造成存在此路径的误解。加速开关仅由加速按钮控制。
     */
    fun applySource(ctx: Context) {
        when (source) {
            "domestic" -> {
                // 高校镜像：下载走镜像站（NEVER_PREFIX 不会被套前缀），而 GitHub 索引仍按用户加速开关
                // 使用前缀 / 直连。此处不可强制关闭加速，否则国内源搜索中的 GitHub 索引会被直连阻断、
                // 加上 Gitee 偶发限流，两个后端同时失败会误报「网络异常」。
                settings.channel = Channels.OFFICIAL
            }
            else -> {
                // 解析 / 搜索源切换不应覆盖用户自身的加速开关；加速仅由加速按钮控制。
                settings.channel = Channels.OFFICIAL
            }
        }
        Settings.put(ctx, "source", source)
        Settings.put(ctx, "domestic", domestic)
        Settings.put(ctx, "accelOn", if (settings.accelOn) "1" else "0")
        Settings.put(ctx, "channel", settings.channel)
        Net.rebuild()
    }

    /** 国内源选中高校镜像时，返回该镜像站的 base 地址（用于优先尝试）。
     *  改为查询 [com.gh.dl.data.MirrorManager] 受管列表，使导入的镜像也可用于下载。 */
    fun domesticBase(ctx: Context): String? = MirrorManager.baseOf(ctx, domestic)

    /**
     * 解析器页刷新：完整重新获取当前仓库（仓库信息、Release、分支/标签、镜像），
     * 而不是只刷新 Release 列表。
     *
     * 旧实现还串行执行一次全域名线路诊断（最多 90 秒），会覆盖 [netSummary]
     * 并让「刷新」看起来长时间无响应；诊断已保留为独立按钮，不再捆绑在刷新里。
     */
    fun refreshParser(ctx: Context) {
        if (slug.isNotBlank()) doLoad(ctx, "https://github.com/$slug")
    }

    /**
     * 「浏览」按钮要打开的仓库网页地址。
     * 优先使用已解析出的 htmlUrl；尚未解析时，只要能识别 owner/repo 即直接拼接官方地址
     *（输入框允许 owner/repo 简写格式，parseSlug 已支持）。
     * 无法识别时返回 null，按钮置灰。
     */
    fun browseUrl(): String? {
        repo?.htmlUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val s = parseSlug(input) ?: if (slug.isNotBlank()) slug else null
        return s?.let { "https://github.com/$it" }
    }

    /** 当前解析器页所有可勾选的下载项（Release 资产 / 国内镜像文件 / jsDelivr 文件） */
    fun parserAllItems(): List<SelItem> {
        val out = ArrayList<SelItem>()
        for (rel in releases) {
            for (a in rel.assets) out.add(SelItem(a.url, a.name, null, a.size))
        }
        val mh = mirror
        if (mh != null) {
            for (a in mh.assets.filter { it.name != "sha256.sum" }) out.add(SelItem(a.url, a.name, null, a.size))
        }
        for ((_, hit) in mirrorAll) {
            for (a in hit.assets.filter { it.name != "sha256.sum" }) out.add(SelItem(a.url, a.name, null, a.size))
        }
        for (f in jsdTree) {
            out.add(SelItem(Jsd.fileUrl(slug, jsdVer, f.path), f.path.substringAfterLast('/'), null, f.size))
        }
        return out.distinctBy { it.url }
    }

    /** 全选 / 取消全选 切换 */
    fun toggleParserAll() {
        val all = parserAllItems()
        if (parserSel.size >= all.size && all.isNotEmpty()) {
            parserSel.clear()
        } else {
            parserSel.clear()
            parserSel.addAll(all)
        }
    }

    /** 把当前勾选项加入下载队列，并退出多选模式 */
    fun batchDownloadParser(ctx: Context) {
        val items = parserSel.toList()
        if (items.isEmpty()) {
            toast(ctx, "请先勾选要下载的文件")
            return
        }
        for (it in items) downloads.enqueueDownload(ctx, it.url, it.name, it.sha, it.size)
        parserMultiSelect = false
        parserSel.clear()
        toast(ctx, "已加入 ${items.size} 个下载任务")
    }

    /** 从搜索结果进入解析器（GitHub / 国内源走官方解析 + 镜像；外国源、国内代码托管平台在浏览器中打开） */
    fun openSearchHit(ctx: Context, h: Search.Hit) {
        when (h.source) {
            "github" -> {
                input = "https://github.com/${h.fullName}"
                source = if (h.domestic) "domestic" else "official"
                applySource(ctx)
                AppState.tab = AppState.TAB_PARSER
                doLoad(ctx, input)
            }
            "gitee", "gitcode", "gitlab", "codeberg" ->
                openUrl(ctx, h.htmlUrl, "未找到可打开链接的浏览器")
        }
    }
}
