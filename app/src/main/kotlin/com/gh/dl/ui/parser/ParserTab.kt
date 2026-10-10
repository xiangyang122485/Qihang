package com.gh.dl.ui.parser

// 本文件从原 GhApp.kt 纯拆分而来（阶段1：按页拆分巨型文件，零逻辑改动）。
// 解析页相关的所有 composable 与私有辅助函数集中于此；函数签名、调用关系、业务逻辑一字未改。
// 跨页共享符号（AppState、动作函数、GhCard、SectionHeader、SshFallbackCard、CollapsibleNote、
// SelItem、CloneJob 等）均位于 com.gh.dl.ui，通过下方 star import 引入。
import com.gh.dl.ui.*
import com.gh.dl.data.*

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 解析器页「批量下载」多选时，在每个文件行前方加一个复选框。
 * 进入多选模式才显示；勾选状态写入 [LocalParserVM.current.parserSel]（以 url 去重）。
 */
@Composable
private fun SelLead(item: SelItem, content: @Composable RowScope.() -> Unit) {
    val vmParser = LocalParserVM.current

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (vmParser.parserMultiSelect) {
            Checkbox(
                checked = vmParser.parserSel.contains(item),
                onCheckedChange = {
                    if (it) {
                        if (!vmParser.parserSel.contains(item)) vmParser.parserSel.add(item)
                    } else {
                        vmParser.parserSel.remove(item)
                    }
                }
            )
        }
        content()
    }
}

// 批量下载的全选 / 下载 / 取消 已移至解析器页顶栏（固定置顶），不再用底部操作栏。

/**
 * 每个可下载文件行末尾的两个图标：复制直链 / 分享直链。
 * 直链是经过通道改写（官方/高校镜像/加速代理前缀）后的最终 URL，
 * 第三方下载器能直接吃 —— 内置引擎之外的一条兜底。
 */
