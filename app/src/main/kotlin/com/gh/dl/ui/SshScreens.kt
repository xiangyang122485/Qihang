package com.gh.dl.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh.dl.data.SshKeys
import com.gh.dl.data.Threads

/** 将当前激活密钥的公钥/指纹同步进 AppState（供其他页面展示） */
fun refreshKey(ctx: android.content.Context) {
    val a = SshKeys.active(ctx)
    AppState.pubKey = a?.pub ?: ""
    AppState.fingerprint = a?.fp ?: ""
}

/**
 * SSH 密钥管理（设置 → 克隆 → SSH 密钥管理，设置中唯一的 SSH 入口）。
 *
 * 密钥使用 Android Keystore 加密后存储在本机（仅本应用可解密），可持久保存、可删除、可多份并存。
 * 已添加到 GitHub 的密钥不会因「过期」而消失——所有密钥均在此处列出，点击「设为当前」即可用于克隆。
 *
 * 阶段 4 重构：不再使用「SSH 第 1 步 / 第 2 步」独立页面。
 *  - 尚无任何密钥：顶部显示「首次使用引导」卡片，整合生成密钥 / 第 1 步复制公钥添加到 GitHub / 第 2 步测线路克隆；
 *  - 已有密钥：顶部显示「我的密钥」列表，下方仍保留生成新密钥 / 导入 / 第 1 步 / 第 2 步 / 合规说明。
 */
