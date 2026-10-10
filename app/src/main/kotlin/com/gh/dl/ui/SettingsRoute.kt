package com.gh.dl.ui

/**
 * 设置页的类型安全路由。
 *
 * 阶段 4（设置 Tab 去重重构）把一级页压成分组卡片，第五轮再拆成三级：
 * 设置 → 分组（网络 / 加速 / 下载 / 克隆 …） → 具体页；
 * 「加速」下面还有第三级（下载加速 / 浏览加速），因为这两套机制完全不相通：
 * 文件前缀代理不了网页（实测返回它自己的首页），网页镜像又只放行 curl 类 UA。
 * 路由字符串集中在密封接口里，杜绝把页面名写成散落各处的魔法字符串，
 * 也避免与解析/下载/搜索三个主 Tab 的 `when(AppState.tab)` 混在一起。
 *
 * 注意：刻意不使用 navigation-compose 的泛型 `composable<Route>()` 形式（那需要 kotlinx-serialization），
 * 这里用「密封接口 + 字符串路由常量」达到类型安全且不引入新依赖，符合项目「不引入新大依赖」的硬约束。
 */
sealed interface SettingsRoute {
    val route: String

    /** 设置根列表页（NavHost 起始 destination）：4 个分组卡片 */
    data object Root : SettingsRoute { override val route = "settings_root" }

    /** 网络：线路选择 / GitHub Token / HTTP 代理 / 镜像源管理 / 线路诊断入口 */
    data object Network : SettingsRoute { override val route = "settings_network" }

    /** 加速：一级入口页，下面挂「下载加速」与「浏览加速」两个三级页 */
    data object Accel : SettingsRoute { override val route = "settings_accel" }

    /** 下载加速（ghproxy 风格文件前缀）：开关 + 前缀增删 + 下载后 SHA256 校验 */
    data object AccelDownload : SettingsRoute { override val route = "settings_accel_download" }

    /** 浏览加速（githubfast 风格网页镜像站）：开关 + 镜像站增删 + 连通性实测 */
    data object AccelBrowse : SettingsRoute { override val route = "settings_accel_browse" }

    /** 下载：下载引擎 / 存储位置(SAF) / 缓存占用与清除 */
    data object Download : SettingsRoute { override val route = "settings_download" }

    /** 浏览：解析引擎 / 图片方式 / 抓取器 / 链接历史 / 图片缓存（第六轮新增） */
    data object Browse : SettingsRoute { override val route = "settings_browse" }

    /** 克隆：克隆总开关 / Git LFS / 克隆引擎 / SSH 密钥管理入口 */
    data object Clone : SettingsRoute { override val route = "settings_clone" }

    /** SSH 密钥管理（仅从「克隆」分组进入） */
    data object Ssh : SettingsRoute { override val route = "settings_ssh" }

    /** 线路诊断（仅从「网络」分组进入） */
    data object Diag : SettingsRoute { override val route = "settings_diag" }

    /** 镜像源管理（导入/删除/测速/设默认，可管理清华·华为云等高校镜像） */
    data object Mirrors : SettingsRoute { override val route = "settings_mirrors" }

    /** 关于与合规说明 */
    data object About : SettingsRoute { override val route = "settings_about" }

    /** 使用文档（纯本地、无网络） */
    data object Docs : SettingsRoute { override val route = "settings_docs" }

    /** 外观：跟随系统 / 浅色 / 深色 */
    data object Appearance : SettingsRoute { override val route = "settings_appearance" }

    /** 开源许可：内置二进制 + JVM 依赖的许可证清单与全文（第九轮新增） */
    data object Licenses : SettingsRoute { override val route = "settings_licenses" }

    /** 权限：通知 / 电池优化 / 安装未知应用 / 存储授权（SAF） */
    data object Permissions : SettingsRoute { override val route = "settings_permissions" }
}

/**
 * 把旧的「页面 key 字符串」映射到类型安全路由，用于跨 Tab 跳转时携带的初始目标
 * （[AppState.settingsDeepLink]）。未知值回退到 [SettingsRoute.Root] 以免导航崩溃。
 */
fun String.toSettingsRoute(): SettingsRoute = when (this) {
    "ssh" -> SettingsRoute.Ssh
    "diag" -> SettingsRoute.Diag
    "mirrors" -> SettingsRoute.Mirrors
    "about" -> SettingsRoute.About
    "docs" -> SettingsRoute.Docs
    "appearance" -> SettingsRoute.Appearance
    "licenses" -> SettingsRoute.Licenses
    "permissions" -> SettingsRoute.Permissions
    "clone" -> SettingsRoute.Clone
    "network" -> SettingsRoute.Network
    "accel" -> SettingsRoute.Accel
    "accelDownload" -> SettingsRoute.AccelDownload
    "accelBrowse" -> SettingsRoute.AccelBrowse
    "download" -> SettingsRoute.Download
    "browse" -> SettingsRoute.Browse
    else -> SettingsRoute.Root
}
