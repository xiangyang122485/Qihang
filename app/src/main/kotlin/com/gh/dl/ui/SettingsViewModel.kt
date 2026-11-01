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
import com.gh.dl.data.Accel
import com.gh.dl.data.Channels
import com.gh.dl.data.Net
import com.gh.dl.data.Settings
import com.gh.dl.data.SshKeys
import com.gh.dl.data.Store
import com.gh.dl.data.Threads

/**
 * 设置页的状态与动作：下载通道 / 克隆选项 / 加速代理前缀 / SSH 通道等配置，
 * 以及启动初始化。原 [AppState] 中这批配置字段与 [com.gh.dl.ui.initSettings] 等迁到本 ViewModel。
 *
 * 注意：搜索源 / 解析源也由本 VM 在 [initSettings] 时一次性注入对应 VM（[parser] / [search]），
 * 因为这两个字段逻辑上属于各自的页面，但持久化入口在「设置 → 网络」。
 */
class SettingsViewModel : ViewModel() {

    private companion object {
        /** 回主线程写入 Compose 状态使用（详见 [initSettings] 中的后台迁移） */
        val MAIN = Handler(Looper.getMainLooper())
    }

    lateinit var parser: ParserViewModel
    lateinit var search: SearchViewModel

    // ---- 下载通道 / 克隆 / 加速 等配置 ----
    // 这些属性直接委托到 [AppState] 的同名全局字段（单一真值源），
    // 使 UI 通过本 VM 读写配置时，脱离 Compose 的 data 层（Git/Lfs/Git2/Search）
    // 与前台 [com.gh.dl.CloneService] 也能实时读取最新值，避免两侧状态不一致。
    // 委托使用属性引用 `by AppState::xxx`，读取时仍命中 [androidx.compose.runtime.State]
    // 快照，Compose 可正常重组。
    var custom by AppState::custom
    var token by AppState::token
    var proxy by AppState::proxy
    var channel by AppState::channel
    /** 下载加速（ghproxy 风格第三方中转文件前缀）总开关 */
    var accelOn by AppState::accelOn
    /** 浏览加速（githubfast 类网页镜像）总开关，与下载加速相互独立 */
    var browseAccelOn by AppState::browseAccelOn
    /** 浏览页静默翻译：非中文正文自动翻译并原位替换 */
    var autoTranslate by AppState::autoTranslate
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
    /** 「DoH + 真实 IP 优选」开关（默认关闭） */
    var dnsPinningOn by AppState::dnsPinningOn
    /** 「启动时自动探活国内镜像源」开关（默认关闭） */
    var mirrorProbeOn by AppState::mirrorProbeOn
    /** libgit2 通道：https / ssh */
    var sshChannel by AppState::sshChannel

    /** 下载加速前缀列表（本地持久保存） */
    val accelPrefixes = mutableStateListOf<String>()

    /** 把内存前缀列表同步到 data 层可读的全局池，供加速前缀自动故障切换使用 */
    private fun syncAccelPool() {
        AppState.accelPool = accelPrefixes.toList()
    }

    /** 浏览加速的网页镜像站列表（本地持久保存；空列表时界面按内置默认显示） */
    val browseMirrors = mutableStateListOf<String>()

    /** 共享文件夹显示名 */
    var sharedName by mutableStateOf("")

