package com.gh.dl.data

import java.net.URLDecoder

/**
 * 国内高校镜像站的 GitHub Release 镜像通道。
 *
 * 背景：在很多网络下 GitHub 的 HTTPS 完全不可达（DNS 污染 + IP 段阻断 + TLS 重置），
 * 任何"优选 IP"类方案都无效。但国内镜像站（中科大 USTC / 南大 NJU / 清华 TUNA /
 * 华为云 / 北京理工 BIT / 上海交大 SJTU）长期同步各大开源项目的 Release 产物，
 * 目录结构与 GitHub 一致，且附带 sha256 校验值。
 *
 * 这是合法、公开、可审计的官方镜像服务，不涉及任何代理或隧道：
 * 流量直达镜像站，不会经过不明第三方。
 *
 * 路径形态：
 *   https://mirrors.ustc.edu.cn/github-release/{owner}/{repo}/
 *     ├─ LatestRelease/        最新版本（软链）
 *     └─ 0.12.21/              历史版本
 *          ├─ xxx.tar.gz
 *          └─ sha256.sum
 *
 * 三家目录页格式不同：USTC 是表格（链接为绝对路径、大小在 title 属性里）、
 * NJU 是 nginx 表格（相对路径 + %20 编码、大小在 td.size 单元格）、
 * TUNA 是 JS 渲染。所以统一用通用锚点解析，再用"链接必须落在当前目录下"这条约束过滤噪音。
 */
object Mirror {

    /** 所有同步了 GitHub Release 的国内镜像站（均为公开、直连、无中转）。
     *  顺序即默认尝试顺序；用户在「解析器→源选择」里挑了某家时，会把它排到最前优先尝试。
     *  注：仅收录「确实同步了 github-release 目录」的镜像站；那些只镜像 Linux 发行版 /
     *  语言包（PyPI / npm / Docker 等）的高校镜像站不在此列（它们取不到 GitHub 的 Release 文件）。 */
    private val BASES = listOf(
        "USTC 中科大" to "https://mirrors.ustc.edu.cn/github-release/",
        "NJU 南大" to "https://mirror.nju.edu.cn/github-release/",
        "TUNA 清华" to "https://mirrors.tuna.tsinghua.edu.cn/github-release/",
        "Huawei 华为云" to "https://mirrors.huaweicloud.com/github-release/",
        "BIT 北京理工" to "https://mirror.bjtu.edu.cn/github-release/",
        "SJTU 上海交大" to "https://ftp.sjtu.edu.cn/github-release/",
        "HUST 华中科技" to "https://mirrors.hust.edu.cn/github-release/"
    )

    /** 最新版本的目录名，镜像站会把它作为软链同步 */
    private const val LATEST = "LatestRelease"

    data class Asset(val name: String, val url: String, val size: Long)

    /**
     * @param versions 版本目录（存的是相对仓库目录的路径片段，如 "LatestRelease/"、"0.12.21/"）
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

    private val ANCHOR = Regex("<a\\s[^>]*href=\"([^\"]+)\"[^>]*>([^<]*)</a>", RegexOption.IGNORE_CASE)
    private val TITLE_SIZE = Regex("title=\"(\\d+) bytes\"", RegexOption.IGNORE_CASE)
    private val SIZE_CELL = Regex("<td[^>]*class=\"size\"[^>]*>([^<]*)</td>", RegexOption.IGNORE_CASE)
    private val HUMAN = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(B|KiB|MiB|GiB|TiB|KB|MB|GB|TB)", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("<[^>]*>")

    private fun origin(base: String): String = base.substringBefore("/github-release/")

    private fun decode(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    private data class Entry(val tail: String, val name: String, val url: String, val size: Long, val dir: Boolean)

    /**
     * 通用解析：把页面里所有链接取出来，只保留"落在当前目录下"的那些，
     * 以此排除导航栏、静态资源、站点首页等无关链接。
     */
    private fun parse(base: String, dirUrl: String, html: String): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (m in ANCHOR.findAll(html)) {
            val href = m.groupValues[1].trim()
            if (href.startsWith("?") || href.startsWith("#")) continue
            if (href.startsWith("../") || href == ".." || href == ".") continue

            // 相对链接要相对"当前目录"解析，而不是站点根
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

    /** 从锚点后面的那段 HTML 里推断文件大小 */
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

        // nginx 自动索引：链接后面跟着的最后一个长数字就是字节数
        return Regex("\\d{4,}").findAll(window.replace(TAGS, " "))
            .mapNotNull { it.value.toLongOrNull() }.lastOrNull() ?: 0L
    }

