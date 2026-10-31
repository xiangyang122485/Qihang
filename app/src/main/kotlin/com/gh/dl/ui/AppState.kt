package com.gh.dl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
 *             已知时透传给下载引擎作为进度分母，从而显示真实百分比；
 *             源码 ZIP（codeload chunked 无 Content-Length）保持 -1，走「总大小未知」分支。
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
 * 放置于全局而非 ViewModel 的原因：克隆由前台 [com.gh.dl.CloneService] 执行，Service 脱离 Compose 树，
 * 无法获取 ViewModel / CompositionLocal；且克隆进度需在切换 Tab、切换应用后持续可见。
 */
object CloneJob {
    var running by mutableStateOf(false)
    var slug by mutableStateOf("")
    var stage by mutableStateOf("")
    var bytes by mutableLongStateOf(0L)
    var objects by mutableIntStateOf(0)
    var percent by mutableFloatStateOf(-1f)
    var speed by mutableLongStateOf(0L)
    var result by mutableStateOf("")
    var ok by mutableStateOf(false)
    var startedAt by mutableLongStateOf(0L)
}

/** 一条 Release 文件的下载任务 */
class DlJob(val id: Long, val name: String) {
    var running by mutableStateOf(false)
    var done by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var msg by mutableStateOf("")
    var bytes by mutableLongStateOf(0L)
    var total by mutableLongStateOf(-1L)
    var speed by mutableLongStateOf(0L)
    var where by mutableStateOf("")
    /** 落盘文件的可访问 URI（SAF 文档或 FileProvider），供「安装 / 打开」使用；旧记录为空 */
    var whereUri by mutableStateOf("")
    var url by mutableStateOf("")
    var officialSha by mutableStateOf<String?>(null)
    var sha by mutableStateOf("")
    var verify by mutableStateOf("")
    var startedAt by mutableLongStateOf(System.currentTimeMillis())
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
 * 此处仅保留：
 *  - [tab]：底部四个主 Tab 的当前位置（根 [GhApp] 读写）；
 *  - [settingsDeepLink]：跨 Tab 跳转到设置子页时携带的「待消费初始路由」
 *    （解析页「线路诊断」、克隆卡「去配置 SSH」）。设置 NavHost 挂载后由 [com.gh.dl.ui.SettingsTab]
 *    消费并复位，避免 NavHost 未挂载时直接 navigate 导致异常；常规 Tab 内导航不经过此字段；
 *  - [initialized]：启动初始化一次性标志；
 *  - pickedIp / ipInfo / lineBusy / pubKey / fingerprint：SSH 线路优选与密钥展示，
 *    由前台 [com.gh.dl.CloneService] 直接读写，无法通过 ViewModel，故保留在全局。
 */
object AppState {

    /**
     * 底部主 Tab 序号，统一在此定义：各处使用常量而非字面量，新增 Tab 时无需逐处修改。
     * 顺序与底栏一致：解析器 · 浏览 · 下载 · 搜索 · 设置。
     */
    const val TAB_PARSER = 0
    const val TAB_BROWSE = 1
    const val TAB_DOWNLOADS = 2
    const val TAB_SEARCH = 3
    const val TAB_SETTINGS = 4

    var tab by mutableIntStateOf(TAB_PARSER)
    /** 跨 Tab 跳转设置子页的待消费目标（key 字符串，见 [String.toSettingsRoute]）；消费后置 null */
    var settingsDeepLink by mutableStateOf<String?>(null)

    /**
     * 「应用已在前台时再次收到分享 / 深链」的投递槽：由 MainActivity.onNewIntent 写入，
     * GhApp 消费后置空。使用 null 区分「无新链接」，避免重复解析同一链接。
     */
    var pendingLink by mutableStateOf<String?>(null)
    var initialized by mutableStateOf(false)

    /**
     * 外观模式：system / light / dark。由「设置 → 外观」写入，[com.gh.dl.ui.theme.GhTheme]
     * 实时读取并切换配色，无需重启。
     *
     * 当前实现将初值从 "system" 改为 **"light"**：此处的值会在启动早期被
     * [com.gh.dl.ui.SettingsViewModel.initSettings] 用磁盘上的设置覆盖，但覆盖发生在
     * 第一帧**之后**，因此初值即为「用户尚未存储设置时看到的外观」。
     * 初值为 system 时，系统深色模式下第一帧按深色渲染，initSettings 注入后
     * 才切换为用户设置，中间出现一次可见的颜色变化，故初值取浅色。
     * 与 [com.gh.dl.data.Settings.THEME_DEFAULT] 必须保持一致。
     */
    var themeMode by mutableStateOf(com.gh.dl.data.Settings.THEME_DEFAULT)