@Composable
private fun LinkActions(rawUrl: String) {
    val vmSettings = LocalSettingsVM.current

    val ctx = LocalContext.current
    IconButton(onClick = { vmSettings.exportLink(ctx, rawUrl) }) {
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = "复制直链",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(18.dp)
        )
    }
    IconButton(onClick = { vmSettings.shareLink(ctx, rawUrl) }) {
        Icon(
            Icons.Filled.Share,
            contentDescription = "分享直链",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 线路切换的小按钮：选中显示绿色 ● */
@Composable
private fun LineChip(id: String, label: String, ctx: Context) {
    val vmParser = LocalParserVM.current

    val sel = vmParser.source == id
    TextButton(onClick = {
        vmParser.source = id
        vmParser.applySource(ctx)
        if (id == "domestic" && vmParser.slug.isNotBlank()) {
            vmParser.doMirror(ctx, preferBase = vmParser.domesticBase(ctx))
        }
    }) {
        Text(
            (if (sel) "● " else "") + label,
            fontSize = 12.sp,
            color = if (sel) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ParserTab() {
    val vmParser = LocalParserVM.current
    val vmDownloads = LocalDownloadsVM.current
    val vmBrowse = LocalBrowseVM.current

    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
        item {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("解析 GitHub 链接", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vmParser.input,
                        onValueChange = { vmParser.input = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://github.com/owner/repo", fontSize = 12.sp) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Spacer(Modifier.height(8.dp))
                    // 原本一个整条「解析」按钮，现在拆成左右两半：
                    //  红「解析」= 走 API 拉 Release / 源码树（本 App 的主功能）
                    //  蓝「浏览」= 跳到「浏览」Tab，用内嵌页面看这个仓库
                    Row(Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { if (vmParser.input.isNotBlank()) vmParser.doLoad(ctx, vmParser.input) },
                            enabled = vmParser.input.isNotBlank() && !vmParser.loading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.accentRed,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) { Text("解析") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { vmParser.browseUrl()?.let { vmBrowse.open(it) } },
                            enabled = vmParser.input.isNotBlank() && vmParser.browseUrl() != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.accentBlue,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) { Text("浏览") }
                    }
                }
            }
        }
        // 解析历史（第六轮新增）：点一条就填回输入框并重新解析。
        // 放在输入框正下方而不是页面底部，因为用户找它的时机就是「刚打开输入框的时候」。
        if (vmParser.history.isNotEmpty()) {
            item { HistoryCard() }
        }
        if (vmParser.input.isNotBlank()) {
            item {
                GhCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("当前链接", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(vmParser.input, fontSize = 13.sp)
                        }
                        TextButton(onClick = {
                            vmParser.input = ""
                            vmParser.slug = ""
                            vmParser.repo = null
                            vmParser.releases = emptyList()
                            vmParser.mirror = null
                            vmParser.mirrorAll = emptyList()
                            vmParser.jsd = false
                            vmParser.error = ""
                        }) { Text("清除", fontSize = 12.sp) }
                    }
                }
            }
        }

        item {
            // 线路切换：GitHub 官方源 ↔ 国内镜像（高校 github-release 镜像，直连无中转）
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("线路", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LineChip("official", "GitHub 官方源", ctx)
                        LineChip("domestic", "国内镜像", ctx)
                    }
                        if (vmParser.source == "domestic") {
                            Spacer(Modifier.height(10.dp))
                            Text("国内镜像站（点选优先；可在「设置→镜像源管理」导入/删除/测速）：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            val mirrors = MirrorManager.list(ctx)
                            if (mirrors.isEmpty()) {
                                Text("还没有镜像源，去「设置→镜像源管理」添加。", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            } else {
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    mirrors.forEach { src ->
                                        val sel = vmParser.domestic == src.id
                                        TextButton(onClick = {
                                            vmParser.domestic = src.id
                                            vmParser.applySource(ctx)
                                            if (vmParser.slug.isNotBlank()) {
                                                vmParser.doMirror(ctx, preferBase = vmParser.domesticBase(ctx))
                                            }
                                        }) {
                                            Text(
                                                (if (sel) "● " else "") + src.name +
                                                    if (src.latencyMs >= 0) (if (src.available) " ${src.latencyMs}ms" else " ✕") else "",
                                                fontSize = 12.sp,
                                                color = if (sel) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        }

        if (vmParser.netSummary.isNotBlank()) {
            item {
                Text(
                    "网络：" + vmParser.netSummary,
                    fontSize = 11.sp,
                    color = if (vmParser.netSummary.contains("可用")) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.warning
                )
            }
        }

        if (vmParser.loading) item { CircularProgressIndicator() }

        if (vmParser.error.isNotBlank()) {
            item {
                Text(vmParser.error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            if (vmParser.slug.isNotBlank()) item { SshFallbackCard() }
        }

        val r = vmParser.repo
        if (r != null) {
            item { SectionHeader("仓库") }
            item { RepoCard(r) }
            item { SourceCard(r) }

            item { SectionHeader("版本发布") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(
                        "all" to "全部", "android" to "Android", "windows" to "Windows",
                        "macos" to "macOS", "linux" to "Linux", "other" to "其它"
                    ).forEach { (k, label) ->
                        TextButton(onClick = { vmParser.filter = k }) {
                            Text(
                                label,
                                fontWeight = if (vmParser.filter == k) FontWeight.Bold
                                else FontWeight.Normal,
                                color = if (vmParser.filter == k) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
            // 用 items(..., key=) 而不是 forEach{item{}}：
            // 「批量下载」勾选、筛选开关、进度刷新都会让这一段重算，没有 key 时
            // Compose 只能按下标对齐 —— 列表长度一变（筛出/滤掉某个 Release），
            // 后面的卡片会整片错位重排，表现就是滚动时突然跳一下、勾选态串到别的卡上。
            items(vmParser.releases, key = { "rel-" + it.tag }) { rel ->
                val assets = rel.assets.filter {
                    vmParser.filter == "all" || com.gh.dl.data.osOf(it.name) == vmParser.filter
                }
                if (assets.isNotEmpty()) {
                    ReleaseCard(rel, assets) { u, n, s -> vmDownloads.startDownload(ctx, u, n, sizeHint = s) }
                }
            }
        }

        val mh = vmParser.mirror
        val all = vmParser.mirrorAll
        if (all.isNotEmpty() || mh != null || vmParser.mirrorMsg.isNotBlank()) {
            item { SectionHeader("镜像通道", accent = MaterialTheme.colorScheme.tertiary) }
            if (all.isNotEmpty()) {
                items(all.toList(), key = { "mvr-" + it.first }) { (ver, hit) ->
                    MirrorVersionCard(ver, hit)
                }
            } else if (mh != null) {
                item { MirrorCard(mh) }
            } else if (vmParser.mirrorMsg.isNotBlank()) {
                item { Text(vmParser.mirrorMsg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        if (vmParser.jsd) {
            item { SectionHeader("jsDelivr 源码", accent = MaterialTheme.colorScheme.tertiary) }
            item { JsdCard() }
        }
        }
    }
}

@Composable
private fun MirrorCard(mh: Mirror.Hit) {
    val vmParser = LocalParserVM.current
    val vmDownloads = LocalDownloadsVM.current

    val ctx = LocalContext.current
    GhCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("软件 · 国内镜像通道", fontWeight = FontWeight.Bold)
            Text(mh.slug, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("版本（点选切换）：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                mh.versions.take(24).forEach { v ->
                    TextButton(onClick = { vmParser.doMirror(ctx, v) }, enabled = !vmParser.mirrorBusy) {
                        Text(
                            if (Mirror.isLatest(v)) "最新" else Mirror.label(v),
                            fontSize = 12.sp,
                            fontWeight = if (v == mh.version) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (vmParser.mirrorMsg.isNotBlank()) {
                Text(vmParser.mirrorMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            mh.assets.filter { it.name != "sha256.sum" }.forEach { a ->
                SelLead(SelItem(a.url, a.name, size = a.size)) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontSize = 13.sp)
                        Text(
                            "${Mirror.kind(a.name)} · ${fmtSize(a.size)}",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        // 校验值要现下现算（几十 MB 的文件算 SHA256 是秒级），必须在后台；
                        // 但**必须有兜底**：原来这条裸线程没有异常出口，
                        // 网络断了 / 镜像站返回怪东西 → 线程静悄悄死掉，用户点了「校验」什么也没发生。
                        verifying(ctx, "校验值") { Mirror.sha256(mh, a.name) }
                    }) { Text("校验", fontSize = 12.sp) }
                    Spacer(Modifier.width(4.dp))
                    if (!vmParser.parserMultiSelect) {
                        Button(onClick = {
                            Thread {
                                // 校验值拿不到也照样下：runCatching 是必须的——
                                // 原来 Mirror.sha256 一抛（断网、镜像站 500）这条线程就死在这儿，
                                // MAIN.post 永远不执行，用户点「下载」表现为「按了没反应」。
                                val h = runCatching { Mirror.sha256(mh, a.name) }.getOrNull()
                                // startDownload 会写 job.running / AppState.tab / 下载列表，
                                // 这些都是 Compose 状态，必须回主线程改。
                                MAIN.post { vmDownloads.startDownload(ctx, a.url, a.name, h, sizeHint = a.size) }
                            }.start()
                        }) { Text("下载") }
                    }
                    LinkActions(a.url)
                }
            }
            Spacer(Modifier.height(6.dp))
            CollapsibleNote(
                "说明（点击展开）",
                "国内镜像站（中科大 / 南大 / 清华 / 华为云 / 北京理工 / 上海交大）同步了开源项目的 " +
                    "Release 产物，默认不经过任何第三方中转。点「校验」可复制官方 sha256 值；" +
                    "若你在「设置 → 网络通道」开启了加速代理，下载会经第三方转发，开启时默认强制比对校验值。"
            )
        }
    }
}

/**
 * 国内镜像里「某一个版本」的卡片：列出该版本下的单个文件（APK/EXE/DEB/IPA…），
 * 每个文件一个「下载」按钮 —— 只取这一个文件，不克隆整个仓库。
 */
@Composable
private fun MirrorVersionCard(ver: String, hit: Mirror.Hit) {
    val vmParser = LocalParserVM.current
    val vmDownloads = LocalDownloadsVM.current

    val ctx = LocalContext.current
    GhCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (Mirror.isLatest(ver)) "最新版本" else "版本 " + Mirror.label(ver),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(hit.mirror, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "下面每个文件可单独下载，不克隆仓库：",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            hit.assets.filter { it.name != "sha256.sum" }.forEach { a ->
                SelLead(SelItem(a.url, a.name, size = a.size)) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontSize = 13.sp)
                        Text(
                            "${platformOf(a.name)} · ${fmtSize(a.size)}",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        verifying(ctx, "校验值") { Mirror.sha256(hit, a.name) }
                    }) { Text("校验", fontSize = 12.sp) }
                    Spacer(Modifier.width(4.dp))
                    if (!vmParser.parserMultiSelect) {
                        Button(onClick = {
                            Thread {
                                // 同上面镜像通道那个按钮：算不出校验值也要照样下
                                val h = runCatching { Mirror.sha256(hit, a.name) }.getOrNull()
                                MAIN.post { vmDownloads.startDownload(ctx, a.url, a.name, h, sizeHint = a.size) }
                            }.start()
                        }) { Text("下载") }
                    }
                    LinkActions(a.url)
                }
            }
        }
    }
}

@Composable
private fun JsdCard() {
    val vmParser = LocalParserVM.current
    val vmDownloads = LocalDownloadsVM.current

    val ctx = LocalContext.current
    GhCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("源码 · jsDelivr 通道", fontWeight = FontWeight.Bold)
            Text(vmParser.slug, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("版本（点选切换）：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                vmParser.jsdVersions.take(20).forEach { v ->
                    TextButton(onClick = { vmParser.doTree(ctx, v) }) {
                        Text(
                            v, fontSize = 12.sp,
                            fontWeight = if (v == vmParser.jsdVer) FontWeight.Bold
                            else FontWeight.Normal
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { vmParser.doPack(ctx) },
                enabled = !vmParser.busy
            ) { Text("打包下载整仓 ZIP") }
            Spacer(Modifier.height(6.dp))
            if (vmParser.packMsg.isNotBlank()) {
                Text(vmParser.packMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            vmParser.jsdTree.take(120).forEach { f ->
                val u = Jsd.fileUrl(vmParser.slug, vmParser.jsdVer, f.path)
                SelLead(SelItem(u, f.path.substringAfterLast('/'), size = f.size)) {
                    Column(Modifier.weight(1f)) {
                        Text(f.path, fontSize = 12.sp)
                        Text(fmtSize(f.size), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!vmParser.parserMultiSelect) {
                        TextButton(onClick = {
                            vmDownloads.startDownload(ctx, u, f.path.substringAfterLast('/'), sizeHint = f.size)
                        }) { Text("下载", fontSize = 12.sp) }
                    }
                    LinkActions(u)
                }
            }
            if (vmParser.jsdTree.size > 120) {
                Text(
                    "还有 ${vmParser.jsdTree.size - 120} 个文件未列出，用「打包下载整仓 ZIP」一次拿全",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RepoCard(r: RepoInfo) {
    GhCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(r.fullName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            val desc = cleanDesc(r.description)
            if (desc.isNotBlank()) {
                Text(
                    desc,
                    fontSize = 13.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                listOf(
                    "★ ${r.stars}", "⑂ ${r.forks}", r.language, r.license,
                    fmtSize(r.sizeKb * 1024), r.pushedAt.take(10)
                ).filter { it.isNotBlank() }.joinToString("  ·  "),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SourceCard(r: RepoInfo) {
    val vmParser = LocalParserVM.current
    val vmDownloads = LocalDownloadsVM.current

    val ctx = LocalContext.current
    GhCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("源代码", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                vmParser.refs.take(30).forEach { p ->
                    TextButton(onClick = { vmParser.selRef = p.first }) {
                        Text(
                            p.second, fontSize = 12.sp,
                            fontWeight = if (vmParser.selRef == p.first) FontWeight.Bold
                            else FontWeight.Normal
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row {
                val zipUrl = "https://codeload.github.com/${r.fullName}/zip/refs/${vmParser.selRef}"
                val tarUrl = "https://codeload.github.com/${r.fullName}/tar.gz/refs/${vmParser.selRef}"
                Button(onClick = {
                    val name = r.fullName.replace("/", "-") + "-" +
                        vmParser.selRef.replace("/", "-") + ".zip"
                    vmDownloads.startDownload(ctx, zipUrl, name)
                }) { Text("下载 ZIP") }
                Spacer(Modifier.width(4.dp))
                LinkActions(zipUrl)
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val name = r.fullName.replace("/", "-") + "-" +
                        vmParser.selRef.replace("/", "-") + ".tar.gz"
                    vmDownloads.startDownload(ctx, tarUrl, name)
                }) { Text("下载 TAR.GZ") }
                Spacer(Modifier.width(4.dp))
                LinkActions(tarUrl)
            }
            Spacer(Modifier.height(6.dp))
            val cmd = if (vmParser.selRef.startsWith("tags/"))
                "git clone --depth 1 --branch ${vmParser.selRef.removePrefix("tags/")} " +
                    "https://github.com/${r.fullName}.git"
            else
                "git clone --depth 1 https://github.com/${r.fullName}.git"
            Text(cmd, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { copyText(ctx, cmd, "命令已复制") }) { Text("复制 clone 命令") }
        }
    }
}

@Composable
private fun ReleaseCard(
    rel: Release,
    assets: List<Asset>,
    onDownload: (String, String, Long) -> Unit
) {
    val vmParser = LocalParserVM.current

    GhCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(rel.name.ifBlank { rel.tag }, fontWeight = FontWeight.Bold)
            Text(
                "${rel.tag} · ${rel.published.take(10)} · ${rel.assets.size} 个文件",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            assets.forEach { a ->
                SelLead(SelItem(a.url, a.name, size = a.size)) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontSize = 13.sp)
                        Text(
                            "${platformOf(a.name)} · ${fmtSize(a.size)} · ${a.downloads} 次",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (!vmParser.parserMultiSelect) {
                        Button(onClick = { onDownload(a.url, a.name, a.size) }) { Text("下载") }
                    }
                    LinkActions(a.url)
                }
            }
        }
    }
}

/**
 * 「添加链接」第一层弹窗：选择链接来源。
 * - 添加链接：直接打开第二层解析弹窗（粘贴框留空）。
 * - 从文本文件选择：调起系统文件选择器，读到链接后打开第二层解析弹窗（粘贴框预填）。
 * - 从剪贴板选择：读剪贴板，有链接则打开第二层解析弹窗（粘贴框预填）。
 */
@Composable
fun AddLinkDialog() {
    val vmParser = LocalParserVM.current

    val ctx = LocalContext.current
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 用户选了文件：扫描其中 GitHub 链接 → 打开第二层解析弹窗（预填）
        if (uri != null) {
            val text = try {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
            } catch (e: Exception) { "" }
            val links = extractGithubLinks(text)
            if (links.isEmpty()) {
                toast(ctx, "这个文件里没找到 GitHub 链接")
                vmParser.showAddLink = false
            } else {
                vmParser.pendingInput = links.joinToString("\n") { "https://github.com/$it" }
                vmParser.showAddLink = false
                vmParser.showParseDialog = true
                if (links.size > 1) toast(ctx, "识别到 ${links.size} 个链接，已放进粘贴框，可逐个解析")
            }
        }
        // 用户取消选择（uri==null）则保持第一层弹窗开着
    }

    AlertDialog(
        onDismissRequest = { vmParser.showAddLink = false },
        title = { Text("添加 GitHub 链接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择链接来源：", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    // 手动粘贴：清空预填，直接进第二层解析弹窗
                    vmParser.pendingInput = ""
                    vmParser.showAddLink = false
                    vmParser.showParseDialog = true
                }) { Text("添加链接（手动粘贴）") }
                OutlinedButton(onClick = {
                    // 调起系统文件选择器；结果回来再决定
                    fileLauncher.launch(arrayOf("text/*", "*/*"))
                }) { Text("从文本文件选择") }
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val t = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    val s = parseSlug(t)
                    if (s == null) {
                        toast(ctx, "剪贴板里没有 GitHub 链接")
                    } else {
                        vmParser.pendingInput = "https://github.com/$s"
                        vmParser.showAddLink = false
                        vmParser.showParseDialog = true
                    }
                }) { Text("从剪贴板选择") }
            }
        },
        confirmButton = { },
        dismissButton = {
            TextButton(onClick = { vmParser.showAddLink = false }) { Text("取消") }
        }
    )
}

/**
 * 「添加链接」第二层弹窗：链接粘贴框 + 解析按钮。
 * 点「解析」才真正拉取仓库内容；支持粘贴多行链接（取第一个有效）。
 */
@Composable
fun ParseDialog() {
    val vmParser = LocalParserVM.current

    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { vmParser.showParseDialog = false },
        title = { Text("解析链接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "把 GitHub 链接贴到下面，点「解析」拉取仓库内容：",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = vmParser.pendingInput,
                    onValueChange = { vmParser.pendingInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://github.com/owner/repo", fontSize = 12.sp) },
                    minLines = 2
                )
                Text(
                    "支持仓库主页 / Release 页 / 单文件链接；可一次粘贴多个（每行一个），解析时取第一个有效链接。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val s = parseSlug(vmParser.pendingInput)
                if (s == null) {
                    toast(ctx, "没识别出 GitHub 链接")
                } else {
                    vmParser.input = "https://github.com/$s"
                    vmParser.showParseDialog = false
                    vmParser.doLoad(ctx, vmParser.input)
                }
            }) { Text("解析") }
        },
        dismissButton = {
            TextButton(onClick = { vmParser.showParseDialog = false }) { Text("取消") }
        }
    )
}

/** 从一段文本里提取所有 owner/repo（GitHub 链接形态） */
private fun extractGithubLinks(text: String): List<String> {
    val out = LinkedHashSet<String>()
    val re = Regex("github\\.com[:/]+([A-Za-z0-9_.\\-]+)/([A-Za-z0-9_.\\-]+)")
    for (m in re.findAll(text)) {
        val s = parseSlug("github.com/${m.groupValues[1]}/${m.groupValues[2]}") ?: continue
        out.add(s)
    }
    return out.toList()
}

/**
 * 解析历史卡片（第六轮新增）。
 *
 * 默认收起只显示一行：手机上「历史」不是每次都要看的东西，摊开会把下面的仓库信息
 * 顶出屏幕，那正是用户上一轮抱怨「设置页太混乱」的同一类问题。
 * 展开后最多列 12 条（存储上限 [History.MAX] = 60），每条右侧一个删除，底部一个「清空」。
 * 点条目 = 填回输入框并立即重新解析（见 [ParserViewModel.openHistory]）。
 */
@Composable
private fun HistoryCard() {
    val vmParser = LocalParserVM.current
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }

    GhCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "解析历史（${vmParser.history.size}）",
                    fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(if (open) "收起 ▴" else "展开 ▾", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                vmParser.history.take(12).forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(1f)
                                .clickable { vmParser.openHistory(ctx, e) }
                        ) {
                            Text(e.slug, fontSize = 13.sp)
                            Text(
                                e.title.ifBlank { e.input },
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { vmParser.removeHistory(ctx, e.slug) }) {
                            Text("删", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (vmParser.history.size > 12) {
                    Text("还有 ${vmParser.history.size - 12} 条未列出", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 说清楚这里存的到底是什么：用户容易以为历史是「页面快照」，
                // 点一下能离线复现。实际只存链接，点开必然重新联网拿最新内容。
                Text(
                    "只记链接，不存页面快照：点一条会重新联网解析（看到的是最新内容，离线时点不开）。" +
                        "同一仓库只留最新一条，开关在「设置 → 浏览」。",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.outline
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vmParser.clearHistory(ctx) }) {
                        Text("清空历史", fontSize = 12.sp, color = MaterialTheme.colorScheme.link)
                    }
                }
            }
        }
    }
}