    /** 版本号倒序：0.12.21 排在 0.9.3 前面 */
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

    /** 展示用的版本名（去掉尾部斜杠、还原 %20） */
    fun label(v: String): String = decode(v.trimEnd('/'))

    fun isLatest(v: String): Boolean = v.trimEnd('/').equals(LATEST, ignoreCase = true)

    /** 某镜像站上该仓库的版本目录；仓库不存在时返回空 */
    private fun versionDirs(base: String, slug: String): List<Entry> {
        val dir = base + slug + "/"
        val html = Net.text(dir)
        val dirs = parse(base, dir, html).filter { it.dir }
        val latest = dirs.filter { isLatest(it.tail) }
        val rest = dirs.filterNot { isLatest(it.tail) }.sortedWith(
            Comparator { a, b -> VERSION_DESC.compare(a.tail, b.tail) }
        )
        return latest + rest
    }

    /** 某个版本目录下的文件列表 */
    private fun assets(base: String, slug: String, verTail: String): List<Asset> {
        val dir = base + slug + "/" + verTail
        val html = Net.text(dir)
        return parse(base, dir, html).filter { !it.dir }.map { Asset(it.name, it.url, it.size) }
    }

    /** 依次尝试各家镜像，返回第一个命中的；prefer 非空时把它排到最前面优先尝试 */
    fun open(slug: String, preferVer: String? = null, prefer: String? = null): Hit? {
        val bases = if (prefer != null) BASES.sortedBy { if (it.second == prefer) 0 else 1 } else BASES
        for ((name, base) in bases) {
            try {
                val dirs = versionDirs(base, slug)
                if (dirs.isEmpty()) continue
                val ver = preferVer?.takeIf { p -> dirs.any { it.tail == p } } ?: dirs.first().tail
                val list = assets(base, slug, ver)
                if (list.isEmpty()) continue
                return Hit(name, base, slug, dirs.map { it.tail }, ver, list)
            } catch (e: Exception) {
                // 这家没有该仓库 → 换下一家
            }
        }
        return null
    }

    /** 取 sha256.sum 里某个文件的校验值，取不到返回 null */
    fun sha256(hit: Hit, fileName: String): String? {
        return try {
            val txt = Net.text(hit.base + hit.slug + "/" + hit.version + "sha256.sum")
            txt.lineSequence().firstOrNull { line ->
                val f = line.trim().split(Regex("\\s+")).lastOrNull().orEmpty().removePrefix("*")
                f == fileName
            }?.trim()
        } catch (e: Exception) { null }
    }

    /**
     * 一次把这个仓库最多 [max] 个版本的产物都取回来，每个版本一张卡单独下载。
     * 这样用户即使 GitHub 官方线路不可达，也能在国内镜像上挑「某一个版本里的某一个文件」
     * （APK / EXE / DEB / IPA……）直接下载，完全不需要克隆整个仓库。
     *
     * 返回的是 (版本目录片段, 该版本的 Hit)，顺序为最新版本在前。
     */
    fun openAll(slug: String, max: Int = 5, prefer: String? = null): List<Pair<String, Hit>> {
        val bases = if (prefer != null) BASES.sortedBy { if (it.second == prefer) 0 else 1 } else BASES
        for ((name, base) in bases) {
            try {
                val dirs = versionDirs(base, slug)
                if (dirs.isEmpty()) continue
                val out = ArrayList<Pair<String, Hit>>()
                for (d in dirs.take(max)) {
                    val list = assets(base, slug, d.tail)
                    if (list.isNotEmpty()) {
                        out.add(
                            d.tail to Hit(name, base, slug, dirs.map { it.tail }, d.tail, list)
                        )
                    }
                }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
                // 这家镜像站没有该仓库 → 换下一家
            }
        }
        return emptyList()
    }

    /** 该文件属于哪类产物，用于在界面上做标记 */
    fun kind(name: String): String = platformOf(name)
}
