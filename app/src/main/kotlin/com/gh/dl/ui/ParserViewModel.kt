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
class ParserViewModel : ViewModel() {

    lateinit var settings: SettingsViewModel
    lateinit var downloads: DownloadsViewModel

    // ---- 解析输入与结果 ----
    var input by mutableStateOf("")
    var slug by mutableStateOf("")
    var repo by mutableStateOf<com.gh.dl.data.RepoInfo?>(null)
    var releases by mutableStateOf<List<com.gh.dl.data.Release>>(emptyList())
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

    // ---- 解析历史（第六轮新增，「设置 → 浏览」里可关） ----
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

    fun doLoad(ctx: Context, raw: String) {
        val s = parseSlug(raw)
        if (s == null) {
            error = "没认出仓库地址，形如 github.com/owner/repo"
            return
        }
        slug = s
        loading = true
        error = ""
        repo = null
        releases = emptyList()
        refs = emptyList()
        jsd = false
        jsdTree = emptyList()
        jsdVersions = emptyList()
        packMsg = ""
        mirror = null
        mirrorAll = emptyList()
        mirrorMsg = ""

        Thread {
            try {
                val base = Channels.apiBase(settings.channel, settings.custom)
                val r = parseRepo(JSONObject(Net.get("$base/repos/$s", settings.token)))
                val rel = try {
                    parseReleases(JSONArray(Net.get("$base/repos/$s/releases?per_page=10", settings.token)))
                } catch (e: Exception) {
                    emptyList()
                }
                val list = ArrayList<Pair<String, String>>()
                list.add("heads/" + r.defaultBranch to "分支：${r.defaultBranch}（默认）")
                try {
                    val a = JSONArray(Net.get("$base/repos/$s/branches?per_page=40", settings.token))
                    for (i in 0 until a.length()) {
                        val n = a.optJSONObject(i)?.optString("name").orEmpty()
                        if (n.isNotBlank() && n != r.defaultBranch) list.add("heads/$n" to "分支：$n")
                    }
                } catch (e: Exception) { /* 忽略 */ }
                try {
                    val a = JSONArray(Net.get("$base/repos/$s/tags?per_page=40", settings.token))
                    for (i in 0 until a.length()) {
                        val n = a.optJSONObject(i)?.optString("name").orEmpty()
                        if (n.isNotBlank()) list.add("tags/$n" to "标签：$n")
                    }
                } catch (e: Exception) { /* 忽略 */ }

                // 记历史放在这里而不是入口处：解析失败的链接（打错的、已删的仓库）
                // 记进去只会变成一条点了必然报错的垃圾项。
                History.record(ctx, raw, s, r.fullName)
                MAIN.post {
                    reloadHistory(ctx)
                    repo = r
                    releases = rel
                    refs = list
                    selRef = "heads/" + r.defaultBranch
                    filter =
                        if (rel.any { it.assets.any { x -> osOf(x.name) == "android" } }) "android" else "all"
                    netSummary = "官方线路可用"
                    loading = false
                    if (source == "domestic") {
                        doMirror(ctx, preferBase = domesticBase(ctx))
                    }
                }
            } catch (e: Exception) {
                var any = false

                try {
                    val all = Mirror.openAll(s, 5, prefer = domesticBase(ctx))
                    if (all.isNotEmpty()) {
                        any = true
                        val first = all.first().second
                        MAIN.post {
                            mirrorAll = all
                            mirror = first
                            mirrorMsg =
                                "官方线路不可达 · 已切换国内镜像（${first.mirror}）· 共 ${all.size} 个版本"
                            netSummary = "官方线路不可达 · 国内镜像可用"
                            loading = false
                        }
                    } else {
                        val hit = Mirror.open(s)
                        if (hit != null) {
                            any = true
                            MAIN.post {
                                mirror = hit
                                mirrorMsg = "官方线路不可达 · 已切换国内镜像（${hit.mirror}）"
                                netSummary = "官方线路不可达 · 国内镜像可用"
                                loading = false
                            }
                        }
                    }
                } catch (e2: Exception) { /* 忽略 */ }

                try {
                    val vs = Jsd.versions(s)
                    if (vs.isNotEmpty()) {
                        any = true
                        MAIN.post {
                            jsd = true
                            jsdVersions = vs
                            jsdVer = vs.first()
                            packMsg = "正在读取文件树…"
                            netSummary = "官方线路不可达 · jsDelivr 可用"
                            loading = false
                        }
                        val t = Jsd.tree(s, vs.first())
                        MAIN.post {
                            jsdTree = t
                            packMsg = "共 ${t.size} 个文件 · ${fmtSize(t.sumOf { it.size })}"
                        }
                    }
                } catch (e3: Exception) { /* 忽略 */ }

                if (!any) {
                    MAIN.post {
                        netSummary = "GitHub 官方线路不可达"
                        error = "这个仓库三条常规通道都取不到。\n\n" +
                            "① 先核对链接是否写对、仓库是否存在；\n" +
                            "② 当前网络对 GitHub 是三层封锁（DNS 污染 / IP 段阻断 / TLS 重置）；\n" +
                            "③ 国内镜像站只同步部分热门开源项目，没同步的仓库拿不到。\n\n" +
                            "仓库确实存在的话，用下面的「SSH 直连克隆」通道（走 22 端口）能拿到。"
                        loading = false
                    }
                }
            }
        }.start()
    }

