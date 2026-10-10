package com.gh.dl.ui.settings

// 第六轮：设置 → 浏览（二级页）。
// 这里集中了「浏览」功能自己的可调项：解析引擎、图片方式、抓取器、链接历史、图片缓存。
// 之所以不塞进「加速」那一组：加速管的是「从哪儿取」，这一页管的是「取回来怎么处理」。
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

/**
 * 一组单选项（点选即写入并生效）。
 * 用行而不是下拉：手机上选项只有 3~4 个，下拉要多点一次还看不到全貌。
 */
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
 * 抓取器那一行后缀的措辞。
 *
 * 第八轮起 curl / wget 是**打进 APK 的**（jniLibs → nativeLibraryDir，和 aria2c 同一套办法），
 * 所以显示的不是「本机有没有」而是「这一档实际用的是内置那份，还是本机那份」：
 * 非 arm64 机型装不到内置二进制（abiFilters 只写了 arm64-v8a），那时自动落到本机的。
 * probed 必须单独传：只用集合判空的话，「都没有」和「还没探完」分不开，会一直显示「检测中」。
 */
private fun fetcherWording(probed: Boolean, src: String, ver: String): String = when {
    !probed -> "检测中"
    src == "内置" -> "内置 $ver，APK 自带"
    src == "本机" -> "本机那份（App 未内置可执行文件，多为非 arm64 机型）"
    else -> "这台机器上没有（内置那份没解压出来，选了会直接回落到内置通道，不影响浏览）"
}

