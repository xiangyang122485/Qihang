package com.gh.dl.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh.dl.data.AriaEngine
import com.gh.dl.data.Channels
import com.gh.dl.data.Fetcher
import com.gh.dl.data.GhView
import com.gh.dl.data.Net
import com.gh.dl.data.Settings
import com.gh.dl.data.Store
import com.gh.dl.data.fmtSize
import com.gh.dl.ui.settings.*

/** 设置主页摘要用短标签：直接输出设置中存储的 id（jsoup / local / okhttp）用户无法理解，
 *  从二级页长描述中截取又过长，因此单独提供一份 2~4 字的简短名称。
 *  未知值原样返回，不做推测——后续新增档位时若未在此处登记，界面上仍可看到真实值。
 */

/** 解析引擎短名 */
private fun shortEngine(id: String): String = when (id) {
    GhView.ENGINE_JSOUP -> "Jsoup"
    GhView.ENGINE_XML -> "XML"
    GhView.ENGINE_REPAIR -> "先修复"
    GhView.ENGINE_FAST -> "仅正文"
    else -> id
}

/** 图片方式短名 */
private fun shortImage(id: String): String = when (id) {
    GhView.IMAGE_LOCAL -> "本地缓存"
    GhView.IMAGE_BASE64 -> "内联"
    GhView.IMAGE_OFF -> "不显示"
    else -> id
}

/** 抓取器短名。**此处不可调用 Fetcher.sourceOf()**：该方法需要 stat 多个目录，
 *  而此行在每次进入设置页时都参与组合——同类问题当前实现已在浏览设置页、
 *  下载设置页中修复过（详见各自的后台探测），主页此行同样需要避免。
 *  「实际使用的是内置还是本机那份」在设置 → 浏览页中有准确显示。 */
private fun shortFetcher(id: String): String = when (id) {
    Fetcher.OKHTTP -> "OkHttp"
    Fetcher.CURL -> "curl"
    Fetcher.WGET -> "wget"
    else -> id
}

@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    GhCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 设置分组小标题：图标 + 粗体标题，用于区分四组（网络/下载/克隆/关于） */
@Composable
private fun SettingsSectionHeader(text: String, icon: ImageVector) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun SubTitle(text: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}

/**
 * 「设置」Tab：自阶段 4 起一级页仅保留 4 个分组卡片（网络 / 下载 / 克隆 / 关于），
 * 点击进入各自的二级页；二级页由 navigation-compose 的 NavHost 承载，返回栈由导航库管理。
 */
