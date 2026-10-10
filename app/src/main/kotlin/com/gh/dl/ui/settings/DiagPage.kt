package com.gh.dl.ui.settings

// 阶段1 纯拆分：原 SettingsTab.kt 的「DiagPage」二级页。逻辑零改动。
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
    // 「正在探活」「探活报错」都是这一页自己的界面状态，不放 AppState（别的页不关心）
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
                    "逐个查 github.com、api.github.com、codeload、raw、objects 这几个域名：" +
                        "系统 DNS 给的 IP 和公共 DoH 查到的真实 IP 分别是多少、哪个能连上。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vmParser.doDiagnose(ctx) }, enabled = !vmParser.diagBusy) {
                    Text(if (vmParser.diagBusy) "正在诊断…" else "开始诊断")
                }
            }
        }

        // ---- 国内镜像源探活 ----
        // 第九轮：启动时**不再自动探活**（默认关闭，见 vmSettings.mirrorProbeOn）。
        // 原来一进 App 就并发对 9 个第三方站点发 HEAD，属于「没经同意就外联」，
        // 所以这里改成：默认显示「还没联网探活过」+ 一个用户主动点的「立即探活」。
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
                            Thread {
                                // 探活本身可能整段抛出（线程池、DNS 客户端都可能）。
                                // 结果与错误都只在后台算好，**统一一次 MAIN.post 回主线程写**：
                                // 既守住「界面状态只从主线程写」的约定，也保证抛出时
                                // 这个转圈一定停得下来（否则按钮永远点不动，第八轮同类问题）。
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
                            }.start()
                        }) { Text(if (AppState.mirrorProbe.isNotEmpty()) "重新探活" else "立即探活", fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启动时自动探活", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "开着时每次进 App 会自动连这 9 个站点测延迟；关着就一次也不联网，只能手动点。",
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
                        else "还没探活过（默认不自动联网测）。想看看哪些镜像站通、延迟多少，点上面的「立即探活」。",
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
                        "这里的「可用」只表示那个站点当时连得上，不代表它同步了你要的仓库。\n" +
                            "解析器选「国内镜像」时列的是「设置 → 镜像源管理」里的全部源（探到延迟的会顺带标出来），不会把没探到的藏掉。",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (err.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(err, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
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
