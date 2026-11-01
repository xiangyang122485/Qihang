package com.gh.dl.ui.settings

// 「网络」二级页：线路选择 / GitHub Token / GitCode Token / HTTP 代理 / 镜像源管理 / 线路诊断。
// 「加速代理」已移至独立页面（→ 设置 → 加速 → 下载加速 / 浏览加速），
// 因下载与浏览的加速机制完全不同，合并管理易造成混淆。
import com.gh.dl.ui.*
import com.gh.dl.data.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController

@Composable
fun NetworkPage(nav: NavHostController, onBack: () -> Unit) {
    val vmParser = LocalParserVM.current
    val vmSettings = LocalSettingsVM.current
    val ctx = LocalContext.current

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("网络", onBack)

        // ---- 线路选择：GitHub 官方源 / 国内镜像（单选） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("线路选择", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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

        // ---- DoH + 真实 IP 优选（默认关闭） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DoH + 真实 IP 优选", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.weight(1f))
                    Switch(checked = vmSettings.dnsPinningOn, onCheckedChange = {
                        vmSettings.dnsPinningOn = it
                        Settings.put(ctx, "dnsPinningOn", if (it) "1" else "0")
                        // data 层不读 ViewModel：修改后必须立即同步至 Net，否则界面开启时底层仍使用旧配置
                        // （或界面关闭后底层仍发送请求）。
                        Net.dnsPinningOn = it
                    })
                }
                Text(
                    if (vmSettings.dnsPinningOn) "已开启：向第三方 DoH 查询域名并外连探活"
                    else "已关闭（默认）：仅使用系统 DNS 直连，不查询 DoH",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 加速入口（三级页，已从本页拆出） ----
        NavRow(
            icon = Icons.Filled.Speed,
            title = "加速设置",
            subtitle = "下载加速：" + (if (vmSettings.accelOn) "开" else "关（默认）") +
                " · 浏览加速：" + (if (vmSettings.browseAccelOn) "开" else "关（默认）"),
            onClick = { nav.navigate(SettingsRoute.Accel.route) }
        )

        // ---- GitHub Token（可选） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GitHub Token（可选）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                var ghShow by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = vmSettings.token, onValueChange = {
                        vmSettings.token = it
                        Settings.put(ctx, "token", it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("GitHub Token（可留空）") }, singleLine = true,
                    visualTransformation = if (ghShow) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { ghShow = !ghShow }) {
                            Icon(
                                if (ghShow) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (ghShow) "隐藏令牌" else "显示令牌"
                            )
                        }
                    }
                )
            }
        }

        // ---- GitCode Token（可选）----
        // 此 Token 归属域名为 gitcode.com，仅发送至该域名；不会被带入
        // GitHub 请求或任何加速代理前缀（Net.text 的 authHeader 仅在显式调用时使用）。
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GitCode Token（可选）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                var gcShow by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = AppState.gitcodeToken,
                    onValueChange = {
                        AppState.gitcodeToken = it
                        Settings.put(ctx, "gitcodeToken", it.trim())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("GitCode 访问令牌（可留空）") }, singleLine = true,
                    visualTransformation = if (gcShow) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { gcShow = !gcShow }) {
                            Icon(
                                if (gcShow) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (gcShow) "隐藏令牌" else "显示令牌"
                            )
                        }
                    }
                )
            }
        }

        // ---- HTTP 代理（可选，系统层） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HTTP 代理（可选）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedTextField(
                    value = vmSettings.proxy, onValueChange = {
                        vmSettings.proxy = it
                        Settings.put(ctx, "proxy", it)
                        applyProxy(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTP 代理 host:port（可留空）") }, singleLine = true
                )
            }
        }

        // ---- 镜像源管理入口 ----
        NavRow(
            icon = Icons.Filled.Storage,
            title = "镜像源管理",
            subtitle = "高校镜像（清华 · 华为云 · 中科大等）的导入、删除与测速，可设为默认下载线路",
            onClick = { nav.navigate(SettingsRoute.Mirrors.route) }
        )

        // ---- 线路诊断入口 ----
        NavRow(
            icon = Icons.Filled.MonitorHeart,
            title = "线路诊断",
            subtitle = vmParser.netSummary.ifBlank { "检测当前网络对 GitHub 的连通性" },
            onClick = { nav.navigate(SettingsRoute.Diag.route) }
        )
    }
}

/** host:port → Net 的代理字段（修改后立即重建客户端，无需再点保存） */
private fun applyProxy(value: String) {
    val p = value.split(":")
    if (p.size == 2) {
        Net.proxyHost = p[0].trim()
        Net.proxyPort = p[1].trim().toIntOrNull() ?: 0
    } else {
        Net.proxyHost = ""
        Net.proxyPort = 0
    }
    Net.rebuild()
}
