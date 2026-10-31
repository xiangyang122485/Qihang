package com.gh.dl.ui.settings

// 「加速拆分」：设置 → 加速 → 下载加速（三级页）。
// 内容自原「网络」页的「加速代理」卡片拆分而来：开关、前缀输入、常用前缀、
// 已存前缀管理、下载后 SHA256 校验。原网络页仅保留跳转入口。
import com.gh.dl.ui.*
import com.gh.dl.data.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AccelDownloadPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm = LocalSettingsVM.current
    var showAdd by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("下载加速", onBack)

        CollapsibleNote(
            "下载加速说明",
            "原理：在 GitHub 文件链接前拼接前缀，由第三方中转代拉后回传。\n" +
                "· 仅作用于文件（Release、raw、源码包、jsDelivr）；网页无法经此代理，见「浏览加速」；\n" +
                "· 公共前缀任何人可搭建，存在替换文件内容的可能，域名亦可能变更，" +
                "故本档默认关闭，并默认启用「下载后强制校验 SHA256」；\n" +
                "· 启用时 GitHub Token 不发送至中转，令牌仅用于官方域名。"
        )

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用下载加速", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = vm.accelOn, onCheckedChange = {
                        vm.setAccelEnabled(ctx, it)
                        if (it && vm.custom.isBlank() && vm.accelPrefixes.isEmpty()) {
                            toast(ctx, "加速已开启，但还没有可用前缀；请先在下方添加")
                        }
                    })
                }
                OutlinedTextField(
                    value = vm.custom, onValueChange = {
                        // 保留正在输入的原文（此阶段自动补协议会干扰输入），
                        // 落盘后由 Accel.normalizePrefix 在使用时统一规范化
                        vm.onCustomEdited(ctx, it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("前缀地址") },
                    placeholder = { Text("https://gh-proxy.com") },
                    singleLine = true
                )
                Text("常用前缀（点击填入）：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Accel.BUILT_IN_PREFIXES.forEach { p ->
                        TextButton(onClick = { vm.activatePrefix(ctx, p) }) {
                            Text(p.removePrefix("https://"), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已保存的前缀", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加前缀", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text("点击填入；点删除按钮移除。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vm.accelPrefixes.isEmpty()) {
                    Text("暂无已保存前缀，点击右上角「+」添加。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    vm.accelPrefixes.forEach { p ->
                        val active = p == Accel.normalizePrefix(vm.custom)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    vm.activatePrefix(ctx, p)
                                    toast(ctx, "已填入：$p")
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                (if (active) "● " else "") + p,
                                fontSize = 12.sp,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                vm.removeAccelPrefix(ctx, p)
                                toast(ctx, "已删除：$p")
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        vm.verifyOn = !vm.verifyOn
                        Settings.put(ctx, "verifyOn", if (vm.verifyOn) "1" else "0")
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("下载后强制校验 SHA256", fontSize = 13.sp)
                        Text("开启加速时用于检测中转是否替换文件", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = vm.verifyOn, onCheckedChange = {
                        vm.verifyOn = it
                        Settings.put(ctx, "verifyOn", if (it) "1" else "0")
                    })
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }

    if (showAdd) AddPrefixDialog(onClose = { showAdd = false })
}
