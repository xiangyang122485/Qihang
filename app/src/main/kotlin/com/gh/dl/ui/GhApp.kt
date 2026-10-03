package com.gh.dl.ui

// 本文件在阶段1瘦身（解析页 composable 迁至 ui/parser/ParserTab.kt）；
// 阶段2 在此创建 4 个按页 ViewModel 并通过 CompositionLocalProvider 暴露给整棵 Compose 树。
import com.gh.dl.ui.parser.AddLinkDialog
import com.gh.dl.ui.parser.ParseDialog
import com.gh.dl.ui.parser.ParserTab
import com.gh.dl.data.MirrorSources
import com.gh.dl.data.Settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GhApp(initialLink: String?) {
    val ctx = LocalContext.current

    // 加速快捷按钮的 Snackbar 宿主 + 协程作用域（单击切换时弹提示）
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 阶段2：4 个按页 ViewModel，owner = MainActivity，配置变更（旋转等）不重建、状态不丢
    val settingsVM: SettingsViewModel = viewModel()
    val downloadsVM: DownloadsViewModel = viewModel()
    val parserVM: ParserViewModel = viewModel()
    val searchVM: SearchViewModel = viewModel()
    // 互注跨页引用：解析/搜索/下载动作需要共享的下载通道配置；打包/批量下载需要下载列表
    settingsVM.parser = parserVM
    settingsVM.search = searchVM
    parserVM.settings = settingsVM
    parserVM.downloads = downloadsVM
    downloadsVM.settings = settingsVM
    downloadsVM.parser = parserVM
    searchVM.settings = settingsVM

    // 阶段 3：设置页 NavHost 的 NavController，与 Activity 同生命周期（切 Tab / 旋转不重建、返回栈不丢）
    val settingsNav = rememberNavController()

    LaunchedEffect(Unit) {
        settingsVM.initSettings(ctx)
        downloadsVM.loadDownloads(ctx)
        // 首次启动（未走过引导）→ 弹全屏引导页（Task 7）
        if (Settings.get(ctx, "intro_done", "0") != "1") AppState.showIntro = true
        if (!initialLink.isNullOrBlank() && parserVM.input.isBlank()) {
            parserVM.input = initialLink
            parserVM.doLoad(ctx, initialLink)
        }
        // 启动后探活 9 个国内镜像源（HEAD 测延迟），结果写进 AppState 供诊断页 / 解析器下拉使用
        Thread {
            val r = MirrorSources.probeAll()
            AppState.mirrorProbe = r
            AppState.mirrorProbed = true
        }.start()
    }

    CompositionLocalProvider(
        LocalParserVM provides parserVM,
        LocalDownloadsVM provides downloadsVM,
        LocalSearchVM provides searchVM,
        LocalSettingsVM provides settingsVM,
        LocalSettingsNav provides settingsNav,
    ) {
        // 阶段 3：返回键由「设置子页 → 设置根列表」与「切回解析器 Tab / 退出」两段组成。
        // 设置子页的逐级返回交给 NavHost 内部的 BackHandler（在设置页内消费，回到根列表为止）；
        // 这里只负责「非解析器 Tab → 回解析器」与「解析器 Tab → 退出」。
        BackHandler(enabled = AppState.tab != 0) {
            AppState.tab = 0
        }

        val title = when (AppState.tab) {
            0 -> "解析器"
            1 -> "下载"
            2 -> "搜索"
            else -> "设置"
        }
        val running = downloadsVM.downloads.count { it.running } + (if (CloneJob.running) 1 else 0)

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, fontWeight = FontWeight.Bold) },
                    actions = {
                        if (AppState.tab == 0) {
                            AccelQuickButton(
                                ctx = ctx,
                                settingsVM = settingsVM,
                                snackbarHostState = snackbarHostState,
                                scope = scope
                            )
                            if (parserVM.parserMultiSelect) {
                                TextButton(onClick = { parserVM.toggleParserAll() }) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("全选", fontSize = 13.sp)
                                }
                                TextButton(onClick = { parserVM.batchDownloadParser(ctx) }) {
                                    Icon(Icons.Filled.Download, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("下载(${parserVM.parserSel.size})", fontSize = 13.sp)
                                }
                                TextButton(onClick = {
                                    parserVM.parserMultiSelect = false
                                    parserVM.parserSel.clear()
                                }) {
                                    Icon(Icons.Filled.Close, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("取消", fontSize = 13.sp)
                                }
                            } else {
                                TextButton(onClick = {
                                    parserVM.parserSel.clear()
                                    parserVM.parserMultiSelect = true
                                }) {
                                    Icon(Icons.Filled.Download, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("批量下载", fontSize = 13.sp)
                                }
                                IconButton(onClick = { parserVM.showAddLink = true }) {
                                    Box(
                                        Modifier
                                            .size(30.dp)
                                            .background(MaterialTheme.colorScheme.secondary, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = "添加链接",
                                            tint = MaterialTheme.colorScheme.onSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                IconButton(onClick = { parserVM.refreshParser(ctx) }) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = "刷新",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (AppState.tab == 2) {
                            AccelQuickButton(
                                ctx = ctx,
                                settingsVM = settingsVM,
                                snackbarHostState = snackbarHostState,
                                scope = scope
                            )
                            IconButton(onClick = { searchVM.refreshSearch(ctx) }) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "刷新",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = AppState.tab == 0,
                        onClick = { AppState.tab = 0 },
                        icon = {
                            Icon(
                                if (AppState.tab == 0) Icons.Filled.Home else Icons.Outlined.Home,
                                contentDescription = null
                            )
                        },
                        label = { Text("解析器", fontSize = 12.sp) }
                    )
                    NavigationBarItem(
                        selected = AppState.tab == 1,
                        onClick = { AppState.tab = 1 },
                        icon = {
                            BadgedBox(badge = {
                                if (running > 0) Badge {
                                    Text("$running", color = MaterialTheme.colorScheme.onError,
                                        style = LocalTextStyle.current.copy(fontSize = 11.sp))
                                }
                            }) {
                                Icon(
                                    if (AppState.tab == 1) Icons.Filled.Download else Icons.Outlined.Download,
                                    contentDescription = null
                                )
                            }
                        },
                        label = { Text("下载", fontSize = 12.sp) }
                    )
                    NavigationBarItem(
                        selected = AppState.tab == 2,
                        onClick = { AppState.tab = 2 },
                        icon = {
                            Icon(
                                if (AppState.tab == 2) Icons.Filled.Search else Icons.Outlined.Search,
                                contentDescription = null
                            )
                        },
                        label = { Text("搜索", fontSize = 12.sp) }
                    )
                    NavigationBarItem(
                        selected = AppState.tab == 3,
                        onClick = {
                            AppState.tab = 3
                            // 从底栏进入设置：清掉历史返回栈，回到根列表页（NavHost 未挂载时 popBackStack 安全返回 false）
                            settingsNav.popBackStack(SettingsRoute.Root.route, inclusive = false)
                        },
                        icon = {
                            Icon(
                                if (AppState.tab == 3) Icons.Filled.Settings else Icons.Outlined.Settings,
                                contentDescription = null
                            )
                        },
                        label = { Text("设置", fontSize = 12.sp) }
                    )
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (AppState.tab) {
                    0 -> ParserTab()
                    1 -> DownloadsTab()
                    2 -> SearchTab()
                    else -> SettingsTab()
                }
            }
        }

        if (parserVM.showAddLink) AddLinkDialog()
        if (parserVM.showParseDialog) ParseDialog()
    }

    // 首次启动全屏引导页（覆盖在 Tab 之上，不阻断其初始化）
    if (AppState.showIntro) {
        IntroPage(onDone = {
            Settings.put(ctx, "intro_done", "1")
            AppState.showIntro = false
        })
    }
}

/**
 * 解析器页 / 搜索页 AppBar 右侧的「加速」快捷按钮：
 *  - 单击：切换加速全局开关（立即持久化 SharedPreferences），两页读同一份状态自动同步；
 *           弹短 Snackbar 提示当前前缀或「已关闭」。
 *  - 长按：弹出已保存前缀列表，当前前缀打勾；点其它前缀切为当前并自动开加速；
 *           底部「管理加速前缀…」入口跳设置网络页；列表为空则提示去设置添加。
 * 图标态：加速真正可用（开关开 + 有前缀）→ 亮色 + 圆形背景；否则灰色、无背景。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AccelQuickButton(
    ctx: Context,
    settingsVM: SettingsViewModel,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val on = settingsVM.accelOn && settingsVM.custom.isNotBlank()
    val prefix = settingsVM.custom

    Box(
        Modifier
            .size(44.dp)
            .combinedClickable(
                onClick = {
                    val nv = !settingsVM.accelOn
                    settingsVM.accelOn = nv
                    Settings.put(ctx, "accelOn", if (nv) "1" else "0")
                    val msg = if (nv) {
                        if (prefix.isBlank()) "加速已开启，但未设置前缀，去设置添加"
                        else "加速已开启，当前前缀：$prefix"
                    } else "加速已关闭"
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                },
                onLongClick = { menuExpanded = true }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(
                    if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.FlashOn,
                contentDescription = "加速开关",
                tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
        val prefixes = settingsVM.accelPrefixes
            if (prefixes.isEmpty()) {
            DropdownMenuItem(
                text = { Text("还没有保存的前缀，去设置添加", fontSize = 13.sp) },
                onClick = {
                    menuExpanded = false
                    // 跨 Tab 跳转走 deep-link：先切到设置 Tab，待 SettingsTab 挂载后由它消费并进入网络页，
                    // 避免在设置 NavHost 尚未挂载时直接 navigate 抛「Navigation graph has not been set」。
                    AppState.tab = 3
                    AppState.settingsDeepLink = "network"
                }
            )
        } else {
            prefixes.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p == prefix) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Spacer(Modifier.width(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(p, fontSize = 13.sp)
                        }
                    },
                    onClick = {
                        menuExpanded = false
                        settingsVM.custom = p
                        settingsVM.accelOn = true
                        Settings.put(ctx, "custom", p)
                        Settings.put(ctx, "accelOn", "1")
                        scope.launch { snackbarHostState.showSnackbar("已切换前缀：$p") }
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text("管理加速前缀…", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                },
                onClick = {
                    menuExpanded = false
                    AppState.tab = 3
                    AppState.settingsDeepLink = "network"
                }
            )
        }
    }
}