    /** 首次进入时将设置读入内存 */
    fun initSettings(ctx: Context) {
        if (AppState.initialized) return
        val storedCustom = Settings.get(ctx, "custom")
        // 历史版本可能把带空格 / 漏写协议的前缀原样存下；读入时统一规范化，
        // 否则拼接出的 URL 非法，拦截器会让所有 GitHub 请求直接失败。
        custom = Accel.normalizePrefix(storedCustom)
        if (custom != storedCustom) Settings.put(ctx, "custom", custom)
        token = Settings.get(ctx, "token")
        AppState.gitcodeToken = Settings.get(ctx, "gitcodeToken")
        proxy = Settings.get(ctx, "proxy")
        channel = Settings.get(ctx, "channel", Channels.OFFICIAL)
        accelOn = Settings.get(ctx, "accelOn", "0") != "0"
        browseAccelOn = Settings.get(ctx, "browseAccelOn", "0") != "0"
        autoTranslate = Settings.autoTranslate(ctx)
        // 当前实现：两个「自动外联」性质的开关，默认关闭。dnsPinningOn 需同时写入 data 层的
        // Net.dnsPinningOn——Net 不读取 ViewModel，不注入会导致界面显示关闭而底层仍在运行。
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
        search.searchHistory = Settings.loadSearchHistory(ctx)
        search.githubIndexOn = Settings.get(ctx, "githubIndexOn", "1") != "0"
        sshChannel = Settings.get(ctx, "sshChannel", "https")
        cloneOn = Settings.get(ctx, "cloneOn", "1") != "0"
        sharedName = if (Store.isSet(ctx)) Store.displayName(ctx) else ""
        // 外观在 MainActivity.onCreate 中已经**同步**预读过一次（为确保首帧配色正确，
        // 不可延迟至此），此行仅将同一个值注入 Compose 状态，使后续重组也能读取。
        // 两侧均通过 Settings.themeMode() 获取，默认值只有一份。
        AppState.themeMode = Settings.themeMode(ctx)
        val storedPrefixes = Settings.loadAccelPrefixes(ctx)
        val normalizedSaved = storedPrefixes.map { Accel.normalizePrefix(it) }
            .filter { it.isNotBlank() }.distinct()
        accelPrefixes.clear()
        accelPrefixes.addAll(normalizedSaved)
        syncAccelPool()
        if (normalizedSaved != storedPrefixes) Settings.saveAccelPrefixes(ctx, normalizedSaved)
        // 旧版「添加前缀」只写了内存里的 custom，没有落盘；进程重启后 custom 变空、
        // 前缀列表却仍在，表现为「加速前缀设置过但完全不起作用」。此处自动恢复第一个；
        // 若保存列表也为空，则回落到内置前缀，避免「开关开着但永远没有前缀」。
        if (accelOn && custom.isBlank()) {
            val first = accelPrefixes.firstOrNull() ?: Accel.BUILT_IN_PREFIXES.firstOrNull()
            if (first != null) {
                custom = first
                Settings.put(ctx, "custom", custom)
            }
        }
        // channel 与加速状态保持一致（Channels.file 现在按 Accel 全局状态分流，
        // 这里同步仅为让设置页显示的通道不再与实际行为矛盾）。
        channel = if (accelOn && custom.isNotBlank()) Channels.CUSTOM else Channels.OFFICIAL
        Settings.put(ctx, "channel", channel)
        browseMirrors.clear()
        browseMirrors.addAll(Settings.loadBrowseMirrors(ctx))
        // 抓取层（data 层的 GhView）不读取 ViewModel，启动时注入列表一次，
        // 之后由界面侧的 setBrowseMirrors 同步，保持单一真值源。
        com.gh.dl.data.GhView.applyMirrors(browseMirrors.toList())
        // 图片缓存与外部抓取器的临时目录：均放置于 cacheDir（可随时重建、系统可自动清理）。
        // 未注入时图片缓存读写静默跳过、curl/wget 档直接不可用，功能不会崩溃，仅降级。
        val imgDir = java.io.File(ctx.cacheDir, "ghimg")
        com.gh.dl.data.GhView.imageCacheDir = imgDir
        com.gh.dl.data.Fetcher.tmpDir = java.io.File(ctx.cacheDir, "ghfetch")
        // 内置 curl / wget 的所在目录与根证书包：当前实现将两个命令行工具打包进 APK 后，
        // 抓取层需要知道从 nativeLibraryDir 查找（SELinux 仅允许在该目录执行），
        // 并且需要获取一份 PEM 证书以完成 TLS 校验（静态编译无法读取 Android 系统信任库）。
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
        // 旧密钥迁移 + 读取当前密钥：这两步需在主线程之外执行。
        // migrateLegacy 命中旧文件时会执行一次 importPem——其中包含 Keystore 加解密（耗时数十到数百毫秒），
        // refreshKey 也需列目录并读取文件；此前这两步直接跟随在 initSettings 末尾，
        // 而 initSettings 在首帧之后的 LaunchedEffect 中执行，导致磁盘 + Keystore 操作
        // 压在用户刚进入应用的时刻，表现为「进入后先卡顿再响应」。
        Threads.start("密钥迁移") {
            SshKeys.migrateLegacy(ctx)
            val a = SshKeys.active(ctx)
            MAIN.post {
                AppState.pubKey = a?.pub ?: ""
                AppState.fingerprint = a?.fp ?: ""
            }
        }
    }

