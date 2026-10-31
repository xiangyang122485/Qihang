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

        CollapsibleNote(
            "本页设置项说明",
            "本页控制内容获取后的处理方式，获取来源由「设置 → 加速」控制：\n" +
                "· 静默翻译：非中文正文自动翻译，译文直接替换页面文字；\n" +
                "· 解析引擎：将 HTML 转换为节点树所采用的规范；\n" +
                "· 图片：README 图片以本地缓存引用、内联至页面，或不显示；\n" +
                "· 抓取器：使用内置 OkHttp，或调用 curl / wget；\n" +
                "· 解析历史：解析器页是否记录已解析的仓库。\n" +
                "修改后立即生效，下一次抓取或刷新按新设置执行，无需重启。"
        )

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

        CollapsibleNote(
            "解析引擎说明",
            "· Jsoup HTML5（默认）：按 html5lib 建树规范解析，自带 CSS 选择器，稳定性最高；\n" +
                "· XML 模式：按 XML 严格解析，速度快于默认模式，遇未闭合标签时失败并回落默认模式；\n" +
                "· 先修复再解析：nu.validator.htmlparser（WHATWG 规范实现）先修复再交 Jsoup 解析，容错最强；\n" +
                "· 仅解析正文：先在原文定位 markdown-body 段落再解析，跳过导航与脚本，处理量最小。\n" +
                "四档均不对畸形 HTML 抛出异常。"
        )

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

        CollapsibleNote(
            "重新渲染与重新联网",
            "仅地址、浏览加速开关、抓取器变更会重新联网。" +
                "解析引擎、图片方式、主题属于同一份内容更换处理方式：" +
                "使用内存中的原始 HTML（最多保留 3 页）重新渲染，不发起网络请求。" +
                "顶栏「刷新」是唯一强制重新联网入口；页脚标签显示本次为抓取或复用缓存。"
        )

        CollapsibleNote(
            "图片缓存规则",
            "缓存文件名按图片地址哈希生成，地址未变时仅通过 7 天过期机制重新获取。" +
                "每次读取均核对文件头魔数，写入中断产生的不完整文件会被删除并重新获取。" +
                "同一页面内并发请求同一图片仅联网一次；单页最多并发获取 4 张图片，" +
                "避免请求过多被镜像站限流；单张超过 2MB 不获取。" +
                "「清图片缓存」与「清解析历史」相互独立。"
        )

        CollapsibleNote(
            "本地缓存模式说明",
            "图片内联为 base64 会占用正文预算，长页面尾部内容可能缺失，且图片获取串行在渲染路径上。" +
                "本地缓存模式下 HTML 仅写入 https://" + GhView.IMG_HOST + "/<hash> 虚拟地址，" +
                "字节按需获取（先查缓存，未命中时联网）。该域名不存在，请求全部由本地应答，" +
                "页面保持零外部请求。"
        )

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

        CollapsibleNote(
            "内置 curl / wget 说明",
            "curl / wget 为静态编译的可执行文件，重命名为 libcurl.so / libwget.so 置于 jniLibs/arm64-v8a，" +
                "安装时由系统解压至 nativeLibraryDir，应用从该目录执行（应用自有目录被 SELinux 禁止 exec）。" +
                "非 arm64 机型无内置二进制，自动改用本机版本，界面显示当前来源。\n" +
                "内置版本：curl 8.11.0（上游自 8.17 起不再发布 aarch64 静态版）、wget " +
                    Fetcher.BUNDLED_WGET_VERSION + "（musl 交叉编译，静态链接 OpenSSL 3.5.9 与 zlib 1.3.2）。" +
                "许可证与源码出处见「设置 → 开源许可」。\n" +
                "证书：静态编译的工具不含系统信任库（Android 信任库位于 /apex，非其默认识别的 PEM 路径），" +
                "内置版本显式加载应用自带根证书包（122 张，与 aria2 同一份，按应用版本重新解压）。" +
                "校验完整启用，自签名、过期、域名不符的证书均被拒绝。本机版本使用系统 CA 路径，无需该参数。\n" +
                "调用方式：一律使用参数数组，URL 作为单个 argv 传入，不拼接 shell 字符串；" +
                "屏蔽 IPv6 字面量与十进制、十六进制回环地址；curl 固定 --proto 与 --proto-redir 仅允许跳转至 https；" +
                "wget 追加 --no-config，避免用户 \$HOME/.wgetrc 改变抓取行为。退出浏览页时终止运行中的子进程。\n" +
                "代价：每次调用为新进程与新 TCP 连接，不复用 OkHttp 连接池；失败时自动回落内置通道。"
        )

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
                Text(
                    "启用后解析器页记录已解析的仓库（最多 " + History.MAX + " 条，同一仓库保留最新一次），" +
                        "点击历史条目可重新解析。关闭仅停止后续记录，不清除已存数据。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