    /**
     * 国内源探活结果（启动后填充）；[mirrorProbed] 表示是否已探过一次。
     *
     * **约定：仅在主线程写入。**探活在 Thread 中执行（九个 HEAD 请求），但结果统一通过 MAIN.post
     * 回到主线程再赋值（GhApp、DiagPage 两个调用点均如此处理）。
     * 跨线程写入 `by mutableStateOf` 虽然 Compose 允许（会产生一条警告），但本项目的界面状态
     * 写入线程统一为「主线程」，混入后台直写会导致问题难以定位；
     * 当前实现排查时这两处正是「裸 Thread 直接写入 AppState」的遗漏。
     */
    var mirrorProbe by mutableStateOf<List<MirrorSources.Probe>>(emptyList())
    var mirrorProbed by mutableStateOf(false)

    /** 下载前台服务用的实时状态（供通知栏刷新）。[activeDownloads] 归零时服务自动收尾 */
    var activeDownloads by mutableIntStateOf(0)
    var dlNotiName by mutableStateOf("")
    var dlNotiBytes by mutableLongStateOf(0L)
    var dlNotiTotal by mutableLongStateOf(-1L)
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
    /** GitCode（gitcode.com）访问令牌；留空时国内源搜索跳过 GitCode（避免无令牌直接发送导致 401） */
    var gitcodeToken by mutableStateOf("")
    var proxy by mutableStateOf("")
    var channel by mutableStateOf(Channels.OFFICIAL)
    /**
     * 下载加速（ghproxy 风格**文件**前缀）总开关，由顶栏加速按钮控制。
     * 与 [browseAccelOn] 为两套独立开关：前缀无法代理网页（返回其自身首页），
     * 因此浏览必须单独设为一档，详见 [browseAccelOn] 与 [com.gh.dl.data.GhView] 的说明。
     */
    var accelOn by mutableStateOf(false)
    /**
     * 浏览加速（githubfast.com 这类**网页**镜像）总开关，仅在「设置 → 加速」和浏览页顶栏生效。
     * 开启时 = 抓取优先走网页镜像、失败后直连 GitHub；关闭时 = 仅直连
     *（当前实现 DoH 优选也为独立开关且默认关闭，因此关闭时为纯系统 DNS 直连）。
     *
     * 本档默认关闭（false）：
     * 此档位会使页面 HTML 先经过第三方镜像站，属于「将访问内容交由中转」，
     * 出厂状态必须为关闭，由用户决定是否开启，与使用文档「均默认关闭」的描述一致。
     */
    var browseAccelOn by mutableStateOf(false)
    /**
     * 静默翻译：浏览页抓取到非中文正文时自动翻译，并把译文直接写回原页面。
     * 默认关闭（false）：翻译会把正文文字发送给第三方公开翻译接口，
     * 是否自动外联应由用户决定。关闭时仍可通过顶栏翻译按钮手动触发。
     */
    var autoTranslate by mutableStateOf(false)
    /**
     * 「DoH + 真实 IP 优选」开关（当前实现，默认 **false**）。
     * 开启后才会向第三方公共 DoH（dns.alidns.com / doh.pub）查询域名、并自动连接候选 IP。
     * 真实值同时写入 [com.gh.dl.data.Net.dnsPinningOn]，因为 data 层不读取 ViewModel。
     */
    var dnsPinningOn by mutableStateOf(false)
    /**
     * 「启动时自动探活 9 个国内镜像源」开关（当前实现，默认 **false**）。
     * 关闭时启动不发送任何请求；如需查看延迟，可前往「线路诊断」手动点击「立即探活」。
     */
    var mirrorProbeOn by mutableStateOf(false)
    /**
     * 浏览的 HTML 解析引擎，取值见 [com.gh.dl.data.GhView] 的 ENGINE_* 常量。
     * 默认 Jsoup HTML5：自带 CSS 选择器且容错能力最好，
     * 其他引擎仅在特定场景下更快，因此设为可选项而非更改默认值。
     */
    var parseEngine by mutableStateOf("jsoup")
    /** 浏览页图片的显示方式，取值见 [com.gh.dl.data.GhView] 的 IMAGE_* 常量 */
    var imageMode by mutableStateOf("local")
    /**
     * 浏览的抓取器：内置 OkHttp（默认）或本机命令行工具。
     * 同一页面 curl 抓取 0.9s、应用抓取 1.4s，耗时差异主要在取图阶段，
     * 与 HTTP 客户端选择无关。curl / wget 作为可选抓取器保留（见 [com.gh.dl.data.Fetcher]），
     * 因为部分机型上系统 curl 使用自身的 DNS/代理配置，反而比应用直连更快。
     */
    var fetcher by mutableStateOf("okhttp")
    /**
     * 解析器页是否记录历史链接（可在「设置 → 浏览」中关闭）。
     * 当前实现改为**默认关闭**：此功能在手机本地留存「用户浏览过哪些仓库」的记录，
     * 属于敏感项，出厂不应自动记录；需由用户自行开启。
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

    /** SSH 密钥（展示用） */
    var pubKey by mutableStateOf("")
    var fingerprint by mutableStateOf("")
}
