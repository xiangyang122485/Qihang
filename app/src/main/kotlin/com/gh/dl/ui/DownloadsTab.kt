package com.gh.dl.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh.dl.data.fmtSize
import kotlinx.coroutines.delay

private fun hhmmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60} 分 ${s % 60} 秒"
}

/**
 * 「下载」Tab：只放下载的文件与进行中的任务。
 * 源码仓库（克隆结果）默认存到「设置→下载位置」授权的共享文件夹里，不在这里列，
 * 避免和「下载的文件」（Release 资产）混在一起。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsTab() {
    val vmDownloads = LocalDownloadsVM.current
    val vmSettings = LocalSettingsVM.current

    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    var showLink by remember { mutableStateOf<String?>(null) }

    val anyRunning = CloneJob.running || vmDownloads.downloads.any { it.running }
    LaunchedEffect(anyRunning) {
        while (CloneJob.running || vmDownloads.downloads.any { it.running }) {
            tick = System.currentTimeMillis()
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---- 克隆任务（进行中） ----
            if (CloneJob.running) {
                item {
                    GhCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("克隆仓库 ${CloneJob.slug}", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            if (CloneJob.percent >= 0f) {
                                LinearProgressIndicator(
                                    progress = { CloneJob.percent },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(CloneJob.stage, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "已接收 " + fmtSize(CloneJob.bytes) + " · " + CloneJob.objects + " 个对象" +
                                    (if (CloneJob.speed > 0) " · " + fmtSize(CloneJob.speed) + "/s" else ""),
                                fontSize = 12.sp
                            )
                            Text(
                                "已用时 " + hhmmss(tick - CloneJob.startedAt),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "整仓历史较大时要几分钟。可以切到别的页面，进度不会丢。",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (CloneJob.result.isNotBlank() && !CloneJob.running) {
                item {
                    GhCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                if (CloneJob.ok) "克隆完成" else "克隆失败",
                                fontWeight = FontWeight.Bold,
                                color = if (CloneJob.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(CloneJob.result, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ---- 文件下载任务（Release 资产） ----
            if (vmDownloads.downloads.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("下载的文件", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (vmDownloads.dlMultiSelect) {
                            TextButton(onClick = {
                                vmDownloads.dlMultiSelect = false
                                vmDownloads.dlSel.clear()
                            }) { Text("取消", fontSize = 12.sp) }
                        } else {
                            TextButton(onClick = {
                                vmDownloads.dlSel.clear()
                                vmDownloads.dlMultiSelect = true
                            }) { Text("批量删除", fontSize = 12.sp) }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                vmDownloads.clearCompleted(ctx)
                            }) { Text("清除已完成", fontSize = 12.sp) }
                        }
                    }
                }
            }

            // 必须带 key：这一页在下载进行中每 500ms 刷新一次 tick，列表又会增删任务。
            // 没有 key 时 Compose 按下标对齐，进度条/按钮状态会串到别的任务卡上
            // （A 的进度显示在 B 那一行），还会让整片卡片重排 —— 列表滚不动、发涩。
            items(vmDownloads.downloads, key = { it.id }) { job ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (vmDownloads.dlMultiSelect) {
                        Checkbox(
                            checked = vmDownloads.dlSel.contains(job.id),
                            onCheckedChange = {
                                if (it) vmDownloads.dlSel.add(job.id) else vmDownloads.dlSel.remove(job.id)
                            }
                        )
                    }
                    GhCard(
                        Modifier
                            .weight(1f)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showLink = job.url }
                            )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(
                                    color = when {
                                        job.failed -> MaterialTheme.colorScheme.error
                                        job.done -> MaterialTheme.colorScheme.secondary
                                        job.running -> MaterialTheme.colorScheme.primary
                                        job.paused -> MaterialTheme.colorScheme.warning
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text(job.name, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                    modifier = Modifier.weight(1f))
                                if (job.paused) {
                                    IconButton(
                                        onClick = { vmDownloads.resumeDownload(ctx, job) },
                                        modifier = Modifier.width(28.dp).height(28.dp)
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = "继续",
                                            tint = MaterialTheme.colorScheme.secondary)
                                    }
                                } else if (job.running) {
                                    CircularProgressIndicator(Modifier.width(16.dp).height(16.dp))
                                    Spacer(Modifier.width(2.dp))
                                    IconButton(
                                        onClick = { vmDownloads.pauseDownload(job) },
                                        modifier = Modifier.width(28.dp).height(28.dp)
                                    ) {
                                        Icon(Icons.Filled.Pause, contentDescription = "暂停")
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            // 已完成 → 画满格（停止动画）；有总大小 → 真实进度；
                            // 下载中且总大小未知 → 才用 indeterminate「走动」条；
                            // 暂停/失败且未知大小 → 不画条（避免已完成还转圈）。
                            val knownTotal = job.total > 0
                            val progress = when {
                                job.done -> 1f
                                knownTotal -> (job.bytes.toFloat() / job.total).coerceIn(0f, 1f)
                                else -> 0f
                            }
                            if (job.done || knownTotal) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else if (job.running) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.height(6.dp))
                            val pct = when {
                                job.done -> " · 100%"
                                knownTotal -> " · ${(job.bytes * 100 / job.total)}%"
                                else -> ""
                            }
                            val sizeText = if (knownTotal)
                                fmtSize(job.bytes) + " / " + fmtSize(job.total)
                            else fmtSize(job.bytes) + (if (job.running) "（总大小未知）" else "")
                            Text(
                                sizeText + pct +
                                    (if (job.speed > 0 && job.running) " · " + fmtSize(job.speed) + "/s" else ""),
                                fontSize = 12.sp
                            )
                            if (job.running) {
                                Text(
                                    "已用时 " + hhmmss(tick - job.startedAt),
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (job.where.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(job.where, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                            }
                            if (job.msg.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    job.msg, fontSize = 11.sp,
                                    color = if (job.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (job.sha.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "SHA256: " + job.sha.take(16) + "…" + job.sha.takeLast(8),
                                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (job.verify.isNotBlank()) {
                                            Text(
                                                job.verify, fontSize = 11.sp,
                                                color = if (job.verify.startsWith("✗"))
                                                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    TextButton(onClick = { copyText(ctx, job.sha, "SHA256 已复制") }) {
                                        Text("复制", fontSize = 11.sp)
                                    }
                                }
                            }
                            if (job.failed && job.url.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "下载失败", fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilledTonalButton(onClick = { vmDownloads.retryDownload(ctx, job) }) {
                                        Text("重试", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (vmDownloads.downloads.isEmpty() && !CloneJob.running && CloneJob.result.isBlank()) {
                item {
                    Text(
                        "还没有下载任务。在「解析器」里解析一个 GitHub 链接，就能下载 Release 里的软件。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (vmDownloads.dlMultiSelect) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { vmDownloads.toggleDlAll() }) {
                    Text("全选", color = MaterialTheme.colorScheme.onError, fontSize = 13.sp)
                }
                TextButton(onClick = { vmDownloads.deleteSelectedDownloads(ctx) }) {
                    Text("删除(${vmDownloads.dlSel.size})", color = MaterialTheme.colorScheme.onError, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    vmDownloads.dlMultiSelect = false
                    vmDownloads.dlSel.clear()
                }) {
                    Text("取消", color = MaterialTheme.colorScheme.onError, fontSize = 13.sp)
                }
            }
        }
    }

    if (showLink != null) {
        AlertDialog(
            onDismissRequest = { showLink = null },
            title = { Text("文件直链") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("长按复制，或点下方按钮复制：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(showLink ?: "", fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(onClick = { copyText(ctx, showLink ?: "", "直链已复制"); showLink = null }) {
                    Text("复制")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    vmSettings.shareLink(ctx, showLink ?: "")
                    showLink = null
                }) { Text("分享") }
            }
        )
    }

    // 未授权 SAF 下载位置时，先引导去选文件夹，而不是静默下到私有目录
    if (AppState.needAuthGuide) {
        AlertDialog(
            onDismissRequest = { AppState.needAuthGuide = false },
            title = { Text("请先选择下载位置") },
            text = {
                Text(
                    "下载需要你用系统文件选择器授权一个文件夹（SAF），App 只把文件写进那里。" +
                        "未授权时不会悄悄下到私有目录。建议去「设置 → 下载」选一个文件夹。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(onClick = {
                    AppState.needAuthGuide = false
                    // 设置页的 NavHost 此刻还没组合（当前在下载 Tab），直接 navigate 会命中
                    // 「Navigation graph has not been set」。走跨 Tab 跳转的正规路子：
                    // 先切到设置 Tab，由 SettingsTab 挂载后消费 settingsDeepLink。
                    AppState.tab = AppState.TAB_SETTINGS
                    AppState.settingsDeepLink = "download"
                }) { Text("去设置选择") }
            },
            dismissButton = {
                TextButton(onClick = { AppState.needAuthGuide = false }) { Text("稍后") }
            }
        )
    }
}
