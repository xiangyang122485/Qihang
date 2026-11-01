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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh.dl.data.Search
import com.gh.dl.data.cleanDesc
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
        item {
            GhGradientBanner(
                title = "搜索项目",
                subtitle = "GitHub · GitLab · Codeberg · Gitee · GitCode",
                icon = Icons.Filled.Search
            )
        }
        // ---- 源选择 ----
        item {
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("搜索源", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 选中即持久化：当前实现移除了「设置 → 网络」中的统一保存按钮，
                        // 所有设置改为就地保存，否则此处切换的搜索源在重启后丢失。
                        listOf("github" to "GitHub 源", "foreign" to "外国源", "domestic" to "国内源")
                            .forEach { (id, label) ->
                                FilterChip(
                                    selected = vmSearch.searchSource == id,
                                    onClick = {
                                        vmSearch.searchSource = id
                                        Settings.put(ctx, "searchSource", id)
                                        vmParser.applySource(ctx)
                                    },
                                    label = { Text(label) }
                                )
                            }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用 GitHub 索引", fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
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
                        Text("优先镜像站", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { if (!vmSearch.searchBusy) vmSearch.doSearch(ctx) }
                    )
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { vmSearch.doSearch(ctx) }, enabled = !vmSearch.searchBusy) {
                    Text("搜索")
                }
            }
        }

        if (vmSearch.searchQuery.isBlank()) {
            // ---- 搜索历史（本地持久，可点击复搜 / 一键清除）----
            if (vmSearch.searchHistory.isNotEmpty()) item {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("搜索历史", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { vmSearch.clearSearchHistory(ctx) }) {
                            Text("清除", fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        vmSearch.searchHistory.forEach { kw ->
                            AssistChip(
                                onClick = { vmSearch.searchQuery = kw; vmSearch.doSearch(ctx) },
                                label = { Text(kw, fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                        }
                    }
                }
            }
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
        // 同样指定稳定 key：搜索结果为分页/换关键词追加模式，未指定 key 时整列卡片会重排
        items(vmSearch.searchResults, key = { "res-" + it.source + "-" + it.fullName }) { h ->
            SearchResultCard(h)
        }

        // ---- 分页加载更多 ----
        if (!vmSearch.searchBusy && vmSearch.searchResults.isNotEmpty()) {
            if (vmSearch.searchLoadingMore) {
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { LinearProgressIndicator(Modifier.fillMaxWidth()) } }
            } else if (vmSearch.searchHasMore) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Button(onClick = { vmSearch.loadMoreSearch(ctx) }) { Text("加载更多") }
                    }
                }
            } else {
                item {
                    Text("已加载全部结果", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
    }
}


@Composable
private fun SearchResultCard(h: Search.Hit) {
    val vmParser = LocalParserVM.current
    val vmSettings = LocalSettingsVM.current
    val vmDownloads = LocalDownloadsVM.current
    val vmBrowse = LocalBrowseVM.current

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
            // 描述为用户自填内容，常出现超长或编码异常（GitHub API 原样返回），统一清洗后再显示
            val desc = cleanDesc(h.desc)
            Text(
                if (desc.isNotBlank()) desc else "暂无描述",
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = if (desc.isNotBlank()) MaterialTheme.colorScheme.onSurfaceVariant
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
            // 与解析器页一致：下载 / 浏览（进入浏览页查看仓库主页）/ 复制直链 / 分享；点击卡片本体仍为「查看并解析」。
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
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = { vmBrowse.open(h.htmlUrl) },
                        enabled = h.htmlUrl.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.accentBlue,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("浏览", fontSize = 13.sp)
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
                Text("点击卡片查看详情", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
