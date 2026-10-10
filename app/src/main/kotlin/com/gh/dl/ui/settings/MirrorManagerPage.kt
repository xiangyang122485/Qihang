package com.gh.dl.ui.settings

// 镜像源管理：导入/删除/测速/设默认 高校镜像（清华·华为云·中科大等）。
import com.gh.dl.ui.*
import com.gh.dl.data.*

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MirrorManagerPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vmParser = LocalParserVM.current

    var mirrors by remember { mutableStateOf(MirrorManager.list(ctx)) }
    var testing by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    val refresh = { mirrors = MirrorManager.list(ctx) }

    val setDefaultAndSync: (String) -> Unit = { id ->
        MirrorManager.setDefault(ctx, id)
        vmParser.domestic = id
        vmParser.applySource(ctx)
        refresh()
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = try {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
            } catch (e: Exception) { "" }
            val n = MirrorManager.importFromText(ctx, text)
            if (n > 0) {
                toast(ctx, "已导入 $n 个镜像源")
                refresh()
            } else {
                toast(ctx, "这个文件里没解析到 http(s) 镜像地址")
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("镜像源管理", onBack)

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CollapsibleNote(
                    "这些镜像是干什么用的（点击展开）",
                    "高校镜像（清华·华为云·中科大等）作为直连目标站，用于下载 GitHub Release / 源码包。\n" +
                        "默认镜像会驱动「国内镜像」线路的下载地址；可导入自建/内网节点，可删除、可测速排序。\n" +
                        "它们是**下载**用的直连站，和「设置 → 加速 → 浏览加速」里那批网页镜像站是两回事。"
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = {
                        testing = true
                        Thread {
                            // try/finally 是必须的：speedTest 里是一串 HEAD 请求，
                            // 任何一个抛出都会让这条线程死在半路 —— 原来 testing 就永远停在 true，
                            // 按钮一直显示「测速中…」且再也点不动，只能退出这页重来。
                            var err: String? = null
                            try { MirrorManager.speedTest(ctx) } catch (e: Exception) {
                                err = e.message ?: e.javaClass.simpleName
                            }
                            val msg = if (err == null) "测速完成" else "测速中断：$err"
                            Handler(Looper.getMainLooper()).post {
                                testing = false
                                refresh()
                                toast(ctx, msg)
                            }
                        }.start()
                    }) {
                        Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (testing) "测速中…" else "测速（HEAD）")
                    }
                    OutlinedButton(onClick = { showImport = true }) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导入")
                    }
                    OutlinedButton(onClick = { showAdd = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加")
                    }
                    OutlinedButton(onClick = {
                        MirrorManager.restoreDefaults(ctx)
                        vmParser.domestic = "tuna"
                        vmParser.applySource(ctx)
                        refresh()
                        toast(ctx, "已恢复内置 9 家镜像")
                    }) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("恢复默认")
                    }
                }
            }
        }

        if (mirrors.isEmpty()) {
            GhCard(Modifier.fillMaxWidth()) {
                Text("还没有镜像源，点上方「添加」或「导入」。", Modifier.padding(14.dp),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            mirrors.forEach { m ->
                GhCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = m.isDefault, onClick = { setDefaultAndSync(m.id) })
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                if (m.isDefault) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Filled.CheckCircle, contentDescription = "默认",
                                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(m.url, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (m.latencyMs >= 0) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (m.available) "可达 · ${m.latencyMs}ms" else "不可达（${m.latencyMs}ms）",
                                    fontSize = 10.sp,
                                    color = if (m.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        IconButton(onClick = {
                            MirrorManager.remove(ctx, m.id)
                            val def = MirrorManager.defaultId(ctx)
                            if (def != null) {
                                vmParser.domestic = def
                                vmParser.applySource(ctx)
                            }
                            refresh()
                            toast(ctx, "已删除：${m.name}")
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        CollapsibleNote(
            "边界说明（点击展开）",
            "这些镜像直连 GitHub 公开开源资源，不涉及私有仓库与 SSH。\n" +
                "下载加速前缀（ghproxy 类）在「设置 → 加速 → 下载加速」管理，" +
                "浏览用的网页镜像站在「设置 → 加速 → 浏览加速」管理，三者互不影响。"
        )
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加镜像源") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("名称（可空，如 清华大学）") }
                    )
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("镜像 base 地址") },
                        placeholder = { Text("https://mirrors.xxx.edu.cn/github-release/") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (MirrorManager.add(ctx, name, url)) {
                        val def = MirrorManager.defaultId(ctx)
                        if (def != null) { vmParser.domestic = def; vmParser.applySource(ctx) }
                        refresh(); showAdd = false; toast(ctx, "已添加")
                    } else {
                        toast(ctx, "地址需以 http(s):// 开头，且不能重复")
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
        )
    }

    if (showImport) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入镜像源") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("支持 TXT（每行 名称|https://地址 或纯地址，# 开头为注释）或 JSON（url 数组 / 含 name+url 的对象数组）。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        fileLauncher.launch(arrayOf("text/*", "application/json", "*/*"))
                        showImport = false
                    }) { Text("从本地文件导入（txt / json）") }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        label = { Text("或直接粘贴内容") },
                        placeholder = { Text("清华|https://mirrors.tuna.tsinghua.edu.cn/github-release/\nhttps://mirror.xxx/github-release/") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val n = MirrorManager.importFromText(ctx, text)
                    if (n > 0) {
                        val def = MirrorManager.defaultId(ctx)
                        if (def != null) { vmParser.domestic = def; vmParser.applySource(ctx) }
                        refresh(); showImport = false; toast(ctx, "已导入 $n 个")
                    } else {
                        toast(ctx, "没解析到 http(s) 镜像地址")
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("取消") } }
        )
    }
}
