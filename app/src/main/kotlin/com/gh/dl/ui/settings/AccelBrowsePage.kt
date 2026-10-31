package com.gh.dl.ui.settings

// 「加速拆分」：设置 → 加速 → 浏览加速（三级页）。
// 与下载加速完全独立：此处管理 githubfast.com 类**网页**镜像站，
// 此类站点仅放行 git/curl 类 UA（浏览器 UA 返回 403），因此仅配合「命令行抓取 + 本地渲染」使用。
import com.gh.dl.ui.*
import com.gh.dl.data.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AccelBrowsePage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm = LocalSettingsVM.current
    var showAdd by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    // 下标对应 vm.browseMirrors；null 表示尚未测试
    val results = remember { mutableStateListOf<GhView.MirrorProbe?>().apply { repeat(vm.browseMirrors.size) { add(null) } } }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("浏览加速", onBack)

        CollapsibleNote(
            "浏览加速说明",
            "浏览页不直接联网：内容经抓取器获取（curl 类 UA）并净化后在本地渲染，" +
                "本档决定从哪个网页镜像站抓取。\n" +
                "· 文件加速前缀仅作用于文件下载，无法代理网页，故本档需单独配置镜像站；\n" +
                "· 此类站点仅放行 git/curl 类 UA，浏览器访问返回 403，加速仅对应用内浏览有效；\n" +
                "· 本档默认关闭，开启后页面内容经由第三方镜像站。\n" +
                "本档与「设置 → 网络 → DoH + 真实 IP 优选」均关闭时，国内网络环境可能无法访问，" +
                "此为不自动改道的预期行为；需要访问时启用其中一档。"
        )

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用浏览加速", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = vm.browseAccelOn, onCheckedChange = {
                        vm.browseAccelOn = it
                        Settings.put(ctx, "browseAccelOn", if (it) "1" else "0")
                    })
                }
                Text(
                    if (vm.browseAccelOn) "抓取顺序：优先镜像站，失败后直连 GitHub"
                    else "抓取顺序：仅直连 GitHub（真实 IP 优选见「设置 → 网络」）",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("网页镜像站（按顺序尝试）", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加镜像站", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (vm.browseMirrors.isEmpty()) {
                    Text(
                        "列表为空时自动使用内置的 " + GhView.DEFAULT_PAGE_MIRROR.removePrefix("https://") + "。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                vm.browseMirrors.forEachIndexed { i, m ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${i + 1}. " + m, fontSize = 12.sp)
                            results.getOrNull(i)?.let { r ->
                                Text(
                                    (if (r.ok) "可达 · " else "不可达 · ") + r.detail + "（${r.ms}ms）",
                                    fontSize = 10.sp,
                                    color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        IconButton(onClick = {
                            vm.removeBrowseMirror(ctx, m)
                            if (i < results.size) results.removeAt(i)
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        if (vm.browseMirrors.isEmpty()) {
                            toast(ctx, "列表为空，无可测速的镜像站")
                            return@Button
                        }
                        testing = true
                        // 列表在探测期间可能被用户修改（删除 / 添加），先取快照，避免运行时读取可变列表
                        val snap = vm.browseMirrors.toList()
                        Threads.start("镜像测速") {
                            val main = android.os.Handler(android.os.Looper.getMainLooper())
                            // probeMirror 内部已使用 runCatching（返回 MirrorProbe(false,…)
                            // ），但外层再次捕获：任何未预期异常均需将 testing 重置为 false，
                            // 否则按钮持续显示「测速中」
                            try {
                                snap.forEachIndexed { idx, m ->
                                    val r = GhView.probeMirror(m)
                                    main.post {
                                        if (idx < results.size) results[idx] = r
                                    }
                                }
                            } finally {
                                main.post { testing = false }
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (testing) "测速中…" else "测速")
                    }
                    if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    OutlinedButton(onClick = {
                        vm.resetBrowseMirrors(ctx)
                        results.clear()
                        toast(ctx, "已恢复内置镜像站")
                    }) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("恢复默认")
                    }
                }
            }
        }

        Text(
            "网页镜像站域名更换频繁，可通过「测速」验证可用性后调整顺序。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showAdd) {
        var manual by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加网页镜像站") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "填写站点根地址（不含仓库路径），每行一个；添加后可通过「测速」验证。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = manual, onValueChange = { manual = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://githubfast.com", fontSize = 12.sp) },
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val found = GhView.parseMirrors(manual)
                    if (found.isEmpty()) {
                        toast(ctx, "请输入以 http:// 或 https:// 开头的地址")
                        return@Button
                    }
                    vm.addBrowseMirrors(ctx, found)
                    results.clear()
                    showAdd = false
                    toast(ctx, "已添加 ${found.size} 个")
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
        )
    }
}
