package com.gh.dl.data

import java.net.URLDecoder

/**
 * 国内高校镜像站的 GitHub Release 镜像通道。
 *
 * 背景：在部分网络环境下 GitHub 的 HTTPS 完全不可达（域名解析结果不可信 + IP 段阻断 + TLS 重置），
 * 任何"优选 IP"类方案均无效。但国内镜像站（中科大 USTC / 南大 NJU / 清华 TUNA /
 * 华为云 / 北京理工 BIT / 上海交大 SJTU）长期同步各大开源项目的 Release 产物，
 * 目录结构与 GitHub 一致，并附带 sha256 校验值。
 *
 * 此为合法、公开、可审计的官方镜像服务，不涉及任何代理或隧道：
 * 流量直达镜像站，不经过任何第三方中转。
 *
 * 路径形态：
 *   https://mirrors.ustc.edu.cn/github-release/{owner}/{repo}/
 *     ├─ LatestRelease/        最新版本（软链）
 *     └─ 0.12.21/              历史版本
 *          ├─ xxx.tar.gz
 *          └─ sha256.sum
 *
 * 三家目录页格式不同：USTC 为表格（链接为绝对路径、大小在 title 属性中）、
 * NJU 为 nginx 表格（相对路径 + %20 编码、大小在 td.size 单元格中）、
 * TUNA 为 JS 渲染。因此统一使用通用锚点解析，再通过"链接必须位于当前目录下"这一约束过滤噪音。
 */
object Mirror {

    /** 所有已同步 GitHub Release 的国内镜像站（均为公开、直连、无中转）。
     *  顺序即默认尝试顺序；用户在「解析器→源选择」中选择某家时，会将其排至最前优先尝试。
     *  注：仅收录「确实同步了 github-release 目录」的镜像站；仅镜像 Linux 发行版 /
     *  语言包（PyPI / npm / Docker 等）的高校镜像站不在此列（无法获取 GitHub 的 Release 文件）。 */
    private val BASES = listOf(
        "USTC 中科大" to "https://mirrors.ustc.edu.cn/github-release/",
        "NJU 南大" to "https://mirror.nju.edu.cn/github-release/",
        "TUNA 清华" to "https://mirrors.tuna.tsinghua.edu.cn/github-release/",
        "Huawei 华为云" to "https://mirrors.huaweicloud.com/github-release/",
        "BIT 北京理工" to "https://mirror.bjtu.edu.cn/github-release/",
        "SJTU 上海交大" to "https://ftp.sjtu.edu.cn/github-release/",
        "HUST 华中科技" to "https://mirrors.hust.edu.cn/github-release/"
    )

    /** 最新版本的目录名，镜像站将其作为软链同步 */
    private const val LATEST = "LatestRelease"

    data class Asset(val name: String, val url: String, val size: Long)

    /**
     * @param versions 版本目录（存储相对仓库目录的路径片段，如 "LatestRelease/"、"0.12.21/"）
     * @param version  当前选中的版本目录片段
     */
    data class Hit(
        val mirror: String,
        val base: String,
        val slug: String,
        val versions: List<String>,
        val version: String,
        val assets: List<Asset>
    )

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    private val ANCHOR = Regex("<a\\s[^>]*href=\"([^\"]+)\"[^>]*>([^<]*)</a>", RegexOption.IGNORE_CASE)
    private val TITLE_SIZE = Regex("title=\"(\\d+) bytes\"", RegexOption.IGNORE_CASE)
    private val SIZE_CELL = Regex("<td[^>]*class=\"size\"[^>]*>([^<]*)</td>", RegexOption.IGNORE_CASE)
    private val HUMAN = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(B|KiB|MiB|GiB|TiB|KB|MB|GB|TB)", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("<[^>]*>")
    private val LONG_DIGITS = Regex("\\d{4,}")
    private val WS_SPLIT = Regex("\\s+")

    private fun origin(base: String): String = base.substringBefore("/github-release/")