@Composable
fun SettingsTab() {
    val nav = LocalSettingsNav.current

    // 跨 Tab 跳转（克隆卡「去配置 SSH」等）携带的初始路由：
    // 此类跳转发生在设置 NavHost 挂载之前，先记录在 AppState.settingsDeepLink 中，待本页挂载后消费，
    // 避免 NavHost 未提供 graph 时直接 navigate 导致异常。
    LaunchedEffect(AppState.settingsDeepLink) {
        val dl = AppState.settingsDeepLink
        if (dl != null) {
            AppState.settingsDeepLink = null
            nav.popBackStack(SettingsRoute.Root.route, inclusive = false) // 清除陈旧历史，落到 [root, 目标]
            // 注意：必须传递 .route 字符串，不可传递 SettingsRoute 对象——否则会命中 navigation-compose 的
            // 泛型 navigate(route: T) 重载，该重载依赖 kotlinx.serialization（本项目未引入该插件）导致崩溃。
            nav.navigate(dl.toSettingsRoute().route)
        }
    }

    NavHost(navController = nav, startDestination = SettingsRoute.Root.route) {
        composable(SettingsRoute.Root.route) { RootPage() }
        composable(SettingsRoute.Network.route) { NetworkPage(nav, onBack = { nav.popBackStack() }) }
        // 加速：二级导航页 + 两个三级页（下载加速 / 浏览加速），两套机制相互独立
        composable(SettingsRoute.Accel.route) { AccelHubPage(nav, onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.AccelDownload.route) { AccelDownloadPage(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.AccelBrowse.route) { AccelBrowsePage(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Download.route) { DownloadPage(onBack = { nav.popBackStack() }) }
        // 浏览：解析引擎 / 图片 / 抓取器 / 历史（当前实现新增，与「加速」并列而非合并）
        composable(SettingsRoute.Browse.route) { BrowseSettingsPage(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Clone.route) { ClonePage(nav, onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Ssh.route) { SshKeyScreen(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Diag.route) { DiagPage(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Mirrors.route) { MirrorManagerPage(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.About.route) { AboutPage(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Docs.route) { DocsPage(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Appearance.route) { AppearancePage(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Licenses.route) { LicensesPage(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Permissions.route) { PermissionsPage(onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun RootPage() {
    val vmSettings = LocalSettingsVM.current
    val nav = LocalSettingsNav.current

    val dlLabel = when (vmSettings.dlEngine) {
        "okdownload" -> "OkDownload 多线程"
        "aria2" -> "Aria2 原生"
        else -> "兼容单线程"
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("设置", fontWeight = FontWeight.Bold, fontSize = 20.sp) }

        // 当前实现：一级页整理为「取 / 存 / 用」三组，每组下挂二级页，二级页中再包含三级页。
        // 分组小标题与卡片标题不重复：仅保留分组名（如「下载」），卡片不带同名标题。
        // 「浏览」与「加速」为两个独立功能：加速控制「从哪个站点获取」，浏览控制「获取后如何处理」，
        // 因此「浏览」独立为一行，不并入「加速」页面。
        item { SettingsSectionHeader("取：网络 · 加速 · 浏览", Icons.Filled.Wifi) }
        item {
            NavRow(
                icon = Icons.Filled.Wifi,
                title = "网络",
                subtitle = "线路选择 · Token · HTTP 代理 · 镜像源 · 线路诊断",
                onClick = { nav.navigate(SettingsRoute.Network.route) }
            )
        }
        item {
            NavRow(
                icon = Icons.Filled.Speed,
                title = "加速",
                subtitle = "下载加速 " + (if (vmSettings.accelOn) "开" else "关") +
                    " · 浏览加速 " + (if (vmSettings.browseAccelOn) "开" else "关"),
                onClick = { nav.navigate(SettingsRoute.Accel.route) }
            )
        }

        item { SettingsSectionHeader("存：下载与克隆", Icons.Filled.Download) }
        item {
            NavRow(
                icon = Icons.Filled.Language,
                title = "浏览",
                // 此处输出与二级页一致的短标签，而非设置中存储的 id（jsoup / local / okhttp）
                subtitle = "解析 " + shortEngine(vmSettings.parseEngine) +
                    " · 图片 " + shortImage(vmSettings.imageMode) +
                    " · 抓取 " + shortFetcher(vmSettings.fetcher) +
                    " · 历史 " + (if (vmSettings.historyOn) "记录中" else "已关"),
                onClick = { nav.navigate(SettingsRoute.Browse.route) }
            )
        }
        item {
            NavRow(
                icon = Icons.Filled.Download,
                title = "下载",
                subtitle = "引擎 $dlLabel · ${if (vmSettings.sharedName.isNotBlank()) "已授权存储位置" else "未设置存储位置"}",
                onClick = { nav.navigate(SettingsRoute.Download.route) }
            )
        }
        item {
            NavRow(
                icon = Icons.Filled.AccountTree,
                title = "克隆",
                subtitle = if (vmSettings.cloneOn)
                    "已开启 · ${if (vmSettings.engine == "libgit2") "libgit2" else "JGit"} · " +
                        if (vmSettings.sshChannel == "ssh") "SSH 直连" else "HTTPS"
                else "已关闭（克隆使用默认方式）",
                onClick = { nav.navigate(SettingsRoute.Clone.route) }
            )
        }

        item { SettingsSectionHeader("用：外观 · 权限 · 说明", Icons.Filled.Palette) }
        item {
            NavRow(
                icon = Icons.Filled.Palette,
                title = "外观",
                subtitle = when (AppState.themeMode) {
                    "light" -> "浅色模式"
                    "dark" -> "深色模式"
                    else -> "跟随系统"
                },
                onClick = { nav.navigate(SettingsRoute.Appearance.route) }
            )
        }
        item {
            NavRow(
                icon = Icons.Filled.Lock,
                title = "权限",
                subtitle = "通知 · 电池优化 · 安装未知应用 · 存储授权",
                onClick = { nav.navigate(SettingsRoute.Permissions.route) }
            )
        }
        item {
            NavRow(
                icon = Icons.Filled.Book,
                title = "使用文档",
                subtitle = "下载 / 克隆 / 进度查看说明（本地）",
                onClick = { nav.navigate(SettingsRoute.Docs.route) }
            )
        }
        item {
            NavRow(
                icon = Icons.Filled.Info,
                title = "关于",
                // 版本号取自 BuildConfig（唯一真值源），避免硬编码与 Gradle 配置不同步
                subtitle = "合规说明 · 版本 " + com.gh.dl.BuildConfig.VERSION_NAME,
                onClick = { nav.navigate(SettingsRoute.About.route) }
            )
        }
        item {
            NavRow(
                icon = Icons.Filled.Policy,
                title = "开源许可",
                subtitle = "内置程序与依赖的许可证（含 GPL 全文，分发包须随附）",
                onClick = { nav.navigate(SettingsRoute.Licenses.route) }
            )
        }
    }
}
