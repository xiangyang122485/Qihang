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
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh.dl.data.SshKeys

/** 把当前激活钥匙的公钥/指纹同步进 AppState（供其它页面展示） */
fun refreshKey(ctx: android.content.Context) {
    val a = SshKeys.active(ctx)
    AppState.pubKey = a?.pub ?: ""
    AppState.fingerprint = a?.fp ?: ""
}

/**
 * SSH 密钥管理（设置 → 克隆 → SSH 密钥管理，设置里唯一的 SSH 入口）。
 *
 * 钥匙用 Android Keystore 加密后存在本机（只有本 App 能解密），可持久保存、可删除、可多把并存。
 * 已经加到 GitHub 的钥匙不会因为“过期”消失——它们都在这儿列着，点「设为当前」即可用于克隆。
 *
 * 阶段 4 重构：不再做「SSH 第1步 / 第2步」独立页。
 *  - 还没有任何钥匙：顶部显示「首次使用引导」卡片，整合 生成钥匙 / 第1步复制公钥加 GitHub / 第2步测线路克隆；
 *  - 已经有钥匙：顶部显示「我的钥匙」列表，下面仍保留生成新钥匙 / 导入 / 第1步 / 第2步 / 合规说明。
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
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("SSH 密钥管理", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("状态：", fontSize = 13.sp)
                    Text(
                        if (keys.isEmpty()) "还没有钥匙" else "共 ${keys.size} 把",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (keys.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "GitHub 的 443 端口在这个网络里会被重置，22 端口（git 官方 SSH 协议）是通的。" +
                        "钥匙用手机本地加密保存（仅本 App 能读取），生成后把公钥加到 GitHub 即可克隆。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 首次使用引导（没有任何钥匙时） ----
        if (keys.isEmpty()) {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("首次使用 SSH 克隆（引导）", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "三步搞定：① 生成钥匙 → ② 复制公钥加到 GitHub → ③ 测线路并克隆。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("第 1 步：先生成一把钥匙", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("给这把钥匙起个名字，如 手机A", fontSize = 12.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            genBusy = true
                            Thread {
                                try {
                                    SshKeys.generate(ctx, label.trim())
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        label = ""
                                        genBusy = false
                                        reload()
                                        toast(ctx, "钥匙已生成并设为当前")
                                    }
                                } catch (e: Throwable) {
                                    android.util.Log.e("gh-ssh", "genKey failed", e)
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        genBusy = false
                                        toast(ctx, "生成失败：$e")
                                    }
                                }
                            }.start()
                        },
                        enabled = !genBusy
                    ) {
                        if (genBusy) CircularProgressIndicator(Modifier.width(16.dp).height(16.dp))
                        else Text("生成并保存")
                    }
                    Spacer(Modifier.height(8.dp))
                    if (AppState.pubKey.isNotBlank()) {
                        Text("第 2 步：复制公钥加到 GitHub", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Row {
                            Button(onClick = { copyText(ctx, AppState.pubKey, "公钥已复制") }) {
                                Text("复制公钥")
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/keys"))
                                )
                            }) { Text("打开 GitHub 添加页") }
                        }
                        if (AppState.fingerprint.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("指纹 ${AppState.fingerprint}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "加完后回 GitHub 列表页核对指纹是否一致，一致才算真的保存成功。",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text("点上方「生成并保存」后，这里会出现复制公钥的按钮。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            "先去「解析器」里粘贴一个仓库链接，这里就能一键克隆。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Button(
                            onClick = { startClone(ctx, vmParser.slug) },
                            enabled = !CloneJob.running
                        ) { Text("克隆 ${vmParser.slug}") }
                        if (AppState.pickedIp.isBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("没测线路也行，会自动优选一条。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ---- 我的钥匙列表（已有钥匙） ----
        if (keys.isNotEmpty()) {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("我的钥匙", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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

        // ---- 生成新钥匙（已有钥匙时，避免和首次引导卡重复） ----
        if (keys.isNotEmpty()) {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("生成新钥匙", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "钥匙只加密保存在手机本地，不会上传。生成后可「设为当前」用于克隆。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("给这把钥匙起个名字，如 手机A", fontSize = 12.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            genBusy = true
                            Thread {
                                try {
                                    SshKeys.generate(ctx, label.trim())
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        label = ""
                                        genBusy = false
                                        reload()
                                        toast(ctx, "钥匙已生成并设为当前")
                                    }
                                } catch (e: Throwable) {
                                    android.util.Log.e("gh-ssh", "genKey failed", e)
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        genBusy = false
                                        toast(ctx, "生成失败：$e")
                                    }
                                }
                            }.start()
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
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            importBusy = true
                            Thread {
                                try {
                                    SshKeys.importPem(ctx, importLabel.trim(), importText)
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        importText = ""
                                        importLabel = ""
                                        importBusy = false
                                        showImport = false
                                        reload()
                                        toast(ctx, "私钥已加密导入")
                                    }
                                } catch (e: Throwable) {
                                    android.util.Log.e("gh-ssh", "import failed", e)
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        importBusy = false
                                        toast(ctx, "导入失败：私钥格式不对或已损坏")
                                    }
                                }
                            }.start()
                        },
                        enabled = importText.isNotBlank() && !importBusy
                    ) { Text("加密导入") }
                }
            }
        }

        // ---- 第 1 步 / 第 2 步（已有钥匙、且已生成公钥时） ----
        if (keys.isNotEmpty() && AppState.pubKey.isNotBlank()) {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("第 1 步：把公钥加到 GitHub", fontWeight = FontWeight.Bold)
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
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/keys"))
                            )
                        }) { Text("打开 GitHub 添加页") }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (AppState.fingerprint.isNotBlank()) {
                        Text("指纹 ${AppState.fingerprint}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "加完后回 GitHub 列表页核对指纹是否一致，一致才算真的保存成功。",
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
                        "克隆会拉完整提交历史，几十 MB 起步，可能要好几分钟。" +
                            "已经在后台服务里跑，切走或锁屏都不会中断；" +
                            "连续 90 秒没有任何新数据会自动断开并说明原因。",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    if (vmParser.slug.isBlank()) {
                        Text(
                            "先去「解析器」里粘贴一个仓库链接，这里就能一键克隆。",
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
                                "没测线路也行，会自动优选一条。",
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
                    "只连 GitHub 官方服务器（github.com 的真实 IP，22 端口）。" +
                        "克隆仅走 GitHub 官方 SSH 服务（22 端口），不经过任何代理或中转。" +
                        "私钥用 Android Keystore 加密存储，只有本 App 能解密，不会以明文落盘到共享位置。",
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
            title = { Text("删除这把钥匙？") },
            text = {
                Text(
                    "「${delEntry?.label ?: "该钥匙"}」会被永久删除。" +
                        (if (delEntry?.id == activeId) "\n它当前正在使用，删除后需另选一把。" else "") +
                        "\nGitHub 上已添加的那条也会失效，需要把新公钥重新加一次。"
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
 * 解析页上的「SSH 直连克隆」提示卡：三条常规通道都失败时才出现。
 * 钥匙管理在设置里，这里只放一个动作入口。
 */
@Composable
fun SshFallbackCard() {
    val vmParser = LocalParserVM.current

    val ctx = LocalContext.current
    LaunchedEffect(Unit) { refreshKey(ctx) }

    GhCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("还有一条通道：SSH 直连克隆", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "走 github.com 的 22 端口直连官方服务器，能拿到带完整提交历史的仓库。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            if (AppState.pubKey.isBlank()) {
                Text("需要先配置 SSH 密钥。", fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    AppState.settingsDeepLink = "ssh"
                    AppState.tab = AppState.TAB_SETTINGS
                }) { Text("去设置里配置") }
            } else {
                Button(
                    onClick = { startClone(ctx, vmParser.slug) },
                    enabled = !CloneJob.running && vmParser.slug.isNotBlank()
                ) { Text("SSH 克隆 ${vmParser.slug}") }
            }
        }
    }
}
