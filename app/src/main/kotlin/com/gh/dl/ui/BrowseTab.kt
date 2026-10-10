package com.gh.dl.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gh.dl.data.GhView
import com.gh.dl.ui.theme.isDarkTheme
import java.io.ByteArrayInputStream

/**
 * 「浏览」Tab：**命令行式抓取 + 本地渲染**。
 *
 * 网络全在 [GhView]（CLI UA 的 OkHttp，见该类注释）里完成，本页面只是把生成的 HTML
 * 交给 WebView 显示，所以这里的 WebView **不开 JS、不联网、不许读本地文件**。
 * 页内链接点击被 [WebViewClient.shouldOverrideUrlLoading] 拦下：github 系地址回到
 * [BrowseViewModel.navigate] 再抓一页，其余地址交系统浏览器（不替用户决定装什么 App）。
 *
 * 生命周期：VM 与 Activity 同生命周期，Tab 切换会销毁本 Composable。页面内容存在
 * [BrowseViewModel.page] 里，所以切回来时 factory 直接把它加载好，不用重新抓取。
 */
@Composable
fun BrowseTab() {
    val ctx = LocalContext.current
    val vm = LocalBrowseVM.current
    val dark = isDarkTheme()

    // 页面内可后退时，返回键先退浏览栈；退无可退才交回外层（切回解析器 Tab）
    BackHandler(enabled = vm.canGoBack) { vm.goBack() }

    // 加速开关与深浅色都在这里读：拨闪电 / 改外观会触发重组，
    // syncOptions 比对快照后按新通道、新配色重取本页
    vm.syncOptions(dark)

    Column(Modifier.fillMaxSize()) {
        vm.loadError?.let { err ->
            // 抓不开时给一条能看懂的提示，而不是留个白屏让人猜
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "抓不开：$err。" +
                        (if (AppState.browseAccelOn)
                            "已开浏览加速（先试 " + GhView.pageMirrors.joinToString("、") { it.removePrefix("https://") } +
                                "，再直连 GitHub），可点刷新重试，或在「设置 → 加速 → 浏览加速」换镜像站。"
                         else "可点顶栏闪电开启浏览加速（改走网页镜像站），或用右上角在外部浏览器打开。"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // 抓取是一整个阻塞请求（没有中间百分比），所以用不定进度条
        if (vm.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.page == null && !vm.loading) {
                // 还没成功抓到任何一页（首次就失败）：给个空态，别只显示一条提示条
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有内容", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                }
            } else {
                AndroidView(
                    factory = { c ->
                        localWebView(c, vm).apply {
                            // 切 Tab 回来时 WebView 是新建的，但页面内容还在 VM 里，直接加载它
                            vm.page?.let { loadPage(it, this) }
                        }
                    },
                    update = { web ->
                        // 只在「抓到新页面」时加载；同一个地址再点刷新也是新的一次抓取，
                        // 会走同一信号，普通重组不会重复加载
                        if (vm.loadedToken != vm.loadToken) {
                            vm.loadedToken = vm.loadToken
                            vm.page?.let { loadPage(it, web) }
                        }
                    },
                    onRelease = { web ->
                        // 必须真销毁。WebView 里面挂着一整块原生渲染内核与合成表面，
                        // GC 回收不到它（历史上有名的泄漏）；而切走 Tab 就会走这里，
                        // 用户来回切几个 Tab 就能攒出好几个内核。
                        // 页面内容存在 VM.page 里，切回来由 factory 重新 loadPage，
                        // 所以销毁实例不丢任何内容。
                        runCatching {
                            web.stopLoading()
                            (web.parent as? android.view.ViewGroup)?.removeView(web)
                            web.destroy()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** 把抓好的本地页面塞进 WebView：base 用 github.com，页面里已无外部资源，加载不产生请求 */
private fun loadPage(p: GhView.Page, web: WebView) {
    web.loadDataWithBaseURL(p.logicalUrl, p.html, "text/html", "utf-8", null)
}

/** 浏览页顶栏动作：后退 / 前进 / 刷新 / 用外部浏览器打开 */
@Composable
fun BrowseTopActions() {
    val ctx = LocalContext.current
    val vm = LocalBrowseVM.current
    // 顶栏横向空间有限（还要放闪电按钮），所以「后退 / 前进」只在真能退、真能进时才出现
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (vm.canGoBack) {
            IconButton(onClick = { vm.goBack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "后退",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (vm.canGoForward) {
            IconButton(onClick = { vm.goForward() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward, "前进",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (vm.loading) {
            // 抓取没有百分比，用一个小圈圈表示「正在取」，免得点了刷新看着像没反应
            CircularProgressIndicator(
                modifier = Modifier.width(16.dp).height(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
        } else {
            IconButton(onClick = { vm.reload() }, enabled = vm.url.isNotBlank()) {
                Icon(Icons.Filled.Refresh, "刷新", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(
            onClick = {
                // 登录 GitHub、下载页面附件这类操作，系统浏览器合适得多
                if (vm.url.isNotBlank()) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(vm.url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            },
            enabled = vm.url.isNotBlank()
        ) {
            Icon(
                Icons.Filled.OpenInBrowser, "在浏览器打开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 浏览 Tab 还没加载任何页面时的提示（此时不创建 WebView，省内存） */
@Composable
fun BrowseEmptyHint() {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("还没有浏览内容", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "在「解析器」页点蓝色的「浏览」按钮，或在「搜索」结果里点「浏览」，" +
                "就会在这一页显示对应的仓库页（README、文件列表、Releases、Issues）。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "这一页不联网：内容是用命令行方式（curl 类 UA）抓下来后本地渲染的，" +
                "所以不需要 JS，也不会加载任何外部脚本或图片。" +
                "顶栏闪电切的是「浏览加速」（和解析器页那个管下载的闪电各管各的）：" +
                "开着时先走网页镜像站、失败再直连 GitHub；关着时只直连（走真实 IP 优选）。" +
                "注意镜像站只放行 git/curl 类 UA，所以这类页面在普通浏览器内核里是打不开的。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * 创建一个「纯本地显示器」WebView：不联网、不跑 JS、不许读本地文件。
 *
 * 三项关掉的道理：
 *  - JS：抓取阶段的 HTML 已经包含 README 正文与文件列表（服务端渲染），不需要跑脚本。
 *    关掉它，即使净化有漏网之鱼也执行不了，等于第二道 XSS 防线。
 *  - 网络：[shouldInterceptRequest] 一律拦掉子资源；[shouldOverrideUrlLoading] 一律自己处理，
 *    所以这个内核不会发出任何请求。生成的页面里图片都是 data:，也不会有请求。
 *  - file/content 访问：本地页不需要，关掉避免被构造出的地址碰到沙箱外内容。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun localWebView(ctx: Context, vm: BrowseViewModel): WebView = WebView(ctx).apply {
    settings.apply {
        javaScriptEnabled = false
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        // 本地页每次由我们重新生成，缓存反而会让旧内容留在内存里
        cacheMode = WebSettings.LOAD_NO_CACHE
        loadsImagesAutomatically = true
        useWideViewPort = true
        loadWithOverviewMode = true
        textZoom = 100
    }
    setBackgroundColor(Color.TRANSPARENT)

    webViewClient = object : WebViewClient() {
        /**
         * 所有导航都在这里消化，绝不让 WebView 自己联网：
         *  - github 系链接 → 交 [BrowseViewModel.navigate] 用命令行方式重抓一页；
         *  - 其他 http(s)（外站、Releases 下载链接等）→ 交系统浏览器；
         *  - 非 http(s)（intent:// / market:// / tel: 等）→ 同样交系统分发；
         *  - 本地页自身的首次加载（about:blank / data）不受影响，它不走这个回调。
         * 一律 return true：WebView 自己一次都不去连。
         */
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val u = request?.url ?: return true
            val link = u.toString()
            // github 官方域：在 App 内再抓一页；其余（外站、下载直链、intent:// 等）交系统
            if (link.startsWith("https://") && vm.isBrowseable(link)) {
                vm.navigate(link)
                return true
            }
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return true
        }

        /**
         * 子资源只有两种下场：`ghimg.local` 的图片由我们自己给，其余一律空响应。
         * 主文档由 [WebView.loadDataWithBaseURL] 直接喂进来，仍交回内核处理。
         *
         * 为什么要专门处理这个虚拟域名（第六轮的关键改动）：图片不再转 base64 塞进 HTML，
         * 而是写成 `https://ghimg.local/<hash>` 这种**根本不存在**的地址，字节由这里现取现给
         * （先查磁盘缓存，没有才联网）。这样
         *  - 整页 HTML 从 2.4M 字符掉回 26KB 量级，正文不再被图片挤到「内容过长」截断；
         *  - 首屏不必等几十张图，WebView 自己并发补齐，观感从 15s 级降到 1~2s 级。
         * 域名不存在 + 其余一律空响应，所以「零外部请求」这条仍然成立：
         * 万一生成的 HTML 里漏了别的 `src="https://…"`，这里照样掐掉，
         * 不会让 WebView 用浏览器 UA 去连必然 403 的镜像站。
         */
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            if (request?.isForMainFrame == true) return null
            val u = request?.url
            if (u != null && u.host == GhView.IMG_HOST) {
                val key = u.lastPathSegment.orEmpty()
                // 本回调不在 UI 线程，可以直接做同步取图；取不到给空响应（图就是显示不出来，
                // 但不能让 WebView 转而去连真网络）
                GhView.loadImage(key)?.let { (bytes, mime) ->
                    // 图片按 hash 寻址，同一 key 的内容永远不变，所以给一周的强缓存标记
                    return WebResourceResponse(mime, null, ByteArrayInputStream(bytes)).apply {
                        responseHeaders = mapOf("Cache-Control" to "public, max-age=604800")
                    }
                }
            }
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
    }
}
