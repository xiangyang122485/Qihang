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

    /**
     * 底部主 Tab 序号。以前各处直接写 0/1/2/3 字面量，在中间插入一个新 Tab
     * （「浏览」）就得逐处改、极易漏改，所以统一收成常量，只在这里定义一次。
     * 顺序与底栏一致：解析器 · 浏览 · 下载 · 搜索 · 设置。
     */
    const val TAB_PARSER = 0
    const val TAB_BROWSE = 1
    const val TAB_DOWNLOADS = 2
    const val TAB_SEARCH = 3
    const val TAB_SETTINGS = 4

    var tab by mutableStateOf(TAB_PARSER)
    /** 跨 Tab 跳转设置子页的待消费目标（key 字符串，见 [String.toSettingsRoute]）；消费后置 null */
    var settingsDeepLink by mutableStateOf<String?>(null)

    /**
     * 「App 已经在前台时又收到分享 / 深链」的投递槽：MainActivity.onNewIntent 写入，
     * GhApp 消费后置空。用 null 区分「没有新链接」，避免重复解析同一个链接。
     */
    var pendingLink by mutableStateOf<String?>(null)
    var initialized by mutableStateOf(false)

    /**
     * 外观模式：system / light / dark。由「设置 → 外观」写入，[com.gh.dl.ui.theme.GhTheme]
     * 实时读取并切换配色，无需重启。
     *
     * 第八轮把初值从 "system" 改成 **"light"**：这里的值会在启动早期被
     * [com.gh.dl.ui.SettingsViewModel.initSettings] 用磁盘上的设置覆盖，但覆盖发生在
     * 第一帧**之后**，所以初值就是「用户还没存过设置时看到的外观」。
     * 原来初值是 system，于是系统深色时第一帧按深色渲染，等 initSettings 灌进
     * 默认值（旧默认同样是 system，值没变但触发了一次重组）或用户其实想要浅色，
     * 中间就是一次可见的翻色。用户的原话：「这个 APP 我默认是浅色的」。
     * 与 [com.gh.dl.data.Settings.THEME_DEFAULT] 必须一致。
     */
    var themeMode by mutableStateOf(com.gh.dl.data.Settings.THEME_DEFAULT)

    /**
     * 国内源探活结果（启动后填充）；[mirrorProbed] 表示是否已探过一次。
     *
     * **约定：只在主线程写。**探活在 Thread 里跑（九个 HEAD 请求），但结果一律 MAIN.post
     * 回主线程再赋值（GhApp、DiagPage 两个调用点都这么写）。
     * 跨线程写 `by mutableStateOf` 虽然 Compose 允许（会打一条警告），但本项目的界面状态
     * 写入口径统一是「主线程」，混一条后台直写进来，将来出问题极难定位；
     * 第八轮排查时这两处正是「裸 Thread 直接写 AppState」的漏网之鱼。
     */
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
    /**
     * 下载加速（ghproxy 风格**文件**前缀）总开关，顶栏闪电控制。
     * 与 [browseAccelOn] 是两套独立开关：实测前缀代理不了网页（会返回它自己的首页），
     * 所以浏览必须单独一档，见 [browseAccelOn] 与 [com.gh.dl.data.GhView] 的说明。
     */
    var accelOn by mutableStateOf(false)
    /**
     * 浏览加速（githubfast.com 这类**网页**镜像）总开关，只在「设置 → 加速」和浏览页顶栏生效。
     * 开着 = 抓取先走网页镜像、失败再直连 GitHub；关着 = 只直连
     * （第九轮起 DoH 优选也是独立开关且默认关，所以关着时就是纯系统 DNS 直连）。
     *
     * 第九轮按用户要求「敏感功能一律默认关闭」把默认值从 true 改成 **false**：
     * 这一档会让页面 HTML 先经过第三方镜像站，属于「把访问内容交给中转」，
     * 所以出厂状态必须是关，由用户自己决定开。旧默认开的时候，
     * 使用文档还写着「都默认关闭」——那句在第八轮其实是不成立的，现在才对齐。
     */
    var browseAccelOn by mutableStateOf(false)
    /**
     * 「DoH + 真实 IP 优选」开关（第九轮，默认 **false**）。
     * 打开才会向第三方公共 DoH（dns.alidns.com / doh.pub）查你的域名、并自动外连候选 IP。
     * 真值同时写进 [com.gh.dl.data.Net.dnsPinningOn]，因为 data 层不读 ViewModel。
     */
    var dnsPinningOn by mutableStateOf(false)
    /**
     * 「启动时自动探活 9 个国内镜像源」开关（第九轮，默认 **false**）。
     * 关掉时开机一个请求都不发；想看延迟就去「线路诊断」手动点「立即探活」。
     */
    var mirrorProbeOn by mutableStateOf(false)
    /**
     * 浏览的 HTML 解析引擎，取值见 [com.gh.dl.data.GhView] 的 ENGINE_* 常量。
     * 默认 Jsoup HTML5：实测它已经是「自带 CSS 选择器 + 容错最强」的那一档，
     * 别的引擎只在特定场景更快，所以做成可选而不是换默认值。
     */
    var parseEngine by mutableStateOf("jsoup")
    /** 浏览页图片的显示方式，取值见 [com.gh.dl.data.GhView] 的 IMAGE_* 常量 */
    var imageMode by mutableStateOf("local")
    /**
     * 浏览的抓取器：内置 OkHttp（默认）还是本机的命令行工具。
     * 用户问「慢是不是 curl 的问题」——实测不是：同一页 curl 抓取 0.9s、App 抓取 1.4s，
     * 慢的是取图阶段。但仍按要求把 wget / curl 接成可选抓取器（见 [com.gh.dl.data.Fetcher]），
     * 因为某些机型上系统 curl 走了自己的 DNS/代理配置，反而比 App 直连快。
     */
    var fetcher by mutableStateOf("okhttp")
    /**
     * 解析器页是否记录历史链接（第六轮新增，「设置 → 浏览」里可关）。
     * 第九轮改为**默认关闭**：这是在手机本地留存「用户看过哪些仓库」的记录，
     * 属于敏感项，出厂不该自动记；要记由用户自己开。
     */
    var historyOn by mutableStateOf(false)
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
