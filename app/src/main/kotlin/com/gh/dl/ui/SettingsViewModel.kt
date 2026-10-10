package com.gh.dl.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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

    private companion object {
        /** 回主线程写 Compose 状态用（见 [initSettings] 里那段后台迁移） */
        val MAIN = Handler(Looper.getMainLooper())
    }

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
    /** 下载加速（ghproxy 风格第三方中转文件前缀）总开关 */
    var accelOn by AppState::accelOn
    /** 浏览加速（githubfast 类网页镜像）总开关，与下载加速互相独立 */
    var browseAccelOn by AppState::browseAccelOn
    /** 浏览的解析引擎 / 图片方式 / 抓取器，取值见 [com.gh.dl.data.GhView] 与 [com.gh.dl.data.Fetcher] */
    var parseEngine by AppState::parseEngine
    var imageMode by AppState::imageMode
    var fetcher by AppState::fetcher
    /** 解析器页是否记录历史链接 */
    var historyOn by AppState::historyOn
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
    /** 「DoH + 真实 IP 优选」开关（第九轮新增，默认关闭） */
    var dnsPinningOn by AppState::dnsPinningOn
    /** 「启动时自动探活国内镜像源」开关（第九轮新增，默认关闭） */
    var mirrorProbeOn by AppState::mirrorProbeOn
    /** libgit2 通道：https / ssh */
    var sshChannel by AppState::sshChannel

    /** 下载加速前缀列表（本地持久保存） */
    val accelPrefixes = mutableStateListOf<String>()

    /** 浏览加速的网页镜像站列表（本地持久保存；空列表时界面按内置默认显示） */
    val browseMirrors = mutableStateListOf<String>()

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
        browseAccelOn = Settings.get(ctx, "browseAccelOn", "0") != "0"
        // 第九轮：两个「自动外联」性质的开关，默认关。dnsPinningOn 要同时落到 data 层的
        // Net.dnsPinningOn —— Net 不读 ViewModel，不灌的话界面显示关着、底层还在跑。
        dnsPinningOn = Settings.get(ctx, "dnsPinningOn", "0") != "0"
        Net.dnsPinningOn = dnsPinningOn
        mirrorProbeOn = Settings.get(ctx, "mirrorProbeOn", "0") != "0"
        parseEngine = Settings.get(ctx, "parseEngine", "jsoup")
        imageMode = Settings.get(ctx, "imageMode", "local")
        fetcher = Settings.get(ctx, "fetcher", "okhttp")
        historyOn = Settings.get(ctx, "historyOn", "0") != "0"
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
        // 外观在 MainActivity.onCreate 里已经**同步**预读过一次（那是为了首帧配色正确，
        // 不能等这里），这一行只是把同一个值灌进 Compose 状态、让后续重组也能读到。
        // 两边都走 Settings.themeMode()，默认值只有一份。
        AppState.themeMode = Settings.themeMode(ctx)
        accelPrefixes.clear()
        accelPrefixes.addAll(Settings.loadAccelPrefixes(ctx))
        browseMirrors.clear()
        browseMirrors.addAll(Settings.loadBrowseMirrors(ctx))
        // 抓取层（data 层的 GhView）不读 ViewModel，启动时把列表灌进去一次，
        // 之后由界面侧的 setBrowseMirrors 同步，保持单一真值源。
        com.gh.dl.data.GhView.applyMirrors(browseMirrors.toList())
        // 图片缓存与外部抓取器的临时目录：都放 cacheDir（可随时重建、系统能自己清）。
        // 不注入的话图片缓存读写静默跳过、curl/wget 档直接不可用，功能不会崩，只是退化。
        val imgDir = java.io.File(ctx.cacheDir, "ghimg")
        com.gh.dl.data.GhView.imageCacheDir = imgDir
        com.gh.dl.data.Fetcher.tmpDir = java.io.File(ctx.cacheDir, "ghfetch")
        // 内置 curl / wget 的所在目录 + 根证书包：第八轮把两个命令行工具打进 APK 后，
        // 抓取层要能知道去 nativeLibraryDir 找它们（SELinux 只允许那里执行），
        // 并且要拿到一份 PEM 才能对 TLS 做完整校验（静态编译读不到 Android 系统信任库）。
        com.gh.dl.data.Fetcher.binDir = java.io.File(ctx.applicationInfo.nativeLibraryDir)
        com.gh.dl.data.Fetcher.cacert = com.gh.dl.data.AriaEngine.caBundleFor(ctx)
        val p = proxy.split(":")
        if (p.size == 2) {
            Net.proxyHost = p[0].trim()
            Net.proxyPort = p[1].trim().toIntOrNull() ?: 0
            Net.rebuild()
        }
        // 解析历史是「列表」而不是单值，读进 ParserViewModel 的内存镜像供界面渲染
        parser.reloadHistory(ctx)
        AppState.initialized = true
        // 老钥匙迁移 + 读取当前钥匙：这两步要在主线程之外做。
        // migrateLegacy 命中旧文件时会走一次 importPem —— 里面是 Keystore 加解密（几十到几百毫秒），
        // refreshKey 也要列目录并读文件；它们原来直接跟在 initSettings 尾巴上，
        // 而 initSettings 是在首帧之后的 LaunchedEffect 里跑的，等于把这段磁盘 + Keystore 活
        // 压在用户刚进 App 的那一刻，表现就是「进去之后先卡一下再能动」。
        Thread {
            SshKeys.migrateLegacy(ctx)
            val a = SshKeys.active(ctx)
            MAIN.post {
                AppState.pubKey = a?.pub ?: ""
                AppState.fingerprint = a?.fp ?: ""
            }
        }.start()
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

    /** 浏览镜像站：追加（去重）并持久保存，同时同步给抓取层 */
    fun addBrowseMirrors(ctx: Context, list: List<String>) {
        for (m in list) {
            val t = m.trim().trimEnd('/')
            if (t.isNotBlank() && browseMirrors.none { it.equals(t, true) }) browseMirrors.add(t)
        }
        applyBrowseMirrors(ctx)
    }

    fun removeBrowseMirror(ctx: Context, m: String) {
        browseMirrors.remove(m)
        applyBrowseMirrors(ctx)
    }

    /** 恢复内置镜像站（清空自定义列表 = 用 GhView 的默认值） */
    fun resetBrowseMirrors(ctx: Context) {
        browseMirrors.clear()
        applyBrowseMirrors(ctx)
    }

    private fun applyBrowseMirrors(ctx: Context) {
        Settings.saveBrowseMirrors(ctx, browseMirrors.toList())
        com.gh.dl.data.GhView.applyMirrors(browseMirrors.toList())
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