@Composable
fun BrowseSettingsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm = LocalSettingsVM.current
    val scope = rememberCoroutineScope()
    var cache by remember { mutableLongStateOf(0L) }
    // 图片缓存大小要遍历整个缓存目录（可达上千个文件），**不能在组合里直接读**：
    // 那等于在 UI 线程做磁盘 IO，目录大的时候这页一进去就卡一下。
    // 这里也**不调** Fetcher.hasTool / sourceOf()：那是 stat 一串目录，同理挪到下面的后台任务。
    // （第八轮把「本机有没有」换成「这一档实际用的是内置还是本机那份」，available() 已经没人用，删掉了）
    // 「这台机器上到底用的是内置那份还是本机那份」同样要 stat 目录（内置的要 stat
    // nativeLibraryDir，本机的要 stat 一串系统目录，见 Fetcher.BIN），所以也放后台。
    // 用 remember 存结果：这页每次重组都要显示这一行，但探测不该每次都做一次。
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
            "这一页管什么（点击展开）",
            "「加速」那一档管的是「去哪个站取」，这一页管的是「取回来怎么处理」：\n" +
                "· 解析引擎：用哪种口径把 HTML 变成节点树；\n" +
                "· 图片：README 里的图片是本地缓存引用、内联进页面，还是干脆不显示；\n" +
                "· 抓取器：用 App 内置的 OkHttp，还是调 curl / wget（这两份也打进 APK 了）；\n" +
                "· 链接历史：解析器页是否记住解析过的仓库。\n" +
                "改完立即生效，下一次抓取/刷新就按新设置走，不用重启。"
        )

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
            "关于解析引擎的选型（点击展开）",
            "用户提的 selectolax / parsel / lxml / html5lib **都是 Python 库**，APK 里没有 CPython，" +
                "带不进去（要带就得打包整个解释器和原生扩展）。这里给的是它们在 JVM 上的对等物：\n" +
                "· Jsoup HTML5 ≈ html5lib 的建树口径，且是这几个里唯一自带 CSS 选择器的；\n" +
                "· XML 模式 ≈ lxml 的 XML 解析，实测 330KB 页面 3.7ms（默认档 11.3ms），" +
                "遇到不闭合标签会失败，失败时自动回落默认档；\n" +
                "· 先修复再解析 = nu.validator.htmlparser（WHATWG 规范官方实现，html5lib 的 JVM 对等物）" +
                "把 HTML 修一遍再交给 Jsoup，实测 23.0ms，最抗畸形；\n" +
                "· 只解析正文 = 学 Jericho 的零拷贝思路，先在原文里定位 markdown-body 那 26KB 再解析，" +
                "跳过 92% 的脚本与导航，是这一组里唯一能真正减少工作量的。\n" +
                "实测五者对畸形 HTML 都不抛异常 —— 所以「内容过长」不是解析器能力问题，" +
                "而是图片内联撑爆预算，见下面「图片」。"
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
            "切这些选项会不会重新下载页面（点击展开）",
            "不会白下。会不会重新联网只取决于「取哪份网络内容」那几项：地址、浏览加速开关、抓取器。" +
                "换解析引擎、换图片方式、改深色都属于「同一份内容换一种处理」，" +
                "App 会把已在内存里的原始 HTML（最多 3 页）直接重新渲染一遍，一个请求都不发；" +
                "四档引擎逐个试一遍也就四次本地渲染，不会变成抓四遍。\n" +
                "顶栏那个「刷新」是唯一强制重新联网的入口——点了就是明确要拿最新内容。" +
                "页脚那行标签会如实写这次是「取 …s + 渲染 …」还是「复用页面缓存 + 渲染 …」。"
        )

        CollapsibleNote(
            "图片缓存的规则（什么时候会重新取图）",
            "文件名按图片地址的 hash 生成，所以「地址没变但作者换了图」这种情况只能靠过期来发现：" +
                "缓存 7 天后自动失效并重新取。另外每次读缓存都会核对文件头魔数，" +
                "上次写盘时被系统杀掉的半截文件会被识别出来、删掉、重取，" +
                "不会永远显示一张坏图（WebView 不联网，坏图没有第二次机会）。" +
                "同一页里并发请求同一张图只会联网一次；一页最多同时取 4 张，" +
                "防止快速滑过长 README 时一次打出十几个请求被镜像站限流。" +
                "单张超过 2MB 的图直接不取。" +
                "「清图片缓存」和「清解析历史」是两个独立按钮，互不影响。"
        )

        CollapsibleNote(
            "为什么默认是「本地缓存」（点击展开）",
            "实测同一页 CYQawa/YunX：正文只有 26KB，但 13 张图转 base64 内联后整页 HTML 涨到 " +
                "2.42M 字符，把 2.5M 的正文预算吃满，于是尾部（「截图」之后全部内容）被丢掉 —— " +
                "这就是那条「内容过长，后面部分未显示」提示的真正来源。\n" +
                "而且取图当时串在渲染关键路径上：同一页带图 15.2s、不带图 1.4s。\n" +
                "现在 HTML 里只写一个 https://" + GhView.IMG_HOST + "/<hash> 的虚拟地址，" +
                "字节由界面找我们要（先查缓存，没有才联网），所以正文不再被挤掉、首屏也不用等图。\n" +
                "这个域名不存在，请求只会被本地应答，页面依旧是零外部请求。"
        )

        GhCard(Modifier.fillMaxWidth()) {
            RadioGroup(
                "抓取器",
                listOf(
                    Fetcher.OKHTTP to "内置 OkHttp（默认；DoH 优选在「设置 → 网络」里单独开关）",
                    // 探测（stat 一串目录）在上面的后台任务里做，这里只读结果；
                    // 探到之前一律写「检测中」，不要提前下结论说「没有」
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
            "慢是不是 curl 的问题（点击展开）",
            "实测不是：同一份页面 curl 0.9s、App 内置 OkHttp 1.4s 拿到 330KB，" +
                "而用户感觉到的 15s 全花在逐张取图转 base64（现在已解决）。\n" +
                "仍然把 curl / wget 接成可选项，因为它们走自己的 DNS / 代理配置，" +
                "某些挂了全局代理的机型上反而比 App 直连快。选了之后失败会自动回落内置通道，" +
                "所以这一档不会让浏览变得更不稳定。\n" +
                "第八轮按用户要求把它们**打进 APK**（「wget 本机没有，那就应该集成到 APP 里才对」——" +
                "实测对的：Android 10+ 的 AOSP 早就不带 GNU wget，很多机型连 curl 都没有）。" +
                "办法和 aria2c 完全一样：静态编译的可执行文件改名成 libcurl.so / libwget.so 放进 " +
                "jniLibs/arm64-v8a，安装时系统解压到 nativeLibraryDir，App 从那儿执行" +
                "（只有那个目录被允许 exec，从自己的 filesDir 跑会被 SELinux 直接拒）。" +
                "带进来的版本是定死的：curl 8.11.0（上游自 8.17 起不再发 aarch64 静态版）、" +
                "wget " + Fetcher.BUNDLED_WGET_VERSION + "（自己用 musl 工具链交叉编译，" +
                "静态链 OpenSSL 3.5.9 + zlib 1.3.2；第九轮把 OpenSSL 从 1.1.1 换成 3.5.9，" +
                "因为旧 OpenSSL 许可证与 GPL 不兼容，且 1.1.1 已停止安全维护（代价：这颗从 3.4 MB 涨到 5.9 MB），" +
                "详见「设置 → 开源许可」）。" +
                "非 arm64 机型装不到这两份，会自动改用本机的，界面那一行会如实写用的是哪一份。\n" +
                "证书这条要说清楚，因为**内置那份和以前反过来了**：静态编译的工具里没有系统信任库" +
                "（Android 的在 /apex 里，不是它们认的那个 PEM 路径），所以内置的 curl / wget 一律" +
                "显式喂 App 自带的那份根证书包（和 aria2 同一份、同一套「按 App 版本重新解压」的规则，" +
                "122 张根）。校验是**完整开启**的，实测对自签证书、过期证书、域名不符一律拒绝；" +
                "wget 的 --ca-certificate 语义上是「往信任库里追加」而不是替换，但 Android 上没有它" +
                "默认去读的那个目录（/etc/ssl 不存在），所以这份 PEM 仍是唯一锚点——把默认信任目录" +
                "遮掉再喂一张自签 CA，同样连不上，不存在「为了能用而放弃校验」这种妥协。" +
                "代价是这份根证书不会跟着系统更新：某个根过期时，得等 App 更新那一版打包的证书，" +
                "所以每次升级都会重解一次。本机那份不需要这个参数（它自带 /system 的 CA 路径）。\n" +
                "安全边界不变：调外部程序一律用参数数组（不拼 shell 字符串），URL 只作为一个 argv 传进去；" +
                "curl / wget 自己不做任何安全限制，所以「内网 / 回环地址不连」这条完全靠 App 层拦住，" +
                "为此把 IPv6 字面量（::1、fd00::、fe80::）和 http://2130706433/ 这类" +
                "十进制 / 十六进制绕法都堵上了。curl 那边写死 --proto/--proto-redir 只允许跳到 https" +
                "（防止「公网地址 302 到 file:// 读 App 私有文件」）；wget 那边 GNU 版自带「跳转只跟 " +
                "http/https/ftp」的实现约束，而内置那份额外加 --no-config —— 实测用户自己的 " +
                "\$HOME/.wgetrc 能改抓取口径（甚至关掉证书校验），不能让它影响 App。" +
                "退出浏览页时会主动掐掉还在跑的子进程，不会留后台残留。" +
                "另外要清楚它的代价：命令行工具每次都是新进程、新 TCP 连接，" +
                "复用不了 OkHttp 那个连接池，连续翻很多仓库时整体比内置通道慢。"
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
                    "开着时，解析器页会记住解析过的仓库（最多 " + History.MAX + " 条，同一仓库只留最新一次），" +
                        "点输入框下面的历史就能重新解析。关掉只影响「以后记不记」，不清已存的。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("占用与清理", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "图片缓存：${if (cache == 0L) "空" else fmtSize(cache)}（上限 200MB，超了自动删最久的）",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        // 同上：删上千个文件是纯磁盘活，放后台做完再回主线程报数
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
