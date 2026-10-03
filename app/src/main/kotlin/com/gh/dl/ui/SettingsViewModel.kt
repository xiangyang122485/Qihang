package com.gh.dl.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.gh.dl.data.Channels
import com.gh.dl.data.Net
import com.gh.dl.data.Settings
import com.gh.dl.data.SshKeys
import com.gh.dl.data.Store

/**
 * 设置页的状态与动作：下载通道 / 克隆选项 / 加速代理前缀 / SSH 通道等配置，
 * 以及启动初始化。原 [AppState] 中这批配置字段与 [com.gh.dl.ui.initSettings] 等迁到本 ViewModel。
 *
 * 注意：搜索源 / 解析源也由本 VM 在 [initSettings] 时一次性灌入对应 VM（[parser] / [search]），
 * 因为这两个字段逻辑上属于各自的页、但持久化入口在「网络通道」设置页。
 */
class SettingsViewModel : ViewModel() {

    lateinit var parser: ParserViewModel
    lateinit var search: SearchViewModel

    // ---- 下载通道 / 克隆 / 加速 等配置 ----
    // 这些属性直接委托到 [AppState] 的同名全局字段（单一真值源），
    // 使 UI 经本 VM 读写配置时，脱离 Compose 的 data 层（Git/Lfs/Git2/Search）
    // 与前台 [com.gh.dl.CloneService] 也能实时读到最新值，避免两边状态不一致。
    // 委托走属性引用 `by AppState::xxx`，读取时仍命中 [androidx.compose.runtime.State]
    // 快照，Compose 可正常重组。
    var custom by AppState::custom
    var token by AppState::token
    var proxy by AppState::proxy
    var channel by AppState::channel
    /** 加速代理（ghproxy 风格第三方中转）总开关 */
    var accelOn by AppState::accelOn
    /** 下载后强制校验 SHA256，默认开 */
    var verifyOn by AppState::verifyOn
    /** 克隆后自动拉取 Git LFS，默认开 */
    var lfsOn by AppState::lfsOn
    /** 克隆引擎：jgit / libgit2 */
    var engine by AppState::engine
    /** 文件下载引擎：single / okdownload / aria2 */
    var dlEngine by AppState::dlEngine
    /** 克隆选项总开关 */
    var cloneOn by AppState::cloneOn
    /** libgit2 通道：https / ssh */
    var sshChannel by AppState::sshChannel

    /** 加速代理前缀列表（本地持久保存） */
    val accelPrefixes = mutableStateListOf<String>()

    /** 共享文件夹显示名 */
    var sharedName by mutableStateOf("")

    /** 首次进入时把设置读进内存 */
    fun initSettings(ctx: Context) {
        if (AppState.initialized) return
        custom = Settings.get(ctx, "custom")
        token = Settings.get(ctx, "token")
        AppState.gitcodeToken = Settings.get(ctx, "gitcodeToken")
        proxy = Settings.get(ctx, "proxy")
        channel = Settings.get(ctx, "channel", Channels.OFFICIAL)
        accelOn = Settings.get(ctx, "accelOn", "0") != "0"
        verifyOn = Settings.get(ctx, "verifyOn", "1") != "0"
        lfsOn = Settings.get(ctx, "lfsOn", "1") != "0"
        engine = Settings.get(ctx, "engine", "libgit2")
        dlEngine = Settings.get(ctx, "dlEngine", "aria2")
        parser.source = Settings.get(ctx, "source", "official")
        parser.domestic = Settings.get(ctx, "domestic", "tuna")
        search.searchSource = Settings.get(ctx, "searchSource", "github")
        search.githubIndexOn = Settings.get(ctx, "githubIndexOn", "1") != "0"
        sshChannel = Settings.get(ctx, "sshChannel", "https")
        cloneOn = Settings.get(ctx, "cloneOn", "1") != "0"
        sharedName = if (Store.isSet(ctx)) Store.displayName(ctx) else ""
        AppState.themeMode = Settings.get(ctx, "theme_mode", "system")
        accelPrefixes.clear()
        accelPrefixes.addAll(Settings.loadAccelPrefixes(ctx))
        val p = proxy.split(":")
        if (p.size == 2) {
            Net.proxyHost = p[0].trim()
            Net.proxyPort = p[1].trim().toIntOrNull() ?: 0
            Net.rebuild()
        }
        AppState.initialized = true
        SshKeys.migrateLegacy(ctx)
        refreshKey(ctx)
    }

    /** 从一段文本里提取所有合法前缀（以 http(s):// 开头，去尾斜杠） */
    fun parsePrefixes(text: String): List<String> {
        val out = LinkedHashSet<String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            if (line.startsWith("http://", true) || line.startsWith("https://", true)) {
                out.add(line.trimEnd('/'))
            }
        }
        return out.toList()
    }

    /** 追加一批前缀并本地持久保存（去重，忽略空白） */
    fun addAccelPrefixes(ctx: Context, list: List<String>) {
        for (p in list) {
            val t = p.trim()
            if (t.isNotBlank() && accelPrefixes.none { it.equals(t, true) }) {
                accelPrefixes.add(t)
            }
        }
        Settings.saveAccelPrefixes(ctx, accelPrefixes.toList())
    }

    /** 删除单个前缀并持久保存 */
    fun removeAccelPrefix(ctx: Context, p: String) {
        accelPrefixes.remove(p)
        Settings.saveAccelPrefixes(ctx, accelPrefixes.toList())
    }

    /** 清空所有前缀并持久保存 */
    fun clearAccelPrefixes(ctx: Context) {
        accelPrefixes.clear()
        Settings.saveAccelPrefixes(ctx, emptyList())
    }

    /**
     * 导出「直链」：把原始链接经过通道改写后复制到剪贴板，供第三方下载器高速下。
     */
    fun exportLink(ctx: Context, rawUrl: String) {
        val url = Channels.file(channel, custom, rawUrl)
        copyText(ctx, url, "直链已复制")
    }

    /** 分享直链：调起系统选择器，把最终 URL 发给第三方下载器 */
    fun shareLink(ctx: Context, rawUrl: String) {
        val url = Channels.file(channel, custom, rawUrl)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        ctx.startActivity(Intent.createChooser(i, "用其它下载器打开直链"))
    }
}
