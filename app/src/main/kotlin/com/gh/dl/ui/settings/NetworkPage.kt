package com.gh.dl.ui.settings

// 「网络」二级页：线路选择 / GitHub Token / GitCode Token / HTTP 代理 / 镜像源管理 / 线路诊断。
// 第五轮把「加速代理」整块搬走了（→ 设置 → 加速 → 下载加速 / 浏览加速），
// 因为下载和浏览的加速机制完全不同，混在这一页里既看不懂也管不住。
import com.gh.dl.ui.*
import com.gh.dl.data.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController

@Composable
fun NetworkPage(nav: NavHostController, onBack: () -> Unit) {
    val vmParser = LocalParserVM.current
    val vmSettings = LocalSettingsVM.current
    val ctx = LocalContext.current

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("网络", onBack)

        // ---- 线路选择：GitHub 官方源 / 国内镜像（单选） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("线路选择", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                CollapsibleNote(
                    "这条管的是什么（点击展开）",
                    "只影响**解析和下载**去哪取文件：官方源直连（时断时通，不依赖中转）；" +
                        "国内镜像走高校 github-release 镜像（直连无中转）。\n" +
                        "它和「设置 → 加速」里的两档加速是独立的：加速是另一层改写，" +
                        "关掉这里选国内镜像时不需要开加速。"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "official" to "GitHub 官方源",
                        "domestic" to "国内镜像"
                    ).forEach { (id, label) ->
                        val sel = vmParser.source == id
                        TextButton(onClick = {
                            vmParser.source = id
                            vmParser.applySource(ctx)
                        }) {
                            Text(
                                (if (sel) "● " else "") + label,
                                fontSize = 12.sp,
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ---- DoH + 真实 IP 优选（第九轮：默认关闭） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DoH + 真实 IP 优选", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.weight(1f))
                    Switch(checked = vmSettings.dnsPinningOn, onCheckedChange = {
                        vmSettings.dnsPinningOn = it
                        Settings.put(ctx, "dnsPinningOn", if (it) "1" else "0")
                        // data 层不读 ViewModel：改完必须立刻落到 Net，否则界面开着、底层还在跑
                        // （或反过来，界面关了底下还在发请求）。
                        Net.dnsPinningOn = it
                    })
                }
                CollapsibleNote(
                    "这一档管什么（点击展开）",
                    "GitHub 域名常被 DNS 污染。开着这一档时，App 会拿你要访问的 GitHub 域名\n" +
                        "去问公共 DoH 服务（dns.alidns.com / doh.pub）要真实 IP，再逐个 IP 做真实\n" +
                        "HTTPS 探活，把最快那个记下来直接用（只连 GitHub 官方服务器，不经任何中转）。\n" +
                        "代价是：第三方 DoH 会知道「你在解析 github.com 这个域名」——\n" +
                        "它拿到的是域名，不是你具体访问哪个仓库，但仍然是把访问行为透露给了别人。\n" +
                        "所以第九轮把它改成**默认关闭**：关着时完全走系统 DNS、只直连，\n" +
                        "污染环境下会老实报「抓不开」，不会背着你走旁路。\n" +
                        "「线路诊断」不受这一档限制 —— 那是你主动点的，本来就该真测。"
                )
                Text(
                    if (vmSettings.dnsPinningOn) "已开启：会自动向第三方 DoH 查询域名并外连探活"
                    else "已关闭（默认）：只用系统 DNS 直连，不查询第三方 DoH",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 加速入口（三级页，第五轮从本页拆出） ----
        NavRow(
            icon = Icons.Filled.Speed,
            title = "加速设置",
            subtitle = "下载加速：" + (if (vmSettings.accelOn) "开" else "关（默认）") +
                " · 浏览加速：" + (if (vmSettings.browseAccelOn) "开" else "关（默认）"),
            onClick = { nav.navigate(SettingsRoute.Accel.route) }
        )

        // ---- GitHub Token（可选） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GitHub Token（可选）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                CollapsibleNote(
                    "GitHub Token 说明（点击展开）",
                    "官方线路通的时候，填 Token 能提高 API 速率限制；" +
                        "SSH 克隆用的不是它，是「克隆 → SSH 密钥管理」里那把手机钥匙。" +
                        "开启任何一档加速时，这个令牌都不会被发给中转。"
                )
                OutlinedTextField(
                    value = vmSettings.token, onValueChange = {
                        vmSettings.token = it
                        Settings.put(ctx, "token", it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("GitHub Token（可留空）") }, singleLine = true
                )
            }
        }

        // ---- GitCode Token（可选）----
        // 这个令牌的归属域名就是 gitcode.com，只发给它自己；绝不会被带进
        // GitHub 请求或任何加速代理前缀（Net.text 的 authHeader 只在显式调用时使用）。
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GitCode Token（可选）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                CollapsibleNote(
                    "GitCode Token 说明（点击展开）",
                    "留空时国内源搜索会跳过 GitCode（它的搜索接口要令牌，" +
                        "空发只会被挡回来）。令牌只在请求 gitcode.com 时带上，不会发给 GitHub 或任何中转。"
                )
                OutlinedTextField(
                    value = AppState.gitcodeToken,
                    onValueChange = {
                        AppState.gitcodeToken = it
                        Settings.put(ctx, "gitcodeToken", it.trim())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("GitCode 访问令牌（可留空）") }, singleLine = true
                )
            }
        }

        // ---- HTTP 代理（可选，系统层） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HTTP 代理（可选）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                CollapsibleNote(
                    "HTTP 代理说明（点击展开）",
                    "系统层 HTTP 代理（host:port），与你自己的代理软件配合用；" +
                        "和「加速」那两档是不同机制，一般只开一个。填完点下方保存才生效。"
                )
                OutlinedTextField(
                    value = vmSettings.proxy, onValueChange = {
                        vmSettings.proxy = it
                        Settings.put(ctx, "proxy", it)
                        applyProxy(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTP 代理 host:port（可留空）") }, singleLine = true
                )
            }
        }

        // ---- 镜像源管理入口 ----
        NavRow(
            icon = Icons.Filled.Storage,
            title = "镜像源管理",
            subtitle = "导入/删除/测速 高校镜像（清华·华为云·中科大等），设默认下载线路",
            onClick = { nav.navigate(SettingsRoute.Mirrors.route) }
        )

        // ---- 线路诊断入口 ----
        NavRow(
            icon = Icons.Filled.MonitorHeart,
            title = "线路诊断",
            subtitle = vmParser.netSummary.ifBlank { "看看当前网络对 GitHub 通不通" },
            onClick = { nav.navigate(SettingsRoute.Diag.route) }
        )
    }
}

/** host:port → Net 的代理字段（改了立即重建客户端，不需要再点保存） */
private fun applyProxy(value: String) {
    val p = value.split(":")
    if (p.size == 2) {
        Net.proxyHost = p[0].trim()
        Net.proxyPort = p[1].trim().toIntOrNull() ?: 0
    } else {
        Net.proxyHost = ""
        Net.proxyPort = 0
    }
    Net.rebuild()
}
