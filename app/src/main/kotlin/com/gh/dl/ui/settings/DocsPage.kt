package com.gh.dl.ui.settings

// 阶段拆分：使用文档二级页，与 AboutPage 同属「关于」分组，逻辑零依赖。
// 第八轮整篇重写：旧版还写着「底部四个 Tab」（早就有五个了）、完全没提「浏览」这个 Tab、
// 也没提解析引擎 / 图片方式 / 抓取器这三档，而「CA 证书跟着系统」「取决于本机带没带 curl」
// 这类说法在内置二进制之后已经不成立。下面每一条都对着代码写，不确定的不写。
import com.gh.dl.BuildConfig
import com.gh.dl.data.Fetcher
import com.gh.dl.ui.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App 内置使用文档（纯本地，无网络）。
 * 结构跟着用户真实动线走：先看有什么（五个 Tab）→ 最常见那件事怎么做（下载 Release）→
 * 想弄明白进度/浏览/克隆/加速/外观/安全各自去哪调。
 * 文案严格对应已实装功能，不夸大、不杜撰未实现的开关。
 */
@Composable
fun DocsPage(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("使用文档", onBack)

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("这个 App 做什么", fontWeight = FontWeight.Bold)
                Text(
                    "粘贴 GitHub 公开仓库链接，列出并下载它的 Release 软件、源码压缩包，也能整仓克隆。" +
                        "只用于下载公开开源资源，不内置任何翻墙或隧道能力，也不上传你的密钥。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionHeader("一、底部五个 Tab 各管什么")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("解析器", "粘贴仓库链接，解析出 Release 版本、国内镜像通道、jsDelivr 源码树，逐项点下载。")
                DocLine("浏览", "在 App 里直接看仓库主页（README 渲染出来的样子）。" +
                        "抓取和渲染都在本地完成，WebView 不联网、不跑 JS；页里的 GitHub 链接点了继续在 App 内翻，" +
                        "其他地址交系统浏览器。")
                DocLine("下载", "所有下载任务的列表：看进度/速度、暂停/继续/重试/删除、复制直链/分享。")
                DocLine("搜索", "搜 GitHub 公开仓库（含国内镜像搜索），结果卡也能直接下载。")
                DocLine("设置", "分「取（网络 · 加速 · 浏览）／存（下载 · 克隆）／用（外观 · 权限 · 使用文档 · 关于）」三组。")
            }
        }

        SectionHeader("二、下载一个 Release（最常见用法）")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DocStep(1, "底部切到「解析器」。")
                DocStep(2, "粘贴仓库地址（如 https://github.com/owner/repo ），或点右上角「+」手动添加链接。")
                DocStep(3, "等待解析完成，页面会列出「软件 · 国内镜像通道」「源码 · jsDelivr 通道」「源代码」等分区。")
                DocStep(4, "点某个资产右边的「下载」，自动跳到下载页开始下载。")
                DocStep(5, "想一次下多个：右上角「批量下载」→ 勾选需要的项 → 点「下载(n)」。")
                DocStep(6, "只想看它长什么样：粘贴后点输入框右边的「浏览」，直接进「浏览」页看仓库主页。")
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    "提示：下载落在你用系统文件选择器挑好的文件夹里；若还没选过文件夹，" +
                        "首次下载会弹出系统选择器，挑一个即可（App 只拥有那一个文件夹的权限）。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionHeader("三、下载页进度怎么看")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("已完成", "进度条画满（100%），不再走动。")
                DocLine("下载中且大小已知", "显示「已下 / 总大小 · xx%」与实时速度。Release、镜像通道、jsDelivr 文件都有确切大小。")
                DocLine("下载中且大小未知", "显示「总大小未知」并用走动条表示在下载中。源码 ZIP 走 codeload 分块传输，拿不到总长度，属正常情况；下完会显示 100%。")
                DocLine("已暂停", "不画进度条，右边是「继续」按钮；继续走断点续传，不从头再来。")
                DocLine("失败", "不画进度条，状态行会写出原因（超时 / 证书 / 无空间等），可「重试」。")
                DocLine("下载引擎", "设置 → 下载 可换：OkDownload 多线程（默认）、Aria2 原生（arm64 已内置，其他架构自动回退单线程）、兼容单线程（OkHttp 直拉，不提速但最稳）。")
            }
        }

        SectionHeader("四、「浏览」页（第 5 轮之后新增，旧文档没写）")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "底部切到「浏览」，看的是**当前仓库主页渲染后的样子**：正文（README）由 App 自己抓取、" +
                        "解析、生成 HTML，再交给本地 WebView 显示。\n" +
                        "· WebView 关了 JS、关了网络、不许读本地文件——页内图片、CSS、脚本全都由 App 提供，" +
                        "所以这一页不会悄悄多发请求，也不受页面里的脚本影响；\n" +
                        "· 点页里的 GitHub 链接继续在 App 内翻，其他地址交系统浏览器；" +
                        "左上/右上是后退、前进，顶栏「刷新」是唯一强制重新联网的入口；\n" +
                        "· 页脚那行会如实写这次是「取 …ms + 渲染 …ms」还是「复用页面缓存 + 渲染 …ms」，" +
                        "以及是「直连 GitHub」还是「经镜像加速」；\n" +
                        "· 内容太长撞到抓取上限时，页面底部会明确提示「后面的内容未抓取」，不会静悄悄给半截。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DocLine("可调项在哪", "设置 → 浏览：解析引擎、图片方式、抓取器、记录解析历史、清图片缓存 / 清解析历史。")
                DocLine("浏览加速", "和「下载加速」是两个独立开关（设置 → 加速）。打开后页面 HTML 会按顺序试你保存的网页镜像前缀，失败自动回落官方直连。")
            }
        }

        SectionHeader("五、设置 → 浏览：三档可调的东西")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("解析引擎（默认 Jsoup HTML5）",
                    "· Jsoup HTML5：最稳，默认；\n" +
                        "· XML 模式：约快 2~3 倍，遇到不闭合标签会失败并自动回落默认档；\n" +
                        "· 先修复再解析：按 WHATWG 规范先修 HTML 再解析，最抗畸形；\n" +
                        "· 只解析正文：先在原文里定位正文那一段再解析，跳过导航与脚本，慢网络时推荐。\n" +
                        "换引擎不会重新联网，App 用内存里的原始 HTML 直接重渲染。")
                DocLine("图片（默认本地缓存）",
                    "· 本地缓存（推荐）：HTML 里只写一个不存在的虚拟域名，字节由 App 现场给，" +
                        "正文不会被图片挤掉、首屏也不用等图；7 天内同一地址直接复用，缓存上限 200MB，超了删最久的；\n" +
                        "· 内联进页面（旧行为）：整页自包含，但很慢，且 base64 会撑满正文预算把尾部内容丢掉；\n" +
                        "· 不显示图片：最省流量。")
                DocLine("抓取器（默认内置 OkHttp）",
                    "· 内置 OkHttp：复用连接池，默认；另受「设置 → 网络 → DoH + 真实 IP 优选」" +
                        "那一档影响（第九轮起该档默认关闭，开着时才会去查公共 DoH）；\n" +
                        "· curl " + Fetcher.BUNDLED_CURL_VERSION + " / wget " + Fetcher.BUNDLED_WGET_VERSION +
                        "：这两份**已经打进 APK**（和 aria2c 同一套办法：静态编译的可执行文件改名成 .so 放进 " +
                        "jniLibs/arm64-v8a，安装后从 nativeLibraryDir 执行），不再依赖本机带没带；" +
                        "非 arm64 机型装不到内置那份时自动改用本机的，设置页那一行会如实写用的是哪一份。\n" +
                        "选它们的原因：命令行工具走自己的 DNS / 代理配置，某些挂了全局代理的机型上反而比 App 直连快。" +
                        "失败一律自动回落内置通道，所以这一档不会让浏览更不稳定。\n" +
                        "代价：每次都是新进程、新 TCP 连接，复用不了连接池，连续翻很多仓库时整体比内置通道慢。")
            }
        }

        SectionHeader("六、搜索")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "底部切到「搜索」，输入关键字回车即可列出公开仓库（GitHub 索引 + 国内镜像搜索 + 国内代码托管）。" +
                        "每条结果卡提供「下载 / 浏览 / 复制直链 / 分享」，点卡片本体进入解析器继续；" +
                        "页顶可以点选「优先镜像站」，Release 就走它下载。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionHeader("七、克隆整仓（可选）")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("开启", "设置 → 克隆，打开总开关；解析页会多出整仓克隆入口。")
                DocLine("SSH 方式", "设置 → 克隆 → SSH 密钥管理，复制 App 生成的公钥，到 GitHub「Settings → SSH and GPG keys → New SSH key」粘贴添加。克隆走 GitHub 官方 22 端口，不经任何代理或中转。")
                DocLine("HTTPS 方式", "可填写 GitHub Token（仅发给 GitHub 官方域名），用于带鉴权访问。")
                DocLine("引擎", "libgit2（内置 arm64 二进制）或 JGit（纯 JVM，其他架构用它）；克隆完会校验 HEAD，不会静默给你一个残缺仓库。")
                DocLine("不支持什么", "私有仓库、Git LFS 大文件、子模块递归、SSH 协议本身不由 App 实现——SSH 那条是走 App 内置的 libgit2 + 自建密钥，不做交互式认证。")
            }
        }

        SectionHeader("八、网络与加速")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("默认通道", "优先直连 GitHub 官方 API / codeload；不通时自动回退到国内高校镜像与 jsDelivr 公共 CDN。")
                DocLine("镜像源管理", "设置 → 网络 → 镜像源：可导入 / 删除 / 逐条测速，解析页与搜索页顶部的「线路」就是从这里来的。")
                DocLine("加速（两个独立开关）", "「下载加速」管文件从哪儿取，「浏览加速」管页面 HTML 从哪儿取，互不影响，都默认关闭；" +
                    "开启后默认强制 SHA256 校验，防止第三方服务器替换文件内容——是否使用请自行评估风险。")
                DocLine("不会经过代理的东西", "GitHub Token 与 SSH 私钥只发（或只用于连）GitHub 官方域名，绝不经过镜像或代理。")
            }
        }

        SectionHeader("九、外观：浅色是默认值")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "设置 → 外观 三档：浅色（**默认**）、深色、跟随系统。\n" +
                        "第八轮修掉了两个毛病：\n" +
                        "① 冷启动闪一下深色——原因是窗口底色由系统主题决定，而 App 的主题设置在首帧之后才读进来。" +
                        "现在浅色 / 深色各有一个显式的窗口主题，Activity 在 setContent 之前就同步读一次设置来选它，" +
                        "所以第一帧就是对的，不存在「先黑后白」；\n" +
                        "② 切换深浅时留残影——原因是状态栏 / 导航栏的配色和窗口底色是分几次改的。" +
                        "现在一次调用里同时改窗口底色、导航栏色、状态栏图标明暗，同帧完成。\n" +
                        "另外夜间资源里那份窗口底色**故意还是浅色**：这样即使用户系统是深色而 App 设的是浅色，" +
                        "冷启动也不会有一瞬间的黑屏兜底。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionHeader("十、合规与安全")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("权限", "只申请网络（外加克隆后台跑时的前台通知）；写文件用系统文件授权（SAF），" +
                    "你亲手挑哪个文件夹，App 就只拥有那一个文件夹的权限，没有「读全部存储」这回事。")
                DocLine("不做什么", "不内置翻墙 / 隧道 / 代理能力；不上传你的 Token 与 SSH 私钥；只用于下载公开开源资源。")
                DocLine("校验建议", "下载完可拿官方 SHA256 比对；镜像通道提供「校验」按钮复制官方校验值，" +
                    "开启加速时 App 会自动比对并提示内容是否被替换。")
                DocLine("开源许可", "APK 内置的 wget / curl / aria2c / libgit2 与全部 JVM 依赖的许可证、" +
                    "对应源码出处都列在「设置 → 开源许可」，许可证全文随 APK 一起打包（assets/licenses）。" +
                    "App 源码本身用 MIT 发布：那几份 GPL 程序是 exec 调用的独立进程，不是链接进 App 的库，" +
                    "copyleft 不回传；libgit2 靠它自带的链接例外。仓库根有 LICENSE 与 NOTICE。")
                DocLine("内置二进制", "aria2c / libgit2 / curl " + Fetcher.BUNDLED_CURL_VERSION +
                    " / wget " + Fetcher.BUNDLED_WGET_VERSION + " 都是打进 APK 的开源程序，" +
                    "只在你点下载 / 克隆 / 浏览时才被执行；curl 与 wget 用的是 App 自带的根证书包" +
                    "（和 aria2 同一份），证书校验完整开启，实测对自签、过期、域名不符的证书一律拒绝。")
                DocLine("版本", "版本 " + BuildConfig.VERSION_NAME + "（构建 " + BuildConfig.VERSION_CODE + "）" +
                    "，以「设置 → 关于」为准。")
            }
        }

        Spacer(Modifier.padding(bottom = 4.dp))
        Text(
            "还有疑问？去「设置 → 关于」查看完整合规说明，或到仓库 Issues 反馈。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 文档小标题 + 正文一行 */
@Composable
private fun DocLine(title: String, body: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 有序步骤：① ② ③… */
@Composable
private fun DocStep(n: Int, text: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            "$n. ",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}
