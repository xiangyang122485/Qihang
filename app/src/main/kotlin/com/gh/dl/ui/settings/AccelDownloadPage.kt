package com.gh.dl.ui.settings

// 第五轮「加速拆分」：设置 → 加速 → 下载加速（三级页）。
// 内容是从原「网络」页那张「加速代理」大卡片里搬出来的：开关、前缀输入、常用前缀、
// 已存前缀管理、下载后 SHA256 校验。原网络页只留一句跳转，不再堆说明。
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
            "这套加速是什么、有什么风险（点击展开）",
            "原理：在 GitHub 文件链接前拼一个前缀，由第三方中转代拉再回传。\n" +
                "只作用于**文件**（Release、raw、源码包、jsDelivr）；网页它代理不了，" +
                "浏览页用的是另一套，见「浏览加速」。\n" +
                "风险：公共实例任何人都能搭，它可能替换你下载的二进制、也可能随时换域名跑路。" +
                "所以默认关闭，且下面那个「下载后强制校验 SHA256」建议一直开着。\n" +
                "隐私：开启时绝不会把你的 GitHub Token 发给中转（令牌只发给官方域名）。\n" +
                "当前实测可用：gh-proxy.com、gh-proxy.org。已失效的：mirror.ghproxy.com→502、" +
                "ghproxy.net→403、gh.llkk.cc→502、hub.gitmirror.com→502、github.akams.cn→404。"
        )

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用下载加速", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = vm.accelOn, onCheckedChange = {
                        vm.accelOn = it
                        Settings.put(ctx, "accelOn", if (it) "1" else "0")
                        vm.channel =
                            if (it && vm.custom.isNotBlank()) Channels.CUSTOM else Channels.OFFICIAL
                        Settings.put(ctx, "channel", vm.channel)
                        Net.rebuild()
                    })
                }
                OutlinedTextField(
                    value = vm.custom, onValueChange = {
                        vm.custom = it
                        Settings.put(ctx, "custom", it)
                        if (vm.accelOn) {
                            vm.channel = if (it.isNotBlank()) Channels.CUSTOM else Channels.OFFICIAL
                            Net.rebuild()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("前缀地址") },
                    placeholder = { Text("https://gh-proxy.com") },
                    singleLine = true
                )
                Text("常用前缀（点一下填入，仅列实测可用的）：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("https://gh-proxy.com", "https://gh-proxy.org").forEach { p ->
                        TextButton(onClick = {
                            vm.custom = p
                            Settings.put(ctx, "custom", p)
                            if (vm.accelOn) {
                                vm.channel = Channels.CUSTOM
                                Net.rebuild()
                            }
                        }) { Text(p.removePrefix("https://"), fontSize = 11.sp) }
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
                Text("点一下填入，点垃圾桶删除。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vm.accelPrefixes.isEmpty()) {
                    Text("还没有保存的前缀，点右上角「+」添加。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    vm.accelPrefixes.forEach { p ->
                        val active = p == vm.custom
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    vm.custom = p
                                    Settings.put(ctx, "custom", p)
                                    if (vm.accelOn) {
                                        vm.channel = Channels.CUSTOM
                                        Net.rebuild()
                                    }
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
                        Text("开了加速就建议一直开着：能发现中转有没有换掉你的文件", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
