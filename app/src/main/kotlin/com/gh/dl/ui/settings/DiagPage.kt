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

    val ctx = LocalContext.current
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

        // ---- 国内镜像源探活（启动自动跑，这里可手动刷新） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("国内镜像源实时延迟", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (!AppState.mirrorProbed) {
                        CircularProgressIndicator(Modifier.size(16.dp))
                    } else {
                        TextButton(onClick = {
                            Thread {
                                val r = MirrorSources.probeAll()
                                AppState.mirrorProbe = r
                            }.start()
                        }) { Text("刷新", fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (!AppState.mirrorProbed && AppState.mirrorProbe.isEmpty()) {
                    Text("正在探活 9 个国内镜像源…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        "不可用的源已置灰；解析器选「国内镜像」时只列出探活成功的源。",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