    fun doMirror(ctx: Context, preferVer: String? = null, preferBase: String? = null) {
        if (slug.isBlank()) {
            toast(ctx, "先解析一个仓库链接")
            return
        }
        mirrorBusy = true
        mirrorMsg = "正在查询国内镜像站…"
        Thread {
            try {
                val hit = Mirror.open(slug, preferVer, preferBase)
                MAIN.post {
                    mirrorBusy = false
                    if (hit == null) {
                        mirror = null
                        mirrorMsg = "国内镜像站暂无这个仓库（镜像站只同步部分热门开源项目）"
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
        }.start()
    }

    fun doTree(ctx: Context, v: String) {
        if (slug.isBlank()) return
        busy = true
        packMsg = "正在读取文件树…"
        Thread {
            try {
                val t = Jsd.tree(slug, v)
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
        }.start()
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

    fun doDiagnose(ctx: Context) {
        diagBusy = true
        lineInfo = "正在诊断…"
        Thread {
            val sb = StringBuilder()
            try {
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
        }.start()
    }

    /**
     * 解析器页「源选择」生效：把官方源 / 国内源（高校镜像）映射到下载路由。
     *
     * [domestic] 的取值只可能是 [com.gh.dl.data.MirrorManager] 管理的镜像站 id（tuna/ustc/…），
     * 界面上没有「加速代理」这一项，所以这里不再判断已删除的 "ghproxy" 分支——
     * 那段判断永远为假，只会让读代码的人以为还存在这条路径。加速开关只由「闪电」按钮控制。
     */
    fun applySource(ctx: Context) {
        when (source) {
            "domestic" -> {
                // 高校镜像：下载走镜像站（NEVER_PREFIX 不会被套前缀），而 GitHub 索引仍按用户「闪电」开关
                // 走前缀 / 直连。此处不能再强制关掉加速，否则国内源搜索里的 GitHub 索引会被直连墙掉、
                // 加上 Gitee 偶发限流，两个后端一起失败就会误报「网络异常」。
                settings.channel = Channels.OFFICIAL
            }
            else -> {
                // 解析 / 搜索源切换不应覆盖用户自己的加速开关；加速只由「闪电」按钮控制。
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
     *  改为查 [com.gh.dl.data.MirrorManager] 受管列表，使导入的镜像也能用于下载。 */
    fun domesticBase(ctx: Context): String? = MirrorManager.baseOf(ctx, domestic)

    /** 解析器页刷新：重新拉取当前仓库数据，并重新探测网络线路 */
    fun refreshParser(ctx: Context) {
        if (slug.isNotBlank()) {
            doLoad(ctx, "https://github.com/$slug")
        }
        doDiagnose(ctx)
    }

    /**
     * 「浏览」按钮要打开的仓库网页地址。
     * 优先用已解析出来的 htmlUrl；还没解析时，只要能认出 owner/repo 就直接拼官方地址
     * （输入框允许写 owner/repo 这种简写，parseSlug 已支持）。
     * 认不出来返回 null，按钮置灰。
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
            toast(ctx, "先勾选要下载的文件")
            return
        }
        for (it in items) downloads.enqueueDownload(ctx, it.url, it.name, it.sha, it.size)
        parserMultiSelect = false
        parserSel.clear()
        toast(ctx, "已加入 ${items.size} 个下载任务")
    }

    /** 从搜索结果点进解析器（GitHub / 国内源走官方解析 + 镜像；外国源、国内代码托管在浏览器打开） */
    fun openSearchHit(ctx: Context, h: Search.Hit) {
        when (h.source) {
            "github" -> {
                input = "https://github.com/${h.fullName}"
                source = if (h.domestic) "domestic" else "official"
                applySource(ctx)
                AppState.tab = AppState.TAB_PARSER
                doLoad(ctx, input)
            }
            "gitee", "gitcode" -> {
                val i = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(h.htmlUrl)
                )
                ctx.startActivity(i)
            }
            "gitlab", "codeberg" -> {
                val i = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(h.htmlUrl)
                )
                ctx.startActivity(i)
            }
        }
    }
}
