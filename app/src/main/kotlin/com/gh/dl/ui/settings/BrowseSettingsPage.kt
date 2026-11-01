package com.gh.dl.ui.settings

// 设置 → 浏览（二级页）。
// 此处集中「浏览」功能的可调项：解析引擎、图片方式、抓取器、链接历史、图片缓存。
// 与「加速」分组独立：加速控制获取来源，本页控制获取后的处理方式。
import com.gh.dl.ui.*
import com.gh.dl.data.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 单选项组（点选即写入并生效），行布局展示全部选项 */
@Composable
private fun RadioGroup(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    fallback: String,
    onSelect: (String) -> Unit
) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        options.forEach { (id, label) ->
            val sel = current == id
            Row(
                Modifier.fillMaxWidth().clickable { if (!sel) onSelect(id) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = sel, onClick = { if (!sel) onSelect(id) })
                Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (sel && id == fallback) {
                    Text("默认", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/**
 * 抓取器行的后缀措辞。
 *
 * 显示当前实际使用的是内置版本还是本机版本：abiFilters 仅含 arm64-v8a，
 * 非 arm64 机型无内置二进制，回退本机版本。
 * probed 单独传入：集合判空无法区分「未安装」与「未探测完成」。
 */
private fun fetcherWording(probed: Boolean, src: String, ver: String): String = when {
    !probed -> "检测中"
    src == "内置" -> "内置 $ver"
    src == "本机" -> "本机版本（非 arm64 机型无内置二进制）"
    else -> "不可用（自动回落内置通道）"
}

@Composable
fun BrowseSettingsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm = LocalSettingsVM.current
    val scope = rememberCoroutineScope()
    var cache by remember { mutableLongStateOf(0L) }
    // 缓存体积需遍历目录，sourceOf() 需 stat 多个目录：均为磁盘 IO，不得在组合中执行，
    // 统一放入下方后台任务，结果以 remember 保存（每次重组均需显示，但只探测一次）。
    var srcCurl by remember { mutableStateOf("无") }
    var srcWget by remember { mutableStateOf("无") }
    var probed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) {
            GhView.imageCacheBytes() to
                (Fetcher.sourceOf(Fetcher.CURL) to Fetcher.sourceOf(Fetcher.WGET))
        }
        cache = r.first
        srcCurl = r.second.first
        srcWget = r.second.second
        probed = true
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("浏览", onBack)


        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "静默翻译",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = vm.autoTranslate, onCheckedChange = {
                        vm.autoTranslate = it
                        Settings.setAutoTranslate(ctx, it)
                    })
                }
                Text(
                    "开启后，浏览页检测到非中文正文时自动翻译，译文直接替换原页面文字，" +
                        "标签、链接与图片位置不变。翻译只发送正文文字，不发送地址、Cookie 或令牌。" +
                        "翻译失败时保留原文且不提示；关闭时可用浏览页顶栏翻译按钮手动触发。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        GhCard(Modifier.fillMaxWidth()) {
            RadioGroup(
                "解析引擎",
                GhView.ENGINES,
                vm.parseEngine,
                GhView.ENGINE_JSOUP
            ) { id ->
                vm.parseEngine = id
                Settings.put(ctx, "parseEngine", id)
            }
        }


        GhCard(Modifier.fillMaxWidth()) {
            RadioGroup(
                "图片",
                GhView.IMAGE_MODES,
                vm.imageMode,
                GhView.IMAGE_LOCAL
            ) { id ->
                vm.imageMode = id
                Settings.put(ctx, "imageMode", id)
            }
        }




        GhCard(Modifier.fillMaxWidth()) {
            RadioGroup(
                "抓取器",
                listOf(
                    Fetcher.OKHTTP to "内置 OkHttp（DoH 优选在「设置 → 网络」中单独开关）",
                    // 探测结果来自上方后台任务；完成前显示「检测中」，不提前判定为不可用
                    Fetcher.CURL to "curl ${Fetcher.BUNDLED_CURL_VERSION}（" +
                        fetcherWording(probed, srcCurl, Fetcher.BUNDLED_CURL_VERSION) + "）",
                    Fetcher.WGET to "wget ${Fetcher.BUNDLED_WGET_VERSION}（" +
                        fetcherWording(probed, srcWget, Fetcher.BUNDLED_WGET_VERSION) + "）"
                ),
                vm.fetcher,
                Fetcher.OKHTTP
            ) { id ->
                vm.fetcher = id
                Settings.put(ctx, "fetcher", id)
            }
        }


        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("记录解析历史", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = vm.historyOn, onCheckedChange = {
                        vm.historyOn = it
                        History.setEnabled(ctx, it)
                        Settings.put(ctx, "historyOn", if (it) "1" else "0")
                    })
                }
            }
        }

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("占用与清理", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "图片缓存：${if (cache == 0L) "无" else fmtSize(cache)}（上限 200MB，超出时删除最旧条目）",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        // 删除缓存文件为磁盘 IO，移至后台执行
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { GhView.clearImageCache() }
                            cache = GhView.imageCacheBytes()
                            toast(ctx, "已清理 ${fmtSize(n)} 图片缓存")
                        }
                    }) { Text("清图片缓存", fontSize = 12.sp) }
                    OutlinedButton(onClick = {
                        History.clear(ctx)
                        vm.parser.history.clear()
                        toast(ctx, "已清空解析历史")
                    }) { Text("清解析历史", fontSize = 12.sp) }
                }
            }
        }
    }
}
