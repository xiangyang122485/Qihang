package com.gh.dl.ui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh.dl.data.Search
import com.gh.dl.data.MirrorManager
import com.gh.dl.data.Settings

@Composable
fun SearchTab() {
    val vmParser = LocalParserVM.current
    val vmSearch = LocalSearchVM.current

    val ctx = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---- 源选择 ----
        item {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("搜索源", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = vmSearch.searchSource == "github", onClick = { vmSearch.searchSource = "github"; vmParser.applySource(ctx) }, label = { Text("GitHub 源") })
                        FilterChip(selected = vmSearch.searchSource == "foreign", onClick = { vmSearch.searchSource = "foreign"; vmParser.applySource(ctx) }, label = { Text("外国源") })
                        FilterChip(selected = vmSearch.searchSource == "domestic", onClick = { vmSearch.searchSource = "domestic"; vmParser.applySource(ctx) }, label = { Text("国内源") })
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("启用 GitHub 索引", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "开启后搜索 api.github.com，关闭后只搜国内/外国源",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = vmSearch.githubIndexOn,
                            onCheckedChange = {
                                vmSearch.githubIndexOn = it
                                Settings.put(ctx, "githubIndexOn", if (it) "1" else "0")
                            }
                        )
                    }
                    if (vmSearch.searchSource == "domestic") {
                        Spacer(Modifier.height(10.dp))
                        Text("优先镜像站（点选，结果将走它下载 Release）：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MirrorManager.list(ctx).forEach { src ->
                                val sel = vmParser.domestic == src.id
                                TextButton(onClick = { vmParser.domestic = src.id; vmParser.applySource(ctx) }) {
                                    Text(
                                        (if (sel) "● " else "") + src.name,
                                        fontSize = 12.sp,
                                        color = if (sel) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "国内源 = GitHub 索引（标「国内镜像可下」，下载走镜像站拿 Release）+ 国内代码托管 Gitee / GitCode。" +
                                "高校镜像站没有按关键词搜仓库的 API，所以 GitHub 部分复用其官方索引。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "GitCode 需要在设置里配 access_token，未配置时自动跳过（不会发出去碰 401）。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (vmSearch.searchSource == "foreign") {
                        Spacer(Modifier.height(6.dp))
                        Text("外国源 = GitLab.com + Codeberg（均为合法外国代码托管，自带公开搜索 API）。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ---- 关键词框 ----
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = vmSearch.searchQuery,
                    onValueChange = { vmSearch.searchQuery = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("搜索项目 / 文件 / 源码") },
                    placeholder = { Text("例如 Python、Java、fzf") },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { vmSearch.doSearch(ctx) }, enabled = !vmSearch.searchBusy) {
                    Text("搜索")
                }
            }
        }

        if (vmSearch.searchQuery.isBlank()) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    Text("热门搜索", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Python", "Java", "fzf", "terminal", "download manager", "android").forEach { kw ->
                            AssistChip(onClick = { vmSearch.searchQuery = kw; vmSearch.doSearch(ctx) }, label = { Text(kw, fontSize = 12.sp) })
                        }
                    }
                }
            }
        }

        if (vmSearch.searchBusy) item { CircularProgressIndicator() }
        if (vmSearch.searchMsg.isNotBlank()) item {
            val err = vmSearch.searchIsError
            Text(
                vmSearch.searchMsg,
                fontSize = 12.sp,
                color = if (err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (err) Modifier.clickable { vmSearch.doSearch(ctx) } else Modifier
            )
        }
        if (vmSearch.searchHint.isNotBlank()) item {
            Text(vmSearch.searchHint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- 结果 ----
        vmSearch.searchResults.forEach { h ->
            item { SearchResultCard(h) }
        }
    }
}


@Composable
private fun SearchResultCard(h: Search.Hit) {
    val vmParser = LocalParserVM.current
    val vmSettings = LocalSettingsVM.current
    val vmDownloads = LocalDownloadsVM.current

    val ctx = LocalContext.current
    val badge = when (h.source) {
        "github" -> if (h.domestic) "国内镜像可下" else "GitHub"
        "gitlab" -> "GitLab"
        "codeberg" -> "Codeberg"
        else -> h.source
    }
    GhCard(Modifier.fillMaxWidth().clickable { vmParser.openSearchHit(ctx, h) }) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(h.fullName, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (h.mirrorLabel != null) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "可走${h.mirrorLabel}镜像下载",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .background(
                            if (h.domestic) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        badge,
                        fontSize = 10.sp,
                        color = if (h.domestic) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (h.desc.isNotBlank()) h.desc else "暂无描述",
                fontSize = 12.sp,
                color = if (h.desc.isNotBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(6.dp))
            val meta = listOf(
                "★ ${h.stars}",
                h.language,
                h.license,
                h.updatedAt
            ).filter { it.isNotBlank() }.joinToString("  ·  ")
            Text(meta, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            // 与解析器页一致：下载（直接进下载页）/ 复制直链 / 分享；点卡片本体仍「查看并解析」。
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val url = repoArchiveUrl(h)
                            val name = h.fullName.replace("/", "-") + "-src.zip"
                            vmDownloads.startDownload(ctx, url, name)
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("下载", fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { vmSettings.exportLink(ctx, repoArchiveUrl(h)) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "复制直链",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { vmSettings.shareLink(ctx, repoArchiveUrl(h)) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = "分享",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text("点卡片查看详情 →", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 搜索结果「下载 / 复制直链 / 分享」共用的源码压缩包直链（raw，未套通道前缀；
 * 下载引擎与 [com.gh.dl.ui.SettingsViewModel.exportLink]/shareLink 会各自按通道改写）。
 * 不同来源用各自的 archive 路径拼装，分支缺省回退到 main。
 */
private fun repoArchiveUrl(h: Search.Hit): String {
    val branch = if (h.defaultBranch.isNotBlank()) h.defaultBranch else "main"
    val repo = h.fullName.substringAfterLast('/')
    return when (h.source) {
        "github" -> "https://codeload.github.com/${h.fullName}/zip/refs/heads/$branch"
        "gitee" -> "https://gitee.com/${h.fullName}/repository/archive/$branch.zip"
        "gitcode" -> "https://gitcode.com/${h.fullName}/-/archive/$branch/$repo-$branch.zip"
        "gitlab" -> "https://gitlab.com/${h.fullName}/-/archive/$branch/$repo-$branch.zip"
        "codeberg" -> "https://codeberg.org/${h.fullName}/archive/$branch.zip"
        else -> h.htmlUrl
    }
}