    private fun decode(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    private data class Entry(val tail: String, val name: String, val url: String, val size: Long, val dir: Boolean)

    /**
     * 通用解析：提取页面中所有链接，仅保留"位于当前目录下"的链接，
     * 以此排除导航栏、静态资源、站点首页等无关链接。
     */
    private fun parse(base: String, dirUrl: String, html: String): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (m in ANCHOR.findAll(html)) {
            val href = m.groupValues[1].trim()
            if (href.startsWith("?") || href.startsWith("#")) continue
            if (href.startsWith("../") || href == ".." || href == ".") continue

            // 相对链接需相对"当前目录"解析，而非站点根
            val url = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> origin(base) + href
                else -> dirUrl + href
            }
            if (!url.startsWith(dirUrl)) continue
            val tail = url.removePrefix(dirUrl)
            if (tail.isEmpty() || tail.contains("?") || tail.contains("/../")) continue

            val isDir = tail.endsWith("/")
            val name = decode(tail.trimEnd('/').substringAfterLast('/'))
            if (name.isBlank()) continue
            if (name.startsWith(".") && name != ".gitignore") continue
            if (!seen.add(url)) continue

            val size = if (isDir) 0L else sizeOf(html, m.range.last + 1)
            out.add(Entry(tail, name, url, size, isDir))
        }
        return out
    }

    /** 从锚点后的 HTML 内容推断文件大小 */
    private fun sizeOf(html: String, from: Int): Long {
        if (from >= html.length) return 0L
        val window = html.substring(from, minOf(html.length, from + 300)).substringBefore("<a ")

        // USTC：title="12345 bytes"
        TITLE_SIZE.find(window)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }

        // NJU：<td class="size">11.1 MiB</td>
        val cell = SIZE_CELL.find(window)?.groupValues?.get(1)?.trim().orEmpty()
        val hm = HUMAN.find(cell)
        if (hm != null) {
            val n = hm.groupValues[1].toDoubleOrNull() ?: 0.0
            val unit = hm.groupValues[2].lowercase()
            val mul = when {
                unit.startsWith("t") -> 1024.0 * 1024 * 1024 * 1024
                unit.startsWith("g") -> 1024.0 * 1024 * 1024
                unit.startsWith("m") -> 1024.0 * 1024
                unit.startsWith("k") -> 1024.0
                else -> 1.0
            }
            return (n * mul).toLong()
        }

        // nginx 自动索引：链接后跟随的最后一个长数字即为字节数
        return LONG_DIGITS.findAll(window.replace(TAGS, " "))
            .mapNotNull { it.value.toLongOrNull() }.lastOrNull() ?: 0L
    }

    /** 版本号降序排列：0.12.21 排在 0.9.3 之前 */
    private val VERSION_DESC = Comparator<String> { a, b ->
        val ka = seg(a)
        val kb = seg(b)
        for (i in 0 until maxOf(ka.size, kb.size)) {
            val x = ka.getOrElse(i) { 0 }
            val y = kb.getOrElse(i) { 0 }
            if (x != y) return@Comparator y - x
        }
        b.compareTo(a)
    }

    private fun seg(v: String): List<Int> =
        v.split('.', '-', '_', ' ').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }

    /** 用于展示的版本文本（去除尾部斜杠、还原 %20 编码） */
    fun label(v: String): String = decode(v.trimEnd('/'))

    fun isLatest(v: String): Boolean = v.trimEnd('/').equals(LATEST, ignoreCase = true)

    /** 某镜像站上该仓库的版本目录；仓库不存在时返回空列表 */
    private fun versionDirs(base: String, slug: String, deadline: Long = Long.MAX_VALUE): List<Entry> {
        if (System.currentTimeMillis() >= deadline) return emptyList()
        val dir = base + slug + "/"
        val html = Net.text(dir, timeoutMs = 8_000)
        val dirs = parse(base, dir, html).filter { it.dir }
        val latest = dirs.filter { isLatest(it.tail) }
        val rest = dirs.filterNot { isLatest(it.tail) }.sortedWith(
            Comparator { a, b -> VERSION_DESC.compare(a.tail, b.tail) }
        )
        return latest + rest
    }

    /** 某个版本目录下的文件列表 */
    private fun assets(base: String, slug: String, verTail: String, deadline: Long = Long.MAX_VALUE): List<Asset> {
        if (System.currentTimeMillis() >= deadline) return emptyList()
        val dir = base + slug + "/" + verTail
        val html = Net.text(dir, timeoutMs = 8_000)
        return parse(base, dir, html).filter { !it.dir }.map { Asset(it.name, it.url, it.size) }
    }

    /**
     * 依次尝试各镜像站，返回首个命中结果；prefer 非空时将其排至最前优先尝试。
     * [budgetMs] 为整次查询的总时间预算：镜像站不可达时单次请求最长可等待数十秒，
     * 不设上限会让「解析」长时间无任何结果。
     */
    fun open(
        slug: String,
        preferVer: String? = null,
        prefer: String? = null,
        budgetMs: Long = 15_000
    ): Hit? {
        val deadline = System.currentTimeMillis() + budgetMs
        val bases = if (prefer != null) BASES.sortedBy { if (it.second == prefer) 0 else 1 } else BASES
        for ((name, base) in bases) {
            if (System.currentTimeMillis() >= deadline) break
            try {
                val dirs = versionDirs(base, slug, deadline)
                if (dirs.isEmpty()) continue
                val ver = preferVer?.takeIf { p -> dirs.any { it.tail == p } } ?: dirs.first().tail
                val list = assets(base, slug, ver, deadline)
                if (list.isEmpty()) continue
                return Hit(name, base, slug, dirs.map { it.tail }, ver, list)
            } catch (e: Exception) {
                // 该镜像站无此仓库 → 尝试下一家
            }
        }
        return null
    }

    /**
     * 从 sha256.sum 中提取指定文件的校验值，仅返回 64 位摘要本身，提取失败时返回 null。
     *
     * 此前该方法返回「整行」（形如 hash + 两个空格 + 文件名）。该值传递至下载完成后的
     * 比对逻辑（DownloadsViewModel 与 saved.sha 执行 equals），而 saved.sha 为纯 hex，
     * 导致所有经镜像下载的文件均误报「与官方校验值不符！文件可能被篡改」。
     */
    fun sha256(hit: Hit, fileName: String): String? {
        return try {
            val txt = Net.text(hit.base + hit.slug + "/" + hit.version + "sha256.sum")
            val line = txt.lineSequence().firstOrNull { l ->
                val f = l.trim().split(WS_SPLIT).lastOrNull().orEmpty().removePrefix("*")
                f == fileName
            } ?: return null
            val hash = line.trim().split(WS_SPLIT).firstOrNull()
                .orEmpty().removePrefix("*").lowercase()
            if (hash.matches(SHA256_HEX)) hash else null
        } catch (e: Exception) { null }
    }

    /**
     * 一次性获取该仓库最多 [max] 个版本的产物，每个版本独立一张卡片供下载。
     * 即使 GitHub 官方线路不可达，用户仍可在国内镜像上选择「某版本中的某文件」
     * （APK / EXE / DEB / IPA 等）直接下载，无需克隆整个仓库。
     *
     * 返回值为 (版本目录片段, 该版本的 Hit)，按最新版本优先排序。
     */
    fun openAll(
        slug: String,
        max: Int = 5,
        prefer: String? = null,
        budgetMs: Long = 15_000
    ): List<Pair<String, Hit>> {
        val deadline = System.currentTimeMillis() + budgetMs
        val bases = if (prefer != null) BASES.sortedBy { if (it.second == prefer) 0 else 1 } else BASES
        for ((name, base) in bases) {
            if (System.currentTimeMillis() >= deadline) break
            try {
                val dirs = versionDirs(base, slug, deadline)
                if (dirs.isEmpty()) continue
                val out = ArrayList<Pair<String, Hit>>()
                for (d in dirs.take(max)) {
                    if (System.currentTimeMillis() >= deadline) break
                    val list = assets(base, slug, d.tail, deadline)
                    if (list.isNotEmpty()) {
                        out.add(
                            d.tail to Hit(name, base, slug, dirs.map { it.tail }, d.tail, list)
                        )
                    }
                }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
                // 该镜像站无此仓库 → 尝试下一家
            }
        }
        return emptyList()
    }

    /** 该文件所属平台类型，用于界面标记 */
    fun kind(name: String): String = platformOf(name)
}
