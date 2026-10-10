package com.gh.dl.ui.settings

// 第五轮「加速拆分」：设置 → 加速 → 浏览加速（三级页）。
// 与下载加速完全独立：这里管的是 githubfast.com 这类**网页**镜像站，
// 它们只放行 git/curl 类 UA（浏览器 UA 一律 403），所以只能配合「命令行抓取 + 本地渲染」用。
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
    // 下标对应 vm.browseMirrors；null 表示还没测过
    val results = remember { mutableStateListOf<GhView.MirrorProbe?>().apply { repeat(vm.browseMirrors.size) { add(null) } } }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("浏览加速", onBack)

        CollapsibleNote(
            "这套加速是什么（点击展开）",
            "浏览页不联网：内容是用命令行方式（curl 类 UA）抓下来、净化后本地渲染的。" +
                "这里选的就是「去哪个网页镜像站抓」。\n" +
                "为什么必须是镜像而不是下载那套前缀：文件前缀代理不了网页，" +
                "把仓库主页交给它，它把自己的首页发回来。\n" +
                "为什么这些站不直接用浏览器打开：它们只放行 git/curl 类 UA，" +
                "浏览器 UA 一律 403——所以这一档加速只对 App 内的浏览有效。\n" +
                "关掉这一档就只直连 GitHub。\n" +
                "第九轮起这一档**默认关闭**（它会把你要访问的页面交给第三方镜像站中转，" +
                "属于敏感项，得由你决定开不开）。\n" +
                "关着且「设置 → 网络 → DoH + 真实 IP 优选」也关着时，国内网络下大概率打不开 —— " +
                "这不是 Bug，是不替你暗中走旁路的代价；打不开时自己在这两档里选一个开。"
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
                    if (vm.browseAccelOn) "抓取顺序：先镜像站，失败再直连 GitHub"
                    else "抓取顺序：只直连 GitHub（不再优先镜像站；是否走真实 IP 优选看「设置 → 网络」那一档）",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("网页镜像站（按顺序试）", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加镜像站", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (vm.browseMirrors.isEmpty()) {
                    Text(
                        "列表为空时自动用内置的 " + GhView.DEFAULT_PAGE_MIRROR.removePrefix("https://") + "。",
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
                            toast(ctx, "列表为空，没有可测的镜像站")
                            return@Button
                        }
                        testing = true
                        // 列表在探测期间可能被用户改（删/加），先取快照，避免边跑边读可变列表
                        val snap = vm.browseMirrors.toList()
                        Thread {
                            val main = android.os.Handler(android.os.Looper.getMainLooper())
                            // probeMirror 自己已经 runCatching（返回 MirrorProbe(false,…)），
                            // 但这层再兜一次：任何未预期抛出都得让 testing 落回 false，
                            // 否则按钮永远「测速中」
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
                        }.start()
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
            "提示：这类站换域名很频繁。实测 hub.gitmirror.com、kkgithub.com、bgithub.xyz、ghp.ci " +
                "当前都连不通，只剩 githubfast.com 可用；你也可以把自己知道的站加进来排在前面。",
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
                        "填站点根地址（不要带仓库路径），一行一个。添加后可用「测速」验证是不是真能返回仓库页。",
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