    /** 从一段文本里提取所有合法前缀（自动补协议、去尾斜杠、去重） */
    fun parsePrefixes(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (line in text.lines()) {
            val p = Accel.normalizePrefix(line)
            if (p.isNotBlank()) out.add(p)
        }
        return out.toList()
    }

    /**
     * 统一的前缀写入入口：规范化 → 落盘 → 同步通道 → 重建客户端。
     *
     * 所有「填入 / 添加 / 切换前缀」的界面动作都必须走这里。
     * 此前「添加前缀」对话框只改了内存里的 [custom]，没有写 SharedPreferences，
     * 进程重启后加速前缀恢复为空，是「加速前缀突然完全不可用」的直接原因。
     */
    fun activatePrefix(ctx: Context, raw: String) {
        val p = Accel.normalizePrefix(raw)
        custom = p
        Settings.put(ctx, "custom", p)
        channel = if (p.isNotBlank() && accelOn) Channels.CUSTOM else Channels.OFFICIAL
        Settings.put(ctx, "channel", channel)
        Net.rebuild()
    }

    /** 输入框编辑：保留用户正在输入的原文，但立即落盘并同步通道状态 */
    fun onCustomEdited(ctx: Context, raw: String) {
        custom = raw
        Settings.put(ctx, "custom", raw)
        val usable = Accel.normalizePrefix(raw).isNotBlank()
        channel = if (accelOn && usable) Channels.CUSTOM else Channels.OFFICIAL
        Settings.put(ctx, "channel", channel)
        // 输入过程中不重建 OkHttp 客户端：拦截器每次都读取最新 AppState，
        // 每敲一个字重建一次只会带来无谓开销。
    }

    /** 切换下载加速；开启时若尚无前缀，自动启用已保存的第一个 */
    fun setAccelEnabled(ctx: Context, on: Boolean) {
        accelOn = on
        Settings.put(ctx, "accelOn", if (on) "1" else "0")
        if (on && Accel.normalizePrefix(custom).isBlank()) {
            val first = accelPrefixes.firstOrNull() ?: Accel.BUILT_IN_PREFIXES.firstOrNull()
            if (first != null) {
                activatePrefix(ctx, first)
                return
            }
        }
        channel = if (on && Accel.normalizePrefix(custom).isNotBlank()) Channels.CUSTOM
        else Channels.OFFICIAL
        Settings.put(ctx, "channel", channel)
        Net.rebuild()
    }

    /** 追加一批前缀并本地持久保存（规范化、去重、忽略空白） */
    fun addAccelPrefixes(ctx: Context, list: List<String>) {
        for (raw in list) {
            val t = Accel.normalizePrefix(raw)
            if (t.isNotBlank() && accelPrefixes.none { it.equals(t, true) }) {
                accelPrefixes.add(t)
            }
        }
        Settings.saveAccelPrefixes(ctx, accelPrefixes.toList())
        syncAccelPool()
        // 首次添加时直接生效并落盘（旧实现只写内存，重启即丢失）
        if (custom.isBlank() && accelPrefixes.isNotEmpty()) {
            activatePrefix(ctx, accelPrefixes.first())
        }
    }

    /** 删除单个前缀并持久保存 */
    fun removeAccelPrefix(ctx: Context, p: String) {
        accelPrefixes.remove(p)
        Settings.saveAccelPrefixes(ctx, accelPrefixes.toList())
        syncAccelPool()
    }

    /** 清空所有前缀并持久保存 */
    fun clearAccelPrefixes(ctx: Context) {
        accelPrefixes.clear()
        Settings.saveAccelPrefixes(ctx, emptyList())
        syncAccelPool()
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
     * 导出「直链」：将原始链接经过通道改写后复制到剪贴板，供第三方下载器高速下载。
     */
    fun exportLink(ctx: Context, rawUrl: String) {
        val url = Channels.file(channel, custom, rawUrl)
        copyText(ctx, url, "直链已复制")
    }

    /** 分享直链：调起系统选择器，将最终 URL 发送给第三方下载器 */
    fun shareLink(ctx: Context, rawUrl: String) {
        val url = Channels.file(channel, custom, rawUrl)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        launchExternal(ctx, Intent.createChooser(i, "使用其他下载器打开直链"), "未找到可接收链接的应用")
    }
}
