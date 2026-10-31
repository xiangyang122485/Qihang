package com.gh.dl.ui

// 本文件在阶段 1 瘦身（解析页 composable 迁至 ui/parser/ParserTab.kt）；
// 阶段 2 在此创建 4 个按页 ViewModel 并通过 CompositionLocalProvider 暴露给整棵 Compose 树。
import com.gh.dl.ui.parser.AddLinkDialog
import com.gh.dl.ui.parser.ParseDialog
import com.gh.dl.ui.parser.ParserTab
import com.gh.dl.data.Accel
import com.gh.dl.data.MirrorSources
import com.gh.dl.data.GhView
import com.gh.dl.data.Settings
import com.gh.dl.data.Threads

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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
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
import androidx.compose.ui.text.style.TextOverflow
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

    // 加速快捷按钮的 Snackbar 宿主与协程作用域（单击切换时显示提示）
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 阶段 2：按页 ViewModel，owner = MainActivity，配置变更（旋转等）不重建、状态不丢失
    val settingsVM: SettingsViewModel = viewModel()
    val downloadsVM: DownloadsViewModel = viewModel()
    val parserVM: ParserViewModel = viewModel()
    val searchVM: SearchViewModel = viewModel()
    val browseVM: BrowseViewModel = viewModel()
    // 跨页引用注入：解析、搜索、下载动作共享下载通道配置；打包与批量下载需要下载列表
    settingsVM.parser = parserVM
    settingsVM.search = searchVM
    parserVM.settings = settingsVM
    parserVM.downloads = downloadsVM
    downloadsVM.settings = settingsVM
    downloadsVM.parser = parserVM
    searchVM.settings = settingsVM

    // 阶段 3：设置页 NavHost 的 NavController，与 Activity 同生命周期（切换 Tab 或旋转时不重建、返回栈不丢失）
    val settingsNav = rememberNavController()

    LaunchedEffect(Unit) {
        settingsVM.initSettings(ctx)
        // 历史下载列表为一整份 JSON 文件（任务较多时可达数百条），读取与解析在 IO 线程执行，
        // 结果回到主线程后写入 Compose 状态。跨线程增删 SnapshotStateList 不在允许范围内。
        downloadsVM.loadDownloadsAsync(ctx)
        // 首次启动（未完成引导）显示全屏引导页（Task 7）
        if (Settings.get(ctx, "intro_done", "0") != "1") AppState.showIntro = true
        if (!initialLink.isNullOrBlank() && parserVM.input.isBlank()) {
            parserVM.input = initialLink
            parserVM.doLoad(ctx, initialLink)
        }
        // 启动后探活 9 个国内镜像源（HEAD 请求测延迟），结果写入 AppState 供诊断页使用。
        // 当前实现：此步骤受开关控制且默认不执行。应用启动时自动连接九个第三方站点，
        // 属于未经用户同意即外联的行为，因此默认关闭。
        // 关闭时线路诊断页显示「尚未探活」，并提供手动「立即探活」按钮。
        // 探活为网络操作，须在后台线程执行；写入 AppState 须回到主线程（见 AppState.mirrorProbe 的约定）
        if (settingsVM.mirrorProbeOn) {
            Threads.start("启动镜像探活") {
                val r = runCatching { MirrorSources.probeAll() }.getOrDefault(emptyList())
                MAIN.post {
                    AppState.mirrorProbe = r
                    AppState.mirrorProbed = true
                }
            }
        }
    }

    // 前台状态下收到新的分享或深链（由 MainActivity.onNewIntent 投递）：切回解析器并直接解析。
    LaunchedEffect(AppState.pendingLink) {
        val l = AppState.pendingLink
        if (l.isNullOrBlank()) return@LaunchedEffect
        AppState.pendingLink = null
        AppState.tab = AppState.TAB_PARSER
        parserVM.input = l
        parserVM.doLoad(ctx, l)
    }

    CompositionLocalProvider(
        LocalParserVM provides parserVM,
        LocalDownloadsVM provides downloadsVM,
        LocalSearchVM provides searchVM,
        LocalSettingsVM provides settingsVM,
        LocalBrowseVM provides browseVM,
        LocalSettingsNav provides settingsNav,
    ) {
        // 阶段 3：返回键行为分为两段：「设置子页 → 设置根列表」与「切回解析器 Tab / 退出」。
        // 设置子页的逐级返回由 NavHost 内部的 BackHandler 处理（在设置页内消费，直至回到根列表）；
        // 此处仅负责「非解析器 Tab → 返回解析器」与「解析器 Tab → 退出」。
        BackHandler(enabled = AppState.tab != AppState.TAB_PARSER) {
            AppState.tab = AppState.TAB_PARSER
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    // 标题在槽位内计算，不放置于外层：外层为 GhApp 的作用域，
                    // 标题变化会导致整棵 GhApp 重组。槽位本身构成一个
                    // 重组边界，读取 AppState.tab / pageTitle 仅触发该区域的重组。
                    title = {
                        val t = when (AppState.tab) {
                            AppState.TAB_PARSER -> "解析器"
                            AppState.TAB_BROWSE ->
                                if (browseVM.pageTitle.isNotBlank()) browseVM.pageTitle else "浏览"
                            AppState.TAB_DOWNLOADS -> "下载"
                            AppState.TAB_SEARCH -> "搜索"
                            else -> "设置"
                        }
                        // 顶栏标题必须单行：浏览页标题取自网页 <title>（可能较长），
                        // 多行会被 TopAppBar 固定高度裁切，显示为页面顶部一块残缺文字
                        Text(
                            t,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    actions = {
                        if (AppState.tab == AppState.TAB_BROWSE) {
                            // 页面翻译：译文直接替换正文文字；翻译中可取消，已翻译时可回到原文
                            IconButton(
                                onClick = { browseVM.toggleTranslation() },
                                enabled = browseVM.page != null
                            ) {
                                Icon(
                                    Icons.Filled.Translate,
                                    contentDescription = when {
                                        browseVM.translating -> "取消翻译"
                                        browseVM.translated -> "显示原文"
                                        else -> "翻译页面"
                                    },
                                    tint = if (browseVM.translated || browseVM.translating) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            // 浏览页的加速按钮控制「浏览加速」，与解析器页的下载加速相互独立
                            AccelQuickButton(
                                ctx = ctx,
                                settingsVM = settingsVM,
                                snackbarHostState = snackbarHostState,
                                scope = scope,
                                forBrowse = true
                            )
                            // 浏览页导航控件：后退 / 前进 / 刷新 / 在外部浏览器中打开
                            BrowseTopActions()
                        }
                        if (AppState.tab == AppState.TAB_PARSER) {
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
                                        contentDescription = "刷新仓库",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (AppState.tab == AppState.TAB_SEARCH) {
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
                        selected = AppState.tab == AppState.TAB_PARSER,
                        onClick = { AppState.tab = AppState.TAB_PARSER },
                        icon = {
                            Icon(
                                if (AppState.tab == AppState.TAB_PARSER) Icons.Filled.Home else Icons.Outlined.Home,
                                contentDescription = null
                            )
                        },
                        label = { Text("解析器", fontSize = 12.sp) }
                    )
                    NavigationBarItem(
                        selected = AppState.tab == AppState.TAB_BROWSE,
                        onClick = { AppState.tab = AppState.TAB_BROWSE },
                        icon = {
                            Icon(
                                if (AppState.tab == AppState.TAB_BROWSE) Icons.Filled.Language
                                else Icons.Outlined.Language,
                                contentDescription = null
                            )
                        },
                        label = { Text("浏览", fontSize = 12.sp) }
                    )
                    NavigationBarItem(
                        selected = AppState.tab == AppState.TAB_DOWNLOADS,
                        onClick = { AppState.tab = AppState.TAB_DOWNLOADS },
                        icon = {
                            // 在途任务数在 badge 槽位内计算，不放在 GhApp 作用域：
                            // 该值读取每个 job 的 running 状态，下载进度每 500ms 刷新一次，
                            // 若放在外层，每次刷新都会触发整棵 GhApp 重组（含顶栏 actions）。
                            BadgedBox(badge = {
                                val running = downloadsVM.downloads.count { it.running } +
                                    (if (CloneJob.running) 1 else 0)
                                if (running > 0) Badge {
                                    Text("$running", color = MaterialTheme.colorScheme.onError,
                                        style = LocalTextStyle.current.copy(fontSize = 11.sp))
                                }
                            }) {
                                Icon(
                                    if (AppState.tab == AppState.TAB_DOWNLOADS) Icons.Filled.Download
                                    else Icons.Outlined.Download,
                                    contentDescription = null
                                )
                            }
                        },
                        label = { Text("下载", fontSize = 12.sp) }
                    )
                    NavigationBarItem(
                        selected = AppState.tab == AppState.TAB_SEARCH,
                        onClick = { AppState.tab = AppState.TAB_SEARCH },
                        icon = {
                            Icon(
                                if (AppState.tab == AppState.TAB_SEARCH) Icons.Filled.Search else Icons.Outlined.Search,
                                contentDescription = null
                            )
                        },
                        label = { Text("搜索", fontSize = 12.sp) }
                    )
                    NavigationBarItem(
                        selected = AppState.tab == AppState.TAB_SETTINGS,
                        onClick = {
                            AppState.tab = AppState.TAB_SETTINGS
                            // 从底栏进入设置：清除历史返回栈，回到根列表页（NavHost 未挂载时 popBackStack 安全返回 false）
                            settingsNav.popBackStack(SettingsRoute.Root.route, inclusive = false)
                        },
                        icon = {
                            Icon(
                                if (AppState.tab == AppState.TAB_SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
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
                    AppState.TAB_PARSER -> ParserTab()
                    // 未访问过「浏览」时不创建 WebView，仅显示引导文案（节省内存与网络请求）
                    AppState.TAB_BROWSE -> if (browseVM.url.isBlank()) BrowseEmptyHint() else BrowseTab()
                    AppState.TAB_DOWNLOADS -> DownloadsTab()
                    AppState.TAB_SEARCH -> SearchTab()
                    else -> SettingsTab()
                }
            }
        }

        if (parserVM.showAddLink) AddLinkDialog()
        if (parserVM.showParseDialog) ParseDialog()
    }

    // 首次启动显示全屏引导页（覆盖在 Tab 之上，不阻断 Tab 初始化）
    if (AppState.showIntro) {
        IntroPage(onDone = {
            Settings.put(ctx, "intro_done", "1")
            AppState.showIntro = false
        })
    }
}

/** 加速前缀下拉菜单的一行：当前生效项显示勾选标记 */
@Composable
private fun AccelPrefixRow(p: String, active: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "当前使用",
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
        onClick = onClick
    )
}

/**
 * 顶栏加速快捷按钮：根据当前所在页面切换对应的加速档位。
 *
 * 当前实现下载加速与浏览加速拆分为两套独立机制（互不相通，详见 GhView 类注释），
 * 因此该按钮不再作为全局加速开关使用：
 *  - 浏览页 → 切换 [SettingsViewModel.browseAccelOn]（网页镜像站档位），长按进入「设置 → 加速 → 浏览加速」；
 *  - 解析器 / 搜索页 → 切换 [SettingsViewModel.accelOn]（加速前缀档位），长按弹出「内置前缀 + 自己添加的前缀」列表，可直接切换。
 * 图标状态：当前档位可用（开关开启且目标地址非空）时高亮显示。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AccelQuickButton(
    ctx: Context,
    settingsVM: SettingsViewModel,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    forBrowse: Boolean = false
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val prefix = Accel.normalizePrefix(settingsVM.custom)
    val on = if (forBrowse) settingsVM.browseAccelOn
             else settingsVM.accelOn && prefix.isNotBlank()

    Box(
        Modifier
            .size(44.dp)
            .combinedClickable(
                onClick = {
                    val msg = if (forBrowse) {
                        val nv = !settingsVM.browseAccelOn
                        settingsVM.browseAccelOn = nv
                        Settings.put(ctx, "browseAccelOn", if (nv) "1" else "0")
                        if (nv) "浏览加速已开启：优先使用网页镜像站 ${GhView.pageMirrors.firstOrNull().orEmpty()}"
                        else "浏览加速已关闭：仅直连 GitHub"
                    } else {
                        val nv = !settingsVM.accelOn
                        settingsVM.setAccelEnabled(ctx, nv)
                        if (nv) {
                            val active = Accel.normalizePrefix(settingsVM.custom)
                            if (active.isBlank()) {
                                "下载加速已开启，但没有可用前缀；请前往「设置 → 加速 → 下载加速」添加"
                            } else {
                                "下载加速已开启，当前加速前缀：$active"
                            }
                        } else "下载加速已关闭"
                    }
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
                contentDescription = if (forBrowse) "浏览加速开关" else "下载加速开关（长按切换前缀）",
                tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
        if (forBrowse) {
            // 浏览加速档不涉及「加速前缀列表」概念（其切换对象为镜像站），长按直接进入设置页
            DropdownMenuItem(
                text = { Text(if (settingsVM.browseAccelOn) "浏览加速：已开启" else "浏览加速：已关闭", fontSize = 13.sp) },
                onClick = { }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("镜像站：" + GhView.pageMirrors.joinToString("、"), fontSize = 12.sp) },
                onClick = { }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("管理浏览加速…", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) },
                onClick = {
                    menuExpanded = false
                    AppState.tab = AppState.TAB_SETTINGS
                    AppState.settingsDeepLink = "accelBrowse"
                }
            )
            return@DropdownMenu
        }
        val builtIns = Accel.BUILT_IN_PREFIXES
        // 内置前缀置顶，之后只列用户自己添加且不与内置重复的条目
        val extraSaved = settingsVM.accelPrefixes.filter { p ->
            builtIns.none { it.equals(p, true) }
        }
        val choose: (String) -> Unit = { p ->
            menuExpanded = false
            // 先开开关再写入前缀，activatePrefix 才会把通道同步为 CUSTOM
            settingsVM.accelOn = true
            Settings.put(ctx, "accelOn", "1")
            settingsVM.activatePrefix(ctx, p)
            scope.launch { snackbarHostState.showSnackbar("已切换加速前缀：$p") }
        }

        DropdownMenuItem(
            text = {
                Text(
                    "内置前缀（点击切换）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            enabled = false,
            onClick = { }
        )
        builtIns.forEach { p ->
            AccelPrefixRow(p, p == prefix) { choose(p) }
        }

        if (extraSaved.isNotEmpty()) {
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        "我添加的前缀",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                enabled = false,
                onClick = { }
            )
            extraSaved.forEach { p ->
                AccelPrefixRow(p, p == prefix) { choose(p) }
            }
        }

        HorizontalDivider()
        DropdownMenuItem(
            text = {
                Text("添加 / 管理加速前缀…", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            },
            onClick = {
                menuExpanded = false
                // 跨 Tab 跳转通过 deep-link 实现：先切换到设置 Tab，待 SettingsTab 挂载后消费目标路由，
                // 避免在设置 NavHost 尚未挂载时直接 navigate 导致「Navigation graph has not been set」异常。
                AppState.tab = AppState.TAB_SETTINGS
                AppState.settingsDeepLink = "accelDownload"
            }
        )
    }
}
