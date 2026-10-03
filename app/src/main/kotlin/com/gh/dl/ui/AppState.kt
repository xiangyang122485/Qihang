package com.gh.dl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gh.dl.data.Channels
import com.gh.dl.data.Jsd
import com.gh.dl.data.MirrorSources

/**
 * 解析器页「批量下载」多选时，一个可勾选的下载项。
 */
/**
 * 解析器页「批量下载」多选时，一个可勾选的下载项。
 * @param size 文件真实大小（字节），来自 GitHub API（Release 资产 / jsDelivr 文件）。
 *             已知时一路透传给下载引擎作为进度分母，从而显示真实百分比；
 *             源码 ZIP（codeload chunked 无 Content-Length）则保持 -1，走「总大小未知」分支。
 */
data class SelItem(
    val url: String,
    val name: String,
    val sha: String? = null,
    val size: Long = -1L
)

/**
 * 克隆任务状态（进程级单例）。
 *
 * 放在全局而非 ViewModel：克隆由前台 [com.gh.dl.CloneService] 执行，Service 脱离 Compose 树，
 * 拿不到 ViewModel / CompositionLocal；且克隆进度需在切 Tab、切 App 后持续可见。
 */
object CloneJob {
    var running by mutableStateOf(false)
    var slug by mutableStateOf("")
    var stage by mutableStateOf("")
    var bytes by mutableStateOf(0L)
    var objects by mutableStateOf(0)
    var percent by mutableStateOf(-1f)
    var speed by mutableStateOf(0L)
    var result by mutableStateOf("")
    var ok by mutableStateOf(false)
    var startedAt by mutableStateOf(0L)
}

/** 一条 Release 文件的下载任务 */
class DlJob(val id: Long, val name: String) {
    var running by mutableStateOf(false)
    var done by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var msg by mutableStateOf("")
    var bytes by mutableStateOf(0L)
    var total by mutableStateOf(-1L)
    var speed by mutableStateOf(0L)
    var where by mutableStateOf("")
    var url by mutableStateOf("")
    var officialSha by mutableStateOf<String?>(null)
    var sha by mutableStateOf("")
    var verify by mutableStateOf("")
    var startedAt by mutableStateOf(System.currentTimeMillis())
    var paused by mutableStateOf(false)
    var cancelled by mutableStateOf(false)
    var canceller by mutableStateOf<(() -> Unit)?>(null)
    var kind by mutableStateOf("file")
    var packSlug by mutableStateOf("")
    var packRef by mutableStateOf("")
    var packFiles: List<Jsd.Entry>? = null
}

/**
 * 全局导航态 + 进程级 SSH 线路态。
 *
 * 阶段 2 后，页面业务状态已按页迁入 4 个 ViewModel（Parser/Downloads/Search/Settings），
 * 这里只保留：
 *  - [tab]：底部四个主 Tab 的当前位置（根 [GhApp] 读写）；
 *  - [settingsDeepLink]：跨 Tab 跳转到设置子页时携带的「待消费初始路由」
 *    （解析页「线路诊断」、克隆卡「去配置 SSH」）。设置 NavHost 挂载后由 [com.gh.dl.ui.SettingsTab]
 *    消费并复位，避免 NavHost 未挂载就直接 navigate 抛错；正常 Tab 内导航不经由它；
 *  - [initialized]：启动初始化一次性标志；
 *  - pickedIp / ipInfo / lineBusy / pubKey / fingerprint：SSH 线路优选与钥匙展示，
 *    由前台 [com.gh.dl.CloneService] 直接读写，无法走 ViewModel，故留在全局。
 */
object AppState {
    var tab by mutableStateOf(0)
    /** 跨 Tab 跳转设置子页的待消费目标（key 字符串，见 [String.toSettingsRoute]）；消费后置 null */
    var settingsDeepLink by mutableStateOf<String?>(null)
    var initialized by mutableStateOf(false)

    /** 外观模式：system / light / dark。由「设置 → 外观」写入，[GhTheme] 实时读取并切换配色，无需重启 */
    var themeMode by mutableStateOf("system")

    /** 国内源探活结果（启动后填充）；[mirrorProbed] 表示是否已探过一次 */
    var mirrorProbe by mutableStateOf<List<MirrorSources.Probe>>(emptyList())
    var mirrorProbed by mutableStateOf(false)

    /** 下载前台服务用的实时状态（供通知栏刷新）。[activeDownloads] 归零时服务自动收尾 */
    var activeDownloads by mutableStateOf(0)
    var dlNotiName by mutableStateOf("")
    var dlNotiBytes by mutableStateOf(0L)
    var dlNotiTotal by mutableStateOf(-1L)
    var dlNotiOk by mutableStateOf(true)

    /** 未授权 SAF 下载位置时，触发「请先选择文件夹」引导（Task 4） */
    var needAuthGuide by mutableStateOf(false)

    /** 首次启动全屏引导页：未走过引导时为 true（Task 7） */
    var showIntro by mutableStateOf(false)

    // ---- 下载通道 / 克隆 / 加速 等全局配置 ----
    // 说明：这些字段同时被脱离 Compose 的 data 层（Git/Lfs/Git2/Search，以及前台
    // [com.gh.dl.CloneService]）直接读取，无法走 ViewModel。阶段2 中它们由
    // [SettingsViewModel] 持有并写入，这里保留为「全局真值源」供非界面消费者读取；
    // SettingsViewModel 的对应属性以 get/set 委托到此处，保证 UI 改动与 data 层读取一致。
    var custom by mutableStateOf("")
    var token by mutableStateOf("")
    /** GitCode（gitcode.com）访问令牌；留空时国内源搜索跳过 GitCode（避免无 token 直发 401） */
    var gitcodeToken by mutableStateOf("")
    var proxy by mutableStateOf("")
    var channel by mutableStateOf(Channels.OFFICIAL)
    var accelOn by mutableStateOf(false)
    var verifyOn by mutableStateOf(true)
    var lfsOn by mutableStateOf(true)
    var engine by mutableStateOf("libgit2")
    var dlEngine by mutableStateOf("aria2")
    var cloneOn by mutableStateOf(true)
    var sshChannel by mutableStateOf("https")

    /** SSH 线路优选结果 */
    var pickedIp by mutableStateOf("")
    var ipInfo by mutableStateOf("")
    var lineBusy by mutableStateOf(false)

    /** SSH 钥匙（展示用） */
    var pubKey by mutableStateOf("")
    var fingerprint by mutableStateOf("")
}
