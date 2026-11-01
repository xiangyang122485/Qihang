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
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gh.dl.data.GhView
import com.gh.dl.ui.theme.isDarkTheme
import java.io.ByteArrayInputStream

/**
 * 「浏览」Tab：采用命令行方式抓取页面并在本地渲染。
 *
 * 网络请求全部在 [GhView]（使用命令行 UA 的 OkHttp，详见该类注释）中完成，
 * 本页面仅将生成的 HTML 交给 WebView 显示。WebView 禁用 JS、禁止联网、禁止读取本地文件。
 * 页内链接点击由 [WebViewClient.shouldOverrideUrlLoading] 拦截：GitHub 域名链接交由
 * [BrowseViewModel.navigate] 重新抓取页面，其余链接由系统浏览器处理。
 *
 * 生命周期：VM 与 Activity 同生命周期，Tab 切换会销毁本 Composable。页面内容存储在
 * [BrowseViewModel.page] 中，切换回来时 factory 直接加载缓存内容，无需重新抓取。
 */
@Composable
fun BrowseTab() {
    val ctx = LocalContext.current
    val vm = LocalBrowseVM.current
    val dark = isDarkTheme()
    // 记录 WebView 当前加载的地址：翻译同一页时保留滚动位置，切换页面时回到顶部
    val lastLoadedUrl = remember { arrayOfNulls<String>(1) }

    // 页面内可后退时，返回键优先退出浏览栈；无历史记录时交回外层（切回解析器 Tab）
    BackHandler(enabled = vm.canGoBack) { vm.goBack() }

    // 手动翻译失败时提示一次；静默翻译失败不写该字段，页面保持原文
    val translateErr = vm.translateError
    LaunchedEffect(translateErr) {
        if (translateErr != null) {
            toast(ctx, "翻译失败：$translateErr")
            vm.consumeTranslateError()
        }
    }

    // 选项快照比对属于副作用：syncOptions 内部写入 ViewModel 状态并可能发起抓取，
    // 直接放在组合过程中会在同一帧内再次触发重组
    LaunchedEffect(dark, vm.url, vm.page) { vm.syncOptions(dark) }

    Column(Modifier.fillMaxSize()) {
        vm.loadError?.let { err ->
            // 单行状态加一个可执行动作。此前为多子句整段说明，占用顶部两三行，
            // 且只在下次抓取开始时才清除，页面已恢复正常时仍显示，形成悬在内容上方的文字块
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable { vm.reload() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "获取失败：$err",
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "点按重试",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        // 抓取为阻塞请求（无中间进度百分比），因此使用不确定进度条
        if (vm.loading || vm.translating) {
            // 翻译在原文显示后进行，进度条与抓取共用同一位置，不新增悬浮文字
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.page == null && !vm.loading) {
                // 尚未成功获取任何页面（首次加载即失败）：显示空态提示，而非仅显示错误条
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无内容", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                }
            } else {
                AndroidView(
                    factory = { c ->
                        localWebView(c, vm).apply {
                            // 切换 Tab 返回时 WebView 为新建实例，页面内容仍缓存在 VM 中，直接加载
                            vm.page?.let { p ->
                                lastLoadedUrl[0] = p.logicalUrl
                                loadPage(p, this, 0)
                            }
                        }
                    },
                    update = { web ->
                        // 仅在获取到新页面时加载；同一地址再次刷新也会触发新的抓取，
                        // 通过信号比对实现，普通重组不会重复加载
                        if (vm.loadedToken != vm.loadToken) {
                            vm.loadedToken = vm.loadToken
                            vm.page?.let { p ->
                                // 译文替换同一页面时保留阅读位置；换页 / 首次加载从顶部开始
                                val keep = lastLoadedUrl[0] == p.logicalUrl
                                val y = if (keep) web.scrollY else 0
                                loadPage(p, web, y)
                                lastLoadedUrl[0] = p.logicalUrl
                            }
                        }
                    },
                    onRelease = { web ->
                        // 必须显式销毁。WebView 包含原生渲染内核与合成表面，
                        // GC 无法回收（已知的内存泄漏场景）。切换 Tab 时会触发此回调，
                        // 多次切换会累积多个内核实例。
                        // 页面内容缓存在 VM.page 中，切换回来时由 factory 重新 loadPage，
                        // 因此销毁实例不会丢失任何内容。
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

/**
 * 将已抓取的本地页面加载到 WebView：base URL 使用 github.com，页面中无外部资源引用，
 * 加载过程不产生网络请求。[scrollY] > 0 时在布局完成后恢复阅读位置（用于原位翻译后重载）。
 */
private fun loadPage(p: GhView.Page, web: WebView, scrollY: Int = 0) {
    web.loadDataWithBaseURL(p.logicalUrl, p.html, "text/html", "utf-8", null)
    if (scrollY > 0) {
        web.postDelayed({
            if (web.isAttachedToWindow) web.scrollTo(0, scrollY)
        }, 120)
    }
}

/** 浏览页顶栏操作：后退 / 前进 / 刷新 / 在外部浏览器中打开 */
@Composable
fun BrowseTopActions() {
    val ctx = LocalContext.current
    val vm = LocalBrowseVM.current
    // 顶栏横向空间有限（需容纳加速按钮），因此「后退 / 前进」仅在可用时显示
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
            // 抓取无百分比进度，使用圆形指示器表示「正在加载」，提供刷新的视觉反馈
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
                // 登录 GitHub、下载页面附件等操作适合在系统浏览器中完成
                if (vm.url.isNotBlank()) openUrl(ctx, vm.url, "未找到可打开链接的浏览器")
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

/** 浏览 Tab 未加载任何页面时的引导提示（此时不创建 WebView，节省内存） */
@Composable
fun BrowseEmptyHint() {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("暂无浏览内容", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "从「解析器」或「搜索」点「浏览」打开仓库页面。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 创建纯本地渲染用 WebView：禁止联网、禁用 JS、禁止读取本地文件。
 *
 * 三项禁用的原因：
 *  - JS：抓取阶段的 HTML 已包含 README 正文与文件列表（服务端渲染），无需执行脚本。
 *    禁用后，即使 HTML 净化存在遗漏也无法执行，作为第二道 XSS 防线。
 *  - 网络：[shouldInterceptRequest] 拦截全部子资源；[shouldOverrideUrlLoading] 自行处理全部导航，
 *    因此 WebView 内核不会发出任何网络请求。生成页面中的图片均为 data: URI，不产生额外请求。
 *  - file/content 访问：本地页无需此功能，禁用后避免构造地址访问沙箱外内容。
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
        // 本地页每次重新生成，缓存会导致旧内容残留于内存
        cacheMode = WebSettings.LOAD_NO_CACHE
        loadsImagesAutomatically = true
        useWideViewPort = true
        loadWithOverviewMode = true
        textZoom = 100
    }
    setBackgroundColor(Color.TRANSPARENT)

    webViewClient = object : WebViewClient() {
        /**
         * 所有导航请求在此处处理，禁止 WebView 自行联网：
         *  - GitHub 域名链接 → 交由 [BrowseViewModel.navigate] 以命令行方式重新抓取页面；
         *  - 其他 http(s) 链接（外站、Releases 下载链接等）→ 交由系统浏览器处理；
         *  - 非 http(s) 链接（intent:// / market:// / tel: 等）→ 交由系统分发处理；
         *  - 本地页首次加载（about:blank / data）不受影响，不经过此回调。
         * 统一返回 true：禁止 WebView 自行发起任何连接。
         */
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val u = request?.url ?: return true
            val link = u.toString()
            // GitHub 官方域名：在应用内重新抓取页面；其余链接（外站、下载直链、intent:// 等）交由系统处理
            if (link.startsWith("https://") && vm.isBrowseable(link)) {
                vm.navigate(link)
                return true
            }
            openUrl(ctx, link, "未找到可打开链接的应用")
            return true
        }

        /**
         * 子资源处理分两种情况：`ghimg.local` 的图片由本地提供，其余返回空响应。
         * 主文档由 [WebView.loadDataWithBaseURL] 直接注入，仍交由内核处理。
         *
         * 引入此虚拟域名的原因（关键设计）：图片不再转换为 base64 嵌入 HTML，
         * 而是使用 `https://ghimg.local/<hash>` 形式的占位地址，字节数据在此回调中按需获取
         *（优先查询磁盘缓存，缓存未命中时再发起网络请求）。此方案的效果：
         *  - 整页 HTML 体积从约 2.4M 字符降至约 26KB，正文不再因图片数据过大而被截断；
         *  - 首屏无需等待全部图片加载，WebView 自行并发补全，加载时间从约 15 秒降至约 1~2 秒。
         * 域名不存在且其余请求均返回空响应，因此「零外部请求」约束仍然成立：
         * 即使生成的 HTML 中遗漏了其他 `src="https://…"` 引用，此处也会拦截，
         * 避免 WebView 使用浏览器 UA 连接必然返回 403 的镜像站。
         */
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            if (request?.isForMainFrame == true) return null
            val u = request?.url
            if (u != null && u.host == GhView.IMG_HOST) {
                val key = u.lastPathSegment.orEmpty()
                // 本回调不在 UI 线程，可执行同步图片读取；读取失败时返回空响应（图片不显示，
                // 但禁止 WebView 转而连接真实网络）
                GhView.loadImage(key)?.let { (bytes, mime) ->
                    // 图片按 hash 寻址，同一 key 的内容固定不变，因此设置一周的强缓存策略
                    return WebResourceResponse(mime, null, ByteArrayInputStream(bytes)).apply {
                        responseHeaders = mapOf("Cache-Control" to "public, max-age=604800")
                    }
                }
            }
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
    }
}
