package com.gh.dl.ui.settings

// 设置 Tab 去重重构：克隆分组页。末尾增加「SSH 密钥管理」入口（设置中唯一的 SSH 入口）。
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
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
import androidx.navigation.NavHostController

@Composable
fun ClonePage(nav: NavHostController, onBack: () -> Unit) {
    val vmSettings = LocalSettingsVM.current

    val ctx = LocalContext.current
    val keyReady = AppState.pubKey.isNotBlank()

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("克隆选项", onBack)

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("克隆选项（总开关）", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (vmSettings.cloneOn) "已开启 · 详见下方设置"
                            else "已关闭 · 默认方式（JGit SSH 直连，不拉取 LFS）",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = vmSettings.cloneOn, onCheckedChange = {
                        vmSettings.cloneOn = it
                        Settings.put(ctx, "cloneOn", if (it) "1" else "0")
                    })
                }
                if (!vmSettings.cloneOn) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "总开关关闭时以下设置不生效。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.warning
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable {
                        vmSettings.lfsOn = !vmSettings.lfsOn
                        Settings.put(ctx, "lfsOn", if (vmSettings.lfsOn) "1" else "0")
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动拉取 Git LFS 大文件", fontSize = 13.sp)
                        Text("JGit 默认仅获取指针文件，开启后替换为真实文件", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = vmSettings.lfsOn, onCheckedChange = {
                        vmSettings.lfsOn = it
                        Settings.put(ctx, "lfsOn", if (it) "1" else "0")
                    })
                }
                Spacer(Modifier.height(6.dp))
                Text("克隆引擎", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "jgit" to "JGit（SSH 直连）",
                        "libgit2" to "libgit2（HTTPS 加速）"
                    ).forEach { (id, label) ->
                        val sel = vmSettings.engine == id
                        TextButton(onClick = {
                            vmSettings.engine = id
                            Settings.put(ctx, "engine", id)
                        }) {
                            Text(
                                (if (sel) "● " else "") + label,
                                fontSize = 11.sp,
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("libgit2 通道（仅 libgit2 引擎生效）", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "https" to "HTTPS（加速代理）",
                        "ssh" to "SSH（22 端口直连）"
                    ).forEach { (id, label) ->
                        val sel = vmSettings.sshChannel == id
                        TextButton(onClick = {
                            vmSettings.sshChannel = id
                            Settings.put(ctx, "sshChannel", id)
                        }) {
                            Text(
                                (if (sel) "● " else "") + label,
                                fontSize = 11.sp,
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ---- SSH 密钥管理（设置中唯一的 SSH 入口） ----
        NavRow(
            icon = Icons.Filled.Key,
            title = "SSH 密钥管理",
            subtitle = if (keyReady) "已生成 · 本机可独立克隆" else "未配置 · 点击生成",
            onClick = { nav.navigate(SettingsRoute.Ssh.route) }
        )
    }
}
