package com.gh.dl.ui.settings

// 阶段 4「设置 Tab 去重重构」：把原 NetPage（网络通道）+ AccelPage（加速代理）+ AccelManagePage（前缀删除）
// 合并为单一的「网络」分组页。一级页「网络」卡片点进来就是这里。
import com.gh.dl.ui.*
import com.gh.dl.data.*

import android.content.*
import androidx.navigation.NavHostController
import androidx.activity.compose.*
import androidx.activity.result.contract.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*

@Composable
fun NetworkPage(nav: NavHostController, onBack: () -> Unit) {
    val vmParser = LocalParserVM.current
    val vmSearch = LocalSearchVM.current
    val vmSettings = LocalSettingsVM.current

    val ctx = LocalContext.current
    var showAddPrefix by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("网络", onBack)

        // ---- 线路选择：GitHub 官方源 / 国内镜像（单选） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("线路选择", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "GitHub 官方源直连（时断时通，不依赖中转）；国内镜像走高校 github-release 镜像（直连无中转）。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "official" to "GitHub 官方源",
                        "domestic" to "国内镜像"
                    ).forEach { (id, label) ->
                        val sel = vmParser.source == id
                        TextButton(onClick = {
                            vmParser.source = id
                            vmParser.applySource(ctx)
                        }) {
                            Text(
                                (if (sel) "● " else "") + label,
                                fontSize = 12.sp,
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ---- 加速代理（合并原 AccelPage + 原 AccelManagePage 的删除） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("加速代理（ghproxy 风格 · 第三方中转）", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showAddPrefix = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加加速前缀", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                CollapsibleNote(
                    "加速代理说明（点击展开）",
                    "原理：在每个 GitHub 链接前加一个前缀，由它转发请求再回传，" +
                        "从而绕过直连失败。这是你最初划定的「第三方代拉」——豆包说它「不违法」" +
                        "在技术上没错（只是转发公开开源内容、不涉及加密隧道），但公共实例任何人都可搭、" +
                        "可能替换你下载的二进制、且经常换域名跑路。\n" +
                        "默认关闭；开启后所有 GitHub 请求自动套前缀。实测可用前缀：gh-proxy.com、gh-proxy.org" +
                        "（公共实例可能随时换域名，请以实际能联通为准；你之前给的 5 个前缀当前实测均不可用：" +
                        "mirror.ghproxy.com→502、ghproxy.net→403、gh.llkk.cc→502、hub.gitmirror.com→502、github.akams.cn→404）。\n\n" +
                        "⚠️ 红线提示：镜像加速仅用于下载 GitHub 公开开源项目资源，" +
                        "不支持私有仓库、SSH 协议。镜像为社区公益服务，稳定性无法保证。" +
                        "开启时绝不会把你的 Token / Cookie 发给镜像服务器（令牌只发给 GitHub 官方）。"
                )
                Row(
                    Modifier.fillMaxWidth().clickable { vmSettings.accelOn = !vmSettings.accelOn }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用加速代理", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = vmSettings.accelOn, onCheckedChange = {
                        vmSettings.accelOn = it
                        Settings.put(ctx, "accelOn", if (it) "1" else "0")
                        vmSettings.channel =
                            if (it && vmSettings.custom.isNotBlank()) Channels.CUSTOM else Channels.OFFICIAL
                        Settings.put(ctx, "channel", vmSettings.channel)
                        Net.rebuild()
                    })
                }
                OutlinedTextField(
                    value = vmSettings.custom, onValueChange = {
                        vmSettings.custom = it
                        Settings.put(ctx, "custom", it)
                        if (vmSettings.accelOn) {
                            vmSettings.channel =
                                if (it.isNotBlank()) Channels.CUSTOM else Channels.OFFICIAL
                            Net.rebuild()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("代理前缀输入框") },
                    placeholder = { Text("https://gh-proxy.com") },
                    singleLine = true
                )
                Spacer(Modifier.height(4.dp))
                Text("常用前缀（点一下填入，仅列实测可用的）：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "https://gh-proxy.com",
                        "https://gh-proxy.org"
                    ).forEach { p ->
                        TextButton(onClick = {
                            vmSettings.custom = p
                            Settings.put(ctx, "custom", p)
                            if (vmSettings.accelOn) {
                                vmSettings.channel = Channels.CUSTOM
                                Net.rebuild()
                            }
                        }) {
                            Text(p.removePrefix("https://"), fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().clickable {
                        vmSettings.verifyOn = !vmSettings.verifyOn
                        Settings.put(ctx, "verifyOn", if (vmSettings.verifyOn) "1" else "0")
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("下载后强制校验 SHA256（防篡改）", fontSize = 13.sp)
                        Text("强烈建议开启：可发现第三方是否替换了文件", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = vmSettings.verifyOn, onCheckedChange = {
                        vmSettings.verifyOn = it
                        Settings.put(ctx, "verifyOn", if (it) "1" else "0")
                    })
                }
                Spacer(Modifier.height(6.dp))
                Text("已保存的加速前缀（点一下填入，点垃圾桶删除）", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
                if (vmSettings.accelPrefixes.isEmpty()) {
                    Text("还没有保存的前缀，点右上角「+」添加。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    vmSettings.accelPrefixes.forEach { p ->
                        val active = p == vmSettings.custom
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vmSettings.custom = p
                                    Settings.put(ctx, "custom", p)
                                    if (vmSettings.accelOn) {
                                        vmSettings.channel = Channels.CUSTOM
                                        Net.rebuild()
                                    }
                                    toast(ctx, "已填入：$p")
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                (if (active) "● " else "") + p,
                                fontSize = 12.sp,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                vmSettings.removeAccelPrefix(ctx, p)
                                toast(ctx, "已删除：$p")
                            }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- GitHub Token（可选） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GitHub Token（可选）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                CollapsibleNote(
                    "GitHub Token 说明（点击展开）",
                    "官方线路通的时候，填 Token 能提高 API 速率限制；" +
                        "SSH 克隆用的不是它，是「SSH 密钥」里那把手机钥匙。"
                )
                OutlinedTextField(
                    value = vmSettings.token, onValueChange = { vmSettings.token = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("GitHub Token（可留空）") }, singleLine = true
                )
            }
        }

        // ---- HTTP 代理（可选，系统层） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HTTP 代理（可选）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                CollapsibleNote(
                    "HTTP 代理说明（点击展开）",
                    "这是系统层的 HTTP 代理（host:port），与你自己的代理软件配合用；" +
                        "和「加速代理前缀」是两种不同机制，一般只开一个。"
                )
                OutlinedTextField(
                    value = vmSettings.proxy, onValueChange = { vmSettings.proxy = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTP 代理 host:port（可留空）") }, singleLine = true
                )
            }
        }

        // ---- 镜像源管理入口 ----
        NavRow(
            icon = Icons.Filled.Storage,
            title = "镜像源管理",
            subtitle = "导入/删除/测速 高校镜像（清华·华为云·中科大等），设默认下载线路",
            onClick = { nav.navigate(SettingsRoute.Mirrors.route) }
        )

        // ---- 线路诊断入口（从解析器 Tab 挪到「网络」分组下） ----
        NavRow(
            icon = Icons.Filled.MonitorHeart,
            title = "线路诊断",
            subtitle = vmParser.netSummary.ifBlank { "看看当前网络对 GitHub 通不通" },
            onClick = { nav.navigate(SettingsRoute.Diag.route) }
        )

        Row {
            Button(onClick = {
                Settings.put(ctx, "custom", vmSettings.custom)
                Settings.put(ctx, "token", vmSettings.token)
                Settings.put(ctx, "proxy", vmSettings.proxy)
                Settings.put(ctx, "accelOn", if (vmSettings.accelOn) "1" else "0")
                Settings.put(ctx, "verifyOn", if (vmSettings.verifyOn) "1" else "0")
                Settings.put(ctx, "lfsOn", if (vmSettings.lfsOn) "1" else "0")
                Settings.put(ctx, "engine", vmSettings.engine)
                Settings.put(ctx, "sshChannel", vmSettings.sshChannel)
                Settings.put(ctx, "cloneOn", if (vmSettings.cloneOn) "1" else "0")
                Settings.put(ctx, "source", vmParser.source)
                Settings.put(ctx, "searchSource", vmSearch.searchSource)
                Settings.put(ctx, "domestic", vmParser.domestic)
                val p = vmSettings.proxy.split(":")
                if (p.size == 2) {
                    Net.proxyHost = p[0].trim()
                    Net.proxyPort = p[1].trim().toIntOrNull() ?: 0
                } else {
                    Net.proxyHost = ""
                    Net.proxyPort = 0
                }
                Net.rebuild()
                vmSettings.channel =
                    if (vmSettings.accelOn && vmSettings.custom.isNotBlank()) Channels.CUSTOM else Channels.OFFICIAL
                Settings.put(ctx, "channel", vmSettings.channel)
                toast(ctx, "已保存")
            }) { Text("保存") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                vmSettings.custom = ""
                vmSettings.token = ""
                vmSettings.proxy = ""
                vmSettings.accelOn = false
                vmSettings.verifyOn = true
                vmSettings.lfsOn = true
                vmSettings.engine = "jgit"
                vmSettings.sshChannel = "https"
                vmSettings.cloneOn = true
                Settings.put(ctx, "custom", "")
                Settings.put(ctx, "token", "")
                Settings.put(ctx, "proxy", "")
                Settings.put(ctx, "accelOn", "0")
                Settings.put(ctx, "verifyOn", "1")
                Settings.put(ctx, "lfsOn", "1")
                Settings.put(ctx, "engine", "jgit")
                Settings.put(ctx, "sshChannel", "https")
                Settings.put(ctx, "cloneOn", "1")
                Settings.put(ctx, "source", "official")
                Settings.put(ctx, "searchSource", "github")
                Settings.put(ctx, "domestic", "tuna")
                Net.proxyHost = ""
                Net.proxyPort = 0
                Net.rebuild()
                vmSettings.channel = Channels.OFFICIAL
                Settings.put(ctx, "channel", Channels.OFFICIAL)
                toast(ctx, "已恢复默认")
            }) { Text("恢复默认") }
        }
    }

    if (showAddPrefix) AddPrefixDialog(onClose = { showAddPrefix = false })
}