@Composable
fun SshKeyScreen(onBack: () -> Unit) {
    val vmParser = LocalParserVM.current

    val ctx = LocalContext.current
    var keys by remember { mutableStateOf(SshKeys.list(ctx)) }
    var activeId by remember { mutableStateOf(SshKeys.activeId(ctx)) }
    var label by remember { mutableStateOf("") }
    var genBusy by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importLabel by remember { mutableStateOf("") }
    var importPass by remember { mutableStateOf("") }
    var importBusy by remember { mutableStateOf(false) }
    var confirmDelId by remember { mutableStateOf<String?>(null) }

    fun reload() {
        keys = SshKeys.list(ctx)
        activeId = SshKeys.activeId(ctx)
        refreshKey(ctx)
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("SSH 密钥管理", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("状态：", fontSize = 13.sp)
                    Text(
                        if (keys.isEmpty()) "尚无密钥" else "共 ${keys.size} 份",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (keys.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "GitHub 443 端口在部分网络环境被重置，22 端口（SSH）可正常连接。" +
                        "密钥在本机加密保存，仅本应用可读取；生成后将公钥添加至 GitHub 即可克隆。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 首次使用引导（尚无任何密钥时） ----
        if (keys.isEmpty()) {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("首次使用 SSH 克隆（引导）", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "步骤：① 生成密钥 → ② 公钥添加至 GitHub → ③ 测线路并克隆。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("第 1 步：生成密钥", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("密钥名称，如「手机A」", fontSize = 12.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            genBusy = true
                            Threads.start("生成密钥") {
                                try {
                                    SshKeys.generate(ctx, label.trim())
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        label = ""
                                        genBusy = false
                                        reload()
                                        toast(ctx, "密钥已生成并设为当前")
                                    }
                                } catch (e: Throwable) {
                                    android.util.Log.e("gh-ssh", "genKey failed", e)
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        genBusy = false
                                        toast(ctx, "生成失败：$e")
                                    }
                                }
                            }
                        },
                        enabled = !genBusy
                    ) {
                        if (genBusy) CircularProgressIndicator(Modifier.width(16.dp).height(16.dp))
                        else Text("生成并保存")
                    }
                    Spacer(Modifier.height(8.dp))
                    if (AppState.pubKey.isNotBlank()) {
                        Text("第 2 步：复制公钥添加到 GitHub", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Row {
                            Button(onClick = { copyText(ctx, AppState.pubKey, "公钥已复制") }) {
                                Text("复制公钥")
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                openUrl(ctx, "https://github.com/settings/keys")
                            }) { Text("打开 GitHub 添加页") }
                        }
                        if (AppState.fingerprint.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("指纹 ${AppState.fingerprint}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "添加后在 GitHub 列表页核对指纹，一致即为保存成功。",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text("点上方「生成并保存」后显示复制公钥按钮。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("第 3 步：测线路并克隆", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { doPickLine(ctx) }, enabled = !AppState.lineBusy) {
                            Text("测线路")
                        }
                        Spacer(Modifier.width(10.dp))
                        if (AppState.ipInfo.isNotBlank()) {
                            Text(AppState.ipInfo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (vmParser.slug.isBlank()) {
                        Text(
                            "先在「解析器」粘贴仓库链接，再在此处执行克隆。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Button(
                            onClick = { startClone(ctx, vmParser.slug) },
                            enabled = !CloneJob.running
                        ) { Text("克隆 ${vmParser.slug}") }
                        if (AppState.pickedIp.isBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("未测线路时自动优选。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ---- 我的密钥列表（已有密钥） ----
        if (keys.isNotEmpty()) {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("我的密钥", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    keys.forEach { e ->
                        val isActive = e.id == activeId
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.label, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                    modifier = Modifier.weight(1f))
                                if (isActive) {
                                    Text("当前使用中", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                            if (e.fp.isNotBlank()) {
                                Text(e.fp, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Row {
                                if (!isActive) {
                                    TextButton(onClick = {
                                        SshKeys.setActive(ctx, e.id)
                                        reload()
                                    }) { Text("设为当前", fontSize = 12.sp) }
                                }
                                TextButton(onClick = { copyText(ctx, e.pub, "公钥已复制") }) {
                                    Text("复制公钥", fontSize = 12.sp)
                                }
                                TextButton(onClick = { confirmDelId = e.id }) {
                                    Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- 生成新密钥（已有密钥时，避免与首次引导卡重复） ----
        if (keys.isNotEmpty()) {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("生成新密钥", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "密钥在本机加密保存，不上传；生成后可设为当前用于克隆。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("密钥名称，如「手机A」", fontSize = 12.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            genBusy = true
                            Threads.start("激活密钥") {
                                try {
                                    SshKeys.generate(ctx, label.trim())
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        label = ""
                                        genBusy = false
                                        reload()
                                        toast(ctx, "密钥已生成并设为当前")
                                    }
                                } catch (e: Throwable) {
                                    android.util.Log.e("gh-ssh", "genKey failed", e)
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        genBusy = false
                                        toast(ctx, "生成失败：$e")
                                    }
                                }
                            }
                        },
                        enabled = !genBusy
                    ) {
                        if (genBusy) CircularProgressIndicator(Modifier.width(16.dp).height(16.dp))
                        else Text("生成并保存")
                    }
                }
            }
        }

        // ---- 导入已有私钥 ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("导入已有私钥", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showImport = !showImport }) {
                        Text(if (showImport) "收起" else "展开", fontSize = 12.sp)
                    }
                }
                if (showImport) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = importLabel,
                        onValueChange = { importLabel = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("备注名（可选）", fontSize = 12.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        placeholder = { Text("粘贴 OpenSSH / PKCS8 私钥（-----BEGIN ...-----）", fontSize = 12.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                        maxLines = 12
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = importPass,
                        onValueChange = { importPass = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("私钥口令（无口令留空）", fontSize = 12.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            importBusy = true
                            val pem = importText
                            val name = importLabel.trim()
                            val pass = importPass
                            Threads.start("导入密钥") {
                                val h = android.os.Handler(android.os.Looper.getMainLooper())
                                try {
                                    SshKeys.importPem(ctx, name, pem, pass)
                                    h.post {
                                        importText = ""
                                        importLabel = ""
                                        importPass = ""
                                        importBusy = false
                                        showImport = false
                                        reload()
                                        toast(ctx, "私钥已加密导入")
                                    }
                                } catch (e: Throwable) {
                                    android.util.Log.e("gh-ssh", "import failed", e)
                                    val msg = e.message ?: ""
                                    h.post {
                                        importBusy = false
                                        toast(ctx, if (msg.isBlank()) "导入失败：私钥格式不正确或已损坏" else "导入失败：$msg")
                                    }
                                }
                            }
                        },
                        enabled = importText.isNotBlank() && !importBusy
                    ) { Text("加密导入") }
                }
            }
        }

        // ---- 第 1 步 / 第 2 步（已有密钥、且已生成公钥时） ----
        if (keys.isNotEmpty() && AppState.pubKey.isNotBlank()) {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("第 1 步：将公钥添加到 GitHub", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "GitHub → Settings → SSH and GPG keys → New SSH key，" +
                            "Key type 选 Authentication Key。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = { copyText(ctx, AppState.pubKey, "公钥已复制") }) {
                            Text("复制公钥")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            openUrl(ctx, "https://github.com/settings/keys")
                        }) { Text("打开 GitHub 添加页") }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (AppState.fingerprint.isNotBlank()) {
                        Text("指纹 ${AppState.fingerprint}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "添加后请返回 GitHub 列表页核对指纹是否一致，一致才表示保存成功。",
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (keys.isNotEmpty()) {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("第 2 步：测线路并克隆", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { doPickLine(ctx) }, enabled = !AppState.lineBusy) {
                            Text("测线路")
                        }
                        Spacer(Modifier.width(10.dp))
                        if (AppState.ipInfo.isNotBlank()) {
                            Text(AppState.ipInfo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "克隆拉取完整提交历史，起始大小约数十 MB，耗时可能较长。" +
                            "任务运行于后台服务，切换页面或锁屏不中断；" +
                            "连续 90 秒无新数据时自动断开并给出原因。",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    if (vmParser.slug.isBlank()) {
                        Text(
                            "先在「解析器」粘贴仓库链接，再在此处执行克隆。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Button(
                            onClick = { startClone(ctx, vmParser.slug) },
                            enabled = !CloneJob.running
                        ) { Text("克隆 ${vmParser.slug}") }
                        if (AppState.pickedIp.isBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "未测线路时系统会自动优选。",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("合规与隐私说明", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "仅连接 GitHub 官方服务器（github.com 的真实 IP，22 端口）。" +
                        "克隆仅经 GitHub 官方 SSH 服务（22 端口），不经代理或中转。" +
                        "私钥以 Android Keystore 加密存储，仅本应用可解密，不明文写入共享位置。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ---- 删除确认 ----
    confirmDelId?.let { delId ->
        val delEntry = keys.firstOrNull { it.id == delId }
        AlertDialog(
            onDismissRequest = { confirmDelId = null },
            title = { Text("删除此密钥？") },
            text = {
                Text(
                    "「${delEntry?.label ?: "该密钥"}」将被永久删除。" +
                        (if (delEntry?.id == activeId) "\n该密钥正在使用中，删除后需另选一份。" else "") +
                        "\nGitHub 上添加的对应公钥需另行移除。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SshKeys.remove(ctx, delId)
                    confirmDelId = null
                    reload()
                    toast(ctx, "已删除")
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelId = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 解析页上的「SSH 直连克隆」提示卡：三条常规通道均失败时显示。
 * 密钥管理在设置中，此处仅提供操作入口。
 */
@Composable
fun SshFallbackCard() {
    val vmParser = LocalParserVM.current

    val ctx = LocalContext.current
    LaunchedEffect(Unit) { refreshKey(ctx) }

    GhCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("备用通道：SSH 直连克隆", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "通过 github.com 的 22 端口直连官方服务器，可获取包含完整提交历史的仓库。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            if (AppState.pubKey.isBlank()) {
                Text("需要先配置 SSH 密钥。", fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    AppState.settingsDeepLink = "ssh"
                    AppState.tab = AppState.TAB_SETTINGS
                }) { Text("前往设置配置") }
            } else {
                Button(
                    onClick = { startClone(ctx, vmParser.slug) },
                    enabled = !CloneJob.running && vmParser.slug.isNotBlank()
                ) { Text("SSH 克隆 ${vmParser.slug}") }
            }
        }
    }
}
