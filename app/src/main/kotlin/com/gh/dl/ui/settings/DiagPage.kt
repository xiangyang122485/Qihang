package com.gh.dl.ui.settings

// 自原 SettingsTab.kt 拆分而来的「DiagPage」二级页。业务逻辑未作改动。
import com.gh.dl.ui.*
import com.gh.dl.data.*

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DiagPage(onBack: () -> Unit) {
    val vmParser = LocalParserVM.current
    val vmSettings = LocalSettingsVM.current

    val ctx = LocalContext.current
    // 「正在探活」「探活报错」为本页私有界面状态，不放入 AppState（其他页面无需关心）
    var probing by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("线路诊断", onBack)

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "依次解析 github.com、api.github.com、codeload、raw、objects，" +
                        "输出系统 DNS 地址、公共 DoH 地址与各自的连通结果。" +
                        "若已开启下载加速，还会额外测试当前前缀是否支持 API 与文件下载。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vmParser.doDiagnose(ctx) }, enabled = !vmParser.diagBusy) {
                    Text(if (vmParser.diagBusy) "正在诊断…" else "开始诊断")
                }
            }
        }

        // ---- 国内镜像源探活 ----
        // 默认关闭自动探活（vmSettings.mirrorProbeOn）：启动即向第三方站点发送 HEAD 请求属于
        // 未经用户同意的连接。界面默认显示「尚未探活」，并提供手动「立即探活」按钮。
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("国内镜像源实时延迟", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (probing) {
                        CircularProgressIndicator(Modifier.size(16.dp))
                    } else {
                        TextButton(onClick = {
                            probing = true
                            err = ""
                            Threads.start("镜像源探活") {
                                // 结果与错误在后台计算完成后单次 MAIN.post 回主线程写入，
                                // 保证异常时加载指示器也会停止。
                                var r: List<MirrorSources.Probe> = emptyList()
                                var failure: String? = null
                                try {
                                    r = MirrorSources.probeAll()
                                } catch (e: Exception) {
                                    failure = "探活失败：${e.message ?: e.javaClass.simpleName}"
                                }
                                val got = r
                                val why = failure
                                MAIN.post {
                                    AppState.mirrorProbe = got
                                    if (got.isNotEmpty()) AppState.mirrorProbed = true
                                    err = why ?: ""
                                    probing = false
                                }
                            }
                        }) { Text(if (AppState.mirrorProbe.isNotEmpty()) "重新探活" else "立即探活", fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启动时自动探活", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "启用后每次进入应用自动连接 9 个站点测量延迟；关闭后不发起连接，仅可手动触发。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = vmSettings.mirrorProbeOn, onCheckedChange = {
                        vmSettings.mirrorProbeOn = it
                        Settings.put(ctx, "mirrorProbeOn", if (it) "1" else "0")
                    })
                }
                Spacer(Modifier.height(6.dp))
                if (AppState.mirrorProbe.isEmpty()) {
                    Text(
                        if (probing) "正在探活 9 个国内镜像源…"
                        else "尚未探活。如需查看连通性与延迟，点击上方「立即探活」。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    AppState.mirrorProbe.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusDot(
                                color = if (p.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(8.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                p.name,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                                color = if (p.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (p.ok) "${p.ms}ms" else "不可用",
                                fontSize = 12.sp,
                                color = if (p.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "「可用」仅表示该站点当时可连接，不代表已同步目标仓库。\n" +
                            "解析器选择「国内镜像」时列出「设置 → 网络 → 镜像源管理」中的全部源，已探活站点标注延迟。",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (err.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(err, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // ---- 本机运行环境 ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("本机运行环境", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val abi = Device.abis()
                CompatRow("内核页大小", "${Device.pageSize()} B" + if (Device.widePage()) "（16KB）" else "")
                CompatRow("支持 ABI", if (abi.isEmpty()) "未知" else abi.joinToString(" / "))
                CompatRow("内置原生库", if (Device.supportsBundledAbi()) "ABI 匹配" else "ABI 不匹配，相关功能自动回退")
                CompatRow("aria2c", AriaEngine.status(ctx))
                CompatRow("libgit2", Git2.status())
                CompatRow("curl / wget", listOf("curl", "wget").joinToString(" / ") {
                    if (Fetcher.toolPath(it) != null) "可用" else "缺失"
                })
            }
        }

        // ---- 未捕获异常记录 ----
        // 线程中逃逸的异常由 [com.gh.dl.App] 落盘，此处展示最近一条，用于定位异常退出
        var crashSeq by remember { mutableIntStateOf(0) }
        val crash = remember(crashSeq) { com.gh.dl.App.lastCrash(ctx) }
        if (crash.first.isNotBlank()) {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "最近一次异常退出",
                            fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            com.gh.dl.App.clearCrash(ctx)
                            crashSeq++
                        }) { Text("清除", fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date(crash.second)),
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        Text(crash.first, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { copyText(ctx, crash.first, "异常记录已复制") }) {
                        Text("复制异常记录", fontSize = 12.sp)
                    }
                }
            }
        }

        if (vmParser.lineInfo.isNotBlank()) {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(vmParser.lineInfo, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { copyText(ctx, vmParser.lineInfo, "诊断结果已复制") }) {
                        Text("复制结果", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompatRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(k, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
        Text(v, fontSize = 11.sp)
    }
}
