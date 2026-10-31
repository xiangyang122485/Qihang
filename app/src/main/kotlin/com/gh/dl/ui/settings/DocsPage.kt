package com.gh.dl.ui.settings

// 使用文档二级页，与 AboutPage 同属「关于」分组，无逻辑依赖。
// 内容与代码实现逐项对应，未实现的功能不予收录。
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
 * 应用内置使用文档（纯本地，无网络请求）。
 * 顺序：功能概览 → 常见操作 → 状态说明 → 各设置分组 → 合规与安全。
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
                Text("功能概述", fontWeight = FontWeight.Bold)
                Text(
                    "粘贴 GitHub 公开仓库链接，下载其 Release 产物与源码压缩包，支持整仓克隆。" +
                        "仅访问 GitHub 官方端点与用户配置的镜像通道，不含隧道、代理或第三方中转功能，" +
                        "不上传 Token 与 SSH 私钥。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionHeader("一、底部五个 Tab")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("解析器", "解析仓库的 Release 版本、国内镜像通道与 jsDelivr 源码树，逐项下载。")
                DocLine("浏览", "应用内渲染仓库主页 README。抓取与渲染均在本地完成，WebView 不联网、不执行 JS；" +
                        "页面内的 GitHub 链接在应用内继续浏览，其余地址交由系统浏览器处理。")
                DocLine("下载", "任务列表：查看进度与速度，暂停 / 继续 / 重试 / 删除，复制直链与分享。")
                DocLine("搜索", "搜索 GitHub 公开仓库，结果卡片可直接下载。")
                DocLine("设置", "三组：获取（网络 · 加速 · 浏览）／存储（下载 · 克隆）／使用（外观 · 权限 · 使用文档 · 关于）。")
            }
        }

        SectionHeader("二、下载 Release 产物")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DocStep(1, "切换至「解析器」。")
                DocStep(2, "粘贴仓库地址（如 https://github.com/owner/repo ），或点右上角「+」添加链接。")
                DocStep(3, "解析完成后，页面列出「软件 · 国内镜像通道」「源码 · jsDelivr 通道」「源代码」等分区。")
                DocStep(4, "点击目标资产右侧「下载」，跳转至下载页开始下载。")
                DocStep(5, "批量下载：右上角「批量下载」→ 勾选项目 → 「下载(n)」。")
                DocStep(6, "仅查看仓库内容：点输入框右侧「浏览」，进入「浏览」页。")
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    "文件保存至系统文件选择器授权的文件夹；未授权时，首次下载会弹出选择器。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionHeader("三、下载页状态")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("已完成", "进度显示 100%。")
                DocLine("下载中（大小已知）", "显示「已下载 / 总大小 · xx%」与实时速度。Release、镜像通道、jsDelivr 文件具有确切大小。")
                DocLine("下载中（大小未知）", "显示「总大小未知」并以动态条表示进行中。codeload 以分块传输源码 ZIP，无总长度。")
                DocLine("已暂停", "不显示进度条，右侧为「继续」；继续时断点续传。")
                DocLine("失败", "不显示进度条，状态行显示原因（超时 / 证书 / 空间不足），可「重试」。")
                DocLine("下载引擎", "「设置 → 下载」可切换：OkDownload 多线程（默认）、Aria2 原生（内置 arm64，其他架构回退单线程）、兼容单线程（OkHttp 直连）。")
            }
        }

        SectionHeader("四、「浏览」页")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "仓库主页正文由应用抓取、解析并生成 HTML，交由本地 WebView 显示。\n" +
                        "· WebView 已禁用 JS、网络与本地文件读取，图片、CSS 均由应用提供，页面不发起额外请求；\n" +
                        "· GitHub 链接在应用内继续浏览，其余地址交由系统浏览器；顶栏「刷新」是唯一强制联网入口；\n" +
                        "· 页脚显示「抓取 / 复用缓存」耗时、渲染耗时，以及连接方式（直连 GitHub 或经镜像）；\n" +
                        "· 内容超出抓取上限时，底部提示「后续内容未抓取」。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DocLine("可调项", "「设置 → 浏览」：解析引擎、图片方式、抓取器、记录解析历史、清除图片缓存与解析历史。")
                DocLine("浏览加速", "与「下载加速」为独立开关（「设置 → 加速」）。启用后按顺序尝试已保存的网页镜像前缀，失败时回落官方直连。")
            }
        }

        SectionHeader("五、「设置 → 浏览」")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("解析引擎（默认 Jsoup HTML5）",
                    "· Jsoup HTML5：稳定性最高；\n" +
                        "· XML 模式：速度约快 2~3 倍，遇未闭合标签时失败并回落默认模式；\n" +
                        "· 先修复再解析：按 WHATWG 规范修复后再解析，容错最强；\n" +
                        "· 仅解析正文：先定位正文段落再解析，跳过导航与脚本。\n" +
                        "切换引擎不重新联网，使用内存中的原始 HTML 重新渲染。")
                DocLine("图片（默认本地缓存）",
                    "· 本地缓存：HTML 中写入虚拟域名，字节由应用提供；同一地址 7 天内复用，上限 200MB，超出删除最旧条目；\n" +
                        "· 内联至页面：整页自包含，base64 占用正文预算，尾部内容可能缺失；\n" +
                        "· 不显示图片：流量消耗最低。")
                DocLine("抓取器（默认内置 OkHttp）",
                    "· 内置 OkHttp：复用连接池；受「设置 → 网络 → DoH + 真实 IP 优选」影响，该开关默认关闭；\n" +
                        "· curl " + Fetcher.BUNDLED_CURL_VERSION + " / wget " + Fetcher.BUNDLED_WGET_VERSION +
                        "：以静态可执行文件重命名为 .so 的形式内置于 jniLibs/arm64-v8a，安装后从 nativeLibraryDir 执行；" +
                        "非 arm64 机型改用本机版本，设置页显示当前来源。\n" +
                        "命令行工具使用独立的 DNS 与代理配置，在部分全局代理设备上速度优于应用直连；" +
                        "失败时回落内置通道。每次调用为新进程与新连接，不复用连接池。")
            }
        }

        SectionHeader("六、搜索")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "输入关键字后回车，列出公开仓库（GitHub 索引、国内镜像搜索、国内代码托管）。" +
                        "结果卡片提供「下载 / 浏览 / 复制直链 / 分享」，点卡片进入解析器；" +
                        "页顶可选「优先镜像站」，Release 产物改由镜像站下载。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionHeader("七、克隆整仓")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("开启", "「设置 → 克隆」打开总开关后，解析页新增整仓克隆入口。")
                DocLine("SSH", "「设置 → 克隆 → SSH 密钥管理」复制应用生成的公钥，添加至 GitHub「Settings → SSH and GPG keys → New SSH key」。经 GitHub 22 端口直连。")
                DocLine("HTTPS", "可填写 GitHub Token，用于带鉴权的访问，仅发送至 GitHub 官方域名。")
                DocLine("引擎", "libgit2（内置 arm64 二进制）或 JGit（纯 JVM）。克隆完成后校验 HEAD。")
                DocLine("限制", "不支持私有仓库、Git LFS 大文件、子模块递归与交互式认证。")
            }
        }

        SectionHeader("八、网络与加速")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("默认通道", "优先直连 GitHub 官方 API / codeload，不可达时回退国内高校镜像与 jsDelivr 公共 CDN。")
                DocLine("镜像源管理", "「设置 → 网络 → 镜像源管理」：导入 / 删除 / 逐条测速；解析页与搜索页顶部「线路」选项来源于此。")
                DocLine("加速开关", "「下载加速」控制文件来源，「浏览加速」控制页面 HTML 来源，二者独立且默认关闭。开启下载加速后默认强制 SHA256 校验，防止第三方服务器替换文件内容。")
                DocLine("不经过代理的内容", "GitHub Token 与 SSH 私钥仅用于连接 GitHub 官方域名，不经镜像站或代理服务器。")
            }
        }

        SectionHeader("九、外观")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "「设置 → 外观」提供浅色（默认）、深色、跟随系统三档。\n" +
                        "· 窗口底色在 setContent 之前依设置选定，冷启动首帧即为所选主题；\n" +
                        "· 状态栏、导航栏与窗口底色在同一次调用中修改，切换深浅色不分帧；\n" +
                        "· 夜间资源中的窗口底色保持浅色，系统为深色而应用设为浅色时冷启动不出现黑屏。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionHeader("十、合规与安全")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("权限", "仅申请网络权限，以及克隆后台运行时的前台通知。文件写入使用系统文件授权框架（SAF），作用范围限于用户授权的文件夹。")
                DocLine("功能边界", "不含隧道、代理或第三方中转功能；默认仅访问 GitHub 官方端点，不上传 Token 与 SSH 私钥；仅用于下载公开开源资源。")
                DocLine("校验", "镜像通道提供「校验」按钮复制官方校验值；开启加速时应用自动比对 SHA256 并提示内容是否被替换。")
                DocLine("开源许可", "内置的 wget / curl / aria2c / libgit2 与全部 JVM 依赖的许可证及源码出处列于「设置 → 开源许可」，许可证全文随 APK 打包（assets/licenses）。" +
                    "应用源码以 MIT 发布；上述 GPL 程序均为 exec 调用的独立进程，未链接进应用，libgit2 依其自带的链接例外。仓库根目录有 LICENSE 与 NOTICE。")
                DocLine("内置二进制", "aria2c / libgit2 / curl " + Fetcher.BUNDLED_CURL_VERSION +
                    " / wget " + Fetcher.BUNDLED_WGET_VERSION + " 均为内置开源程序，仅在下载 / 克隆 / 浏览时执行。" +
                    "curl 与 wget 使用应用自带的根证书包（与 aria2 同一份），证书校验完整启用。")
                DocLine("版本", "版本 " + BuildConfig.VERSION_NAME + "（构建 " + BuildConfig.VERSION_CODE + "），以「设置 → 关于」为准。")
            }
        }

        Spacer(Modifier.padding(bottom = 4.dp))
        Text(
            "完整合规说明见「设置 → 关于」。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 文档条目：标题行 + 正文行 */
@Composable
private fun DocLine(title: String, body: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 有序步骤 */
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
