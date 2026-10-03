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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Book
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
import com.gh.dl.data.Net
import com.gh.dl.data.Settings
import com.gh.dl.data.Store
import com.gh.dl.data.fmtSize
import com.gh.dl.ui.settings.*

/**
 * 可折叠的说明块：默认只显示一行标题，点击才展开正文。
 * 用于把各处大段说明收起，避免占满屏幕、挡住下面的内容。
 */
@Composable
fun CollapsibleNote(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(if (open) "▾" else "▸", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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

/** 设置分组小标题：图标 + 粗体标题，让四组（网络/下载/克隆/关于）一眼分清 */
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
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}

/**
 * 「设置」Tab：阶段 4 起一级页只保留 4 个分组卡片（网络 / 下载 / 克隆 / 关于），
 * 点进去才是各自的二级页；二级页用 navigation-compose 的 NavHost 承载，返回栈由导航库管理。
 */
@Composable
fun SettingsTab() {
    val nav = LocalSettingsNav.current

    // 跨 Tab 跳转（克隆卡「去配置 SSH」等）携带的初始路由：
    // 那些跳转发生在设置 NavHost 挂载之前，先记在 AppState.settingsDeepLink，待本页挂载后消费，
    // 避免 NavHost 未提供 graph 时直接 navigate 抛错。
    LaunchedEffect(AppState.settingsDeepLink) {
        val dl = AppState.settingsDeepLink
        if (dl != null) {
            AppState.settingsDeepLink = null
            nav.popBackStack(SettingsRoute.Root.route, inclusive = false) // 清掉陈旧历史，落到 [root, 目标]
            // 注意：必须传 .route 字符串，不能传 SettingsRoute 对象——否则会命中 navigation-compose 的
            // 泛型 navigate(route: T) 重载，它依赖 kotlinx.serialization（本项目未引入该插件）而崩溃。
            nav.navigate(dl.toSettingsRoute().route)
        }
    }

    NavHost(navController = nav, startDestination = SettingsRoute.Root.route) {
        composable(SettingsRoute.Root.route) { RootPage() }
        composable(SettingsRoute.Network.route) { NetworkPage(nav, onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Download.route) { DownloadPage(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Clone.route) { ClonePage(nav, onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Ssh.route) { SshKeyScreen(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Diag.route) { DiagPage(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Mirrors.route) { MirrorManagerPage(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.About.route) { AboutPage(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Docs.route) { DocsPage(onBack = { nav.popBackStack() }) }
        composable(SettingsRoute.Appearance.route) { AppearancePage(onBack = { nav.popBackStack() }) }
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

        // ---- 网络 ----
        item { SettingsSectionHeader("网络", Icons.Filled.Wifi) }
        item {
            NavRow(
                icon = Icons.Filled.Wifi,
                title = "网络",
                subtitle = "线路选择 · 加速代理 · GitHub Token · HTTP 代理",
                onClick = { nav.navigate(SettingsRoute.Network.route) }
            )
        }

        // ---- 下载 ----
        item { SettingsSectionHeader("下载", Icons.Filled.Download) }
        item {
            NavRow(
                icon = Icons.Filled.Download,
                title = "下载",
                subtitle = "下载引擎：$dlLabel · ${if (vmSettings.sharedName.isNotBlank()) "已授权存储位置" else "未设置存储位置"}",
                onClick = { nav.navigate(SettingsRoute.Download.route) }
            )
        }

        // ---- 克隆 ----
        item { SettingsSectionHeader("克隆", Icons.Filled.AccountTree) }
        item {
            NavRow(
                icon = Icons.Filled.AccountTree,
                title = "克隆",
                subtitle = if (vmSettings.cloneOn)
                    "已开启 · ${if (vmSettings.engine == "libgit2") "libgit2" else "JGit"} · " +
                        if (vmSettings.sshChannel == "ssh") "SSH 直连" else "HTTPS"
                else "已关闭（克隆用默认方式）",
                onClick = { nav.navigate(SettingsRoute.Clone.route) }
            )
        }

        // ---- 关于 ----
        item { SettingsSectionHeader("关于", Icons.Filled.Info) }
        item {
            NavRow(
                icon = Icons.Filled.Info,
                title = "关于",
                subtitle = "合规说明 · 版本 1.0",
                onClick = { nav.navigate(SettingsRoute.About.route) }
            )
        }
        item {
            NavRow(
                icon = Icons.Filled.Book,
                title = "使用文档",
                subtitle = "怎么下载 / 克隆 / 看懂进度（本地）",
                onClick = { nav.navigate(SettingsRoute.Docs.route) }
            )
        }

        // ---- 权限 ----
        item { SettingsSectionHeader("权限", Icons.Filled.Lock) }
        item {
            NavRow(
                icon = Icons.Filled.Lock,
                title = "权限",
                subtitle = "通知 · 电池优化 · 安装未知应用 · 存储授权",
                onClick = { nav.navigate(SettingsRoute.Permissions.route) }
            )
        }

        // ---- 外观 ----
        item { SettingsSectionHeader("外观", Icons.Filled.Palette) }
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
    }
}
