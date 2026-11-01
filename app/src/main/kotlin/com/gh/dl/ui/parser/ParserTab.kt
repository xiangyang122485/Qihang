package com.gh.dl.ui.parser

// 自原 GhApp.kt 拆分而来（按页面拆分大型文件，业务逻辑未作改动）。
// 解析页相关的全部 composable 与私有辅助函数集中于此；函数签名、调用关系、业务逻辑保持不变。
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
import androidx.compose.material3.LinearProgressIndicator
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
 * 解析器页「批量下载」多选模式下，在每个文件行前方添加复选框。
 * 仅在多选模式下显示；勾选状态写入 [LocalParserVM.current.parserSel]（以 URL 去重）。
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

// 批量下载的全选 / 下载 / 取消操作已移至解析器页顶栏（固定置顶），不再使用底部操作栏。

/**
 * 每个可下载文件行末尾的两个图标：复制直链 / 分享直链。
 * 直链为经过通道改写（官方 / 高校镜像 / 加速代理前缀）后的最终 URL，
 * 第三方下载器可直接使用，作为内置引擎之外的备选方案。
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

/** 线路切换按钮：选中时显示绿色 ● */
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
            GhGradientBanner(
                title = "解析 GitHub 链接",
                subtitle = "Release · 源码 · 软件包，一键加速下载",
                icon = Icons.Filled.Link
            )
        }
        item {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = vmParser.input,
                        onValueChange = { vmParser.input = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://github.com/owner/repo", fontSize = 12.sp) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Spacer(Modifier.height(8.dp))
                    // 原为单个「解析」按钮，当前拆分为左右两部分：
                    //  红色「解析」= 通过 API 获取 Release / 源码树（应用核心功能）
                    //  蓝色「浏览」= 跳转至「浏览」Tab，使用内嵌页面查看仓库
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
        // 解析历史：点击条目后填入输入框并重新解析。
        // 放置在输入框正下方而非页面底部，便于用户在输入时快速访问。
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
                                Text("暂无镜像源，请前往「设置 → 网络 → 镜像源管理」添加。", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
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
                    color = when {
                        vmParser.netSummary.contains("可用") -> MaterialTheme.colorScheme.secondary
                        vmParser.netSummary.contains("正在") -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.warning
                    }
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
            // Release 与仓库信息分开请求：列表未返回、请求失败、仓库无发布、筛选后为空
            // 四种状态分别显示，避免把网络延迟误判为功能缺失
            item {
                when {
                    vmParser.releasesLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "正在获取 Release 列表…",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    vmParser.releasesError.isNotBlank() -> Column {
                        Text(
                            "Release 获取失败：${vmParser.releasesError}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        // 重试必须是「刷新整个仓库」，而不是只补拉 Release：
                        // 仓库信息与 Release 可能经不同线路返回，单独重拉 Release 容易继续失败
                        TextButton(onClick = { vmParser.refreshParser(ctx) }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("重新获取仓库", fontSize = 12.sp)
                        }
                    }
                    vmParser.releases.isEmpty() -> Column {
                        Text(
                            "该仓库未发布 Release。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { vmParser.refreshParser(ctx) }) {
                            Text("刷新仓库", fontSize = 12.sp)
                        }
                    }
                    else -> {
                        val shown = vmParser.releases.count { rel ->
                            rel.assets.any { a ->
                                vmParser.filter == "all" || osOf(a.name) == vmParser.filter
                            }
                        }
                        if (shown == 0) {
                            Text(
                                "当前筛选下没有可下载文件。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            // 使用 items(..., key=) 而非 forEach{item{}}：
            // 「批量下载」勾选、筛选切换、进度刷新均会触发该段落重新计算，未指定 key 时
            // Compose 仅按下标对齐，列表长度变化（筛出或滤掉某个 Release）会导致
            // 后续卡片错位重排，表现为滚动时跳动或勾选状态串至其他卡片。
            items(vmParser.releases, key = { "rel-" + it.tag }) { rel ->
                val assets = rel.assets.filter {
                    vmParser.filter == "all" || com.gh.dl.data.osOf(it.name) == vmParser.filter
                }
                if (assets.isNotEmpty()) {
                    ReleaseCard(rel, assets) { u, n, s -> vmDownloads.startDownload(ctx, u, n, sizeHint = s) }
                }
            }
            // ---- Release 分页加载更多（仅官方 API 通道支持；atom / 镜像兜底不显示）----
            if (vmParser.releasesLoadingMore) {
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { LinearProgressIndicator(Modifier.fillMaxWidth()) } }
            } else if (vmParser.releasesHasMore) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = { vmParser.loadMoreReleases(ctx) }) { Text("加载更多 Release") }
                        if (vmParser.releasesMoreError.isNotBlank()) {
                            Text("加载失败：${vmParser.releasesMoreError}", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else if (vmParser.releasesFromApi && vmParser.releases.isNotEmpty()) {
                item { Text("已加载全部 Release", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
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
                        // 校验值需实时计算（数十 MB 文件的 SHA256 计算耗时秒级），必须在后台线程执行；
                        // 异常处理：原裸线程缺少异常出口，
                        // 网络中断或镜像站返回异常响应时线程静默终止，用户点击「校验」后无任何反馈。
                        verifying(ctx, "校验值") { Mirror.sha256(mh, a.name) }
                    }) { Text("校验", fontSize = 12.sp) }
                    Spacer(Modifier.width(4.dp))
                    if (!vmParser.parserMultiSelect) {
                        Button(onClick = {
                            Threads.start("校验并下载") {
                                // 即使校验值获取失败仍继续下载：runCatching 为必需，
                                // 否则 Mirror.sha256 抛出异常（网络中断、镜像站 500）时线程终止，
                                // MAIN.post 永不执行，用户点击「下载」表现为无响应。
                                val h = runCatching { Mirror.sha256(mh, a.name) }.getOrNull()
                                // startDownload 会写入 job.running / AppState.tab / 下载列表，
                                // 这些均为 Compose 状态，必须回到主线程修改。
                                MAIN.post { vmDownloads.startDownload(ctx, a.url, a.name, h, sizeHint = a.size) }
                            }
                        }) { Text("下载") }
                    }
                    LinkActions(a.url)
                }
            }
        }
    }
}

/**
 * 国内镜像中单个版本的卡片：列出该版本下的各个文件（APK/EXE/DEB/IPA 等），
 * 每个文件配备一个「下载」按钮，仅下载该文件，不克隆整个仓库。
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
                "以下文件可单独下载：",
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
                            Threads.start("校验并下载") {
                                // 与上方镜像通道按钮逻辑一致：即使校验值获取失败仍继续下载
                                val h = runCatching { Mirror.sha256(hit, a.name) }.getOrNull()
                                MAIN.post { vmDownloads.startDownload(ctx, a.url, a.name, h, sizeHint = a.size) }
                            }
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
                    "还有 ${vmParser.jsdTree.size - 120} 个文件未列出，可使用「打包下载整仓 ZIP」一次获取全部",
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rel.name.ifBlank { rel.tag }, fontWeight = FontWeight.Bold)
                if (rel.pre) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "预发布",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
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
            if (rel.body.isNotBlank()) {
                var expanded by remember { mutableStateOf(false) }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起更新说明" else "查看更新说明", fontSize = 12.sp)
                }
                if (expanded) {
                    val shown = if (rel.body.length > 3000) rel.body.take(3000) + "\n…（内容过长，已截断）" else rel.body
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            shown,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「添加链接」第一层弹窗：选择链接来源。
 * - 添加链接：直接打开第二层解析弹窗（粘贴框留空）。
 * - 从文本文件选择：调起系统文件选择器，读取链接后打开第二层解析弹窗（粘贴框预填）。
 * - 从剪贴板选择：读取剪贴板，若存在链接则打开第二层解析弹窗（粘贴框预填）。
 */
@Composable
fun AddLinkDialog() {
    val vmParser = LocalParserVM.current

    val ctx = LocalContext.current
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 用户选择文件后：扫描其中 GitHub 链接 → 打开第二层解析弹窗（预填）
        if (uri != null) {
            val text = try {
                ctx.contentResolver.openInputStream(uri)?.use { it.reader().readText() }.orEmpty()
            } catch (e: Exception) { "" }
            val links = extractGithubLinks(text)
            if (links.isEmpty()) {
                toast(ctx, "该文件中未找到 GitHub 链接")
                vmParser.showAddLink = false
            } else {
                vmParser.pendingInput = links.joinToString("\n") { "https://github.com/$it" }
                vmParser.showAddLink = false
                vmParser.showParseDialog = true
                if (links.size > 1) toast(ctx, "识别到 ${links.size} 个链接，已填入粘贴框，可逐个解析")
            }
        }
        // 用户取消选择（uri==null）则保持第一层弹窗开启
    }

    AlertDialog(
        onDismissRequest = { vmParser.showAddLink = false },
        title = { Text("添加 GitHub 链接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择链接来源：", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    // 手动粘贴：清空预填内容，直接进入第二层解析弹窗
                    vmParser.pendingInput = ""
                    vmParser.showAddLink = false
                    vmParser.showParseDialog = true
                }) { Text("添加链接（手动粘贴）") }
                OutlinedButton(onClick = {
                    // 调起系统文件选择器；返回结果后继续处理
                    fileLauncher.launch(arrayOf("text/*", "*/*"))
                }) { Text("从文本文件选择") }
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val t = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    val s = parseSlug(t)
                    if (s == null) {
                        toast(ctx, "剪贴板中未找到 GitHub 链接")
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
 * 「添加链接」第二层弹窗：链接粘贴框与解析按钮。
 * 点击「解析」后方开始拉取仓库内容；支持粘贴多行链接（取第一个有效链接）。
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
                    "将 GitHub 链接粘贴至下方，点击「解析」拉取仓库内容：",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = vmParser.pendingInput,
                    onValueChange = { vmParser.pendingInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://github.com/owner/repo", fontSize = 12.sp) },
                    minLines = 2
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val s = parseSlug(vmParser.pendingInput)
                if (s == null) {
                    toast(ctx, "未识别出 GitHub 链接")
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

private val GH_LINK = Regex("github\\.com[:/]+([A-Za-z0-9_.\\-]+)/([A-Za-z0-9_.\\-]+)")

/** 从文本中提取所有 owner/repo（GitHub 链接格式） */
private fun extractGithubLinks(text: String): List<String> {
    val out = LinkedHashSet<String>()
    for (m in GH_LINK.findAll(text)) {
        val s = parseSlug("github.com/${m.groupValues[1]}/${m.groupValues[2]}") ?: continue
        out.add(s)
    }
    return out.toList()
}

/**
 * 解析历史卡片。
 *
 * 默认收起仅显示一行，展开后最多列出 12 条（存储上限 [History.MAX] = 60），
 * 每条右侧提供删除按钮，底部提供「清空」按钮。
 * 点击条目将填入输入框并立即重新解析（见 [ParserViewModel.openHistory]）。
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vmParser.clearHistory(ctx) }) {
                        Text("清空历史", fontSize = 12.sp, color = MaterialTheme.colorScheme.link)
                    }
                }
            }
        }
    }
}
