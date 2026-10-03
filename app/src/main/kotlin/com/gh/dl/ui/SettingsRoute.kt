package com.gh.dl.ui

/**
 * 设置页的类型安全路由。
 *
 * 阶段 4（设置 Tab 去重重构）把一级页压成 4 个分组卡片（网络 / 下载 / 克隆 / 关于），
 * 每个分组点进去是一个二级页；SSH 密钥管理从「克隆」分组进入，线路诊断从「网络」分组进入。
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

    /** 网络：线路选择 / 加速代理 / GitHub Token / HTTP 代理 / 线路诊断入口 */
    data object Network : SettingsRoute { override val route = "settings_network" }

    /** 下载：下载引擎 / 存储位置(SAF) / 缓存占用与清除 */
    data object Download : SettingsRoute { override val route = "settings_download" }

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
    "permissions" -> SettingsRoute.Permissions
    "clone" -> SettingsRoute.Clone
    "network" -> SettingsRoute.Network
    "download" -> SettingsRoute.Download
    else -> SettingsRoute.Root
}
