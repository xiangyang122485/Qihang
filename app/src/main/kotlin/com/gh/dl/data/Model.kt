package com.gh.dl.data

import org.json.JSONArray
import org.json.JSONObject

data class RepoInfo(
    val fullName: String = "",
    val description: String = "",
    val htmlUrl: String = "",
    val stars: Int = 0,
    val forks: Int = 0,
    val language: String = "",
    val license: String = "",
    val sizeKb: Long = 0,
    val defaultBranch: String = "main",
    val pushedAt: String = ""
)

data class Asset(
    val name: String = "",
    val size: Long = 0,
    val downloads: Int = 0,
    val url: String = ""
)

data class Release(
    val tag: String = "",
    val name: String = "",
    val pre: Boolean = false,
    val published: String = "",
    val htmlUrl: String = "",
    val assets: List<Asset> = emptyList()
)

fun parseRepo(o: JSONObject): RepoInfo = RepoInfo(
    fullName = o.optString("full_name"),
    description = o.optString("description"),
    htmlUrl = o.optString("html_url"),
    stars = o.optInt("stargazers_count"),
    forks = o.optInt("forks_count"),
    language = o.optString("language"),
    license = o.optJSONObject("license")?.optString("spdx_id") ?: "",
    sizeKb = o.optLong("size"),
    defaultBranch = o.optString("default_branch", "main"),
    pushedAt = o.optString("pushed_at")
)

fun parseReleases(arr: JSONArray): List<Release> {
    val out = ArrayList<Release>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val assetsArr = o.optJSONArray("assets")
        val assets = ArrayList<Asset>()
        if (assetsArr != null) {
            for (j in 0 until assetsArr.length()) {
                val a = assetsArr.optJSONObject(j) ?: continue
                assets.add(
                    Asset(
                        name = a.optString("name"),
                        size = a.optLong("size"),
                        downloads = a.optInt("download_count"),
                        url = a.optString("browser_download_url")
                    )
                )
            }
        }
        out.add(
            Release(
                tag = o.optString("tag_name"),
                name = o.optString("name"),
                pre = o.optBoolean("prerelease"),
                published = o.optString("published_at"),
                htmlUrl = o.optString("html_url"),
                assets = assets
            )
        )
    }
    return out
}

fun fmtSize(bytes: Long): String {
    if (bytes <= 0) return "-"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (i == 0) "${v.toLong()} ${units[i]}" else "${String.format("%.1f", v)} ${units[i]}"
}

/**
 * 清洗仓库描述（GitHub / Gitee / GitCode 的 description 都是用户自填，脏得千奇百怪）。
 *
 * 实测两类问题（不是解码 bug —— API 原样返回的就是这串字节）：
 *  1. 超长：funNLP 那种把整个 README 目录粘进 description，5800+ 字符；
 *  2. 乱码：Some-Many-Books 那种 GBK 被按别的编码存进来的 mojibake，55000 字符，
 *     还夹带全角空格 U+3000、私有区字符（U+E000–U+F8FF）与控制字符。
 *
 * 做法：先剥掉不可见/私有区字符并把所有空白（含全角空格）压成一个空格；
 * 再在「第一段超长连续汉字」处截断 —— 正常中文描述里最长的一段连续汉字实测 ≤ 29
 * （funNLP 29、chinese-poetry 12、ruanyf 7），而乱码块是 2584，
 * 所以阈值取 40 既能切掉乱码又不会误伤正常句子（正常句子必然被标点/空格打断）。
 * 截断点之前若还有可读内容就保留，正好把「个人收藏书籍列表 PDF下载 百度云」这种
 * 有效前缀留下。最后按 maxLen 截尾。
 */
fun cleanDesc(raw: String, maxLen: Int = 240): String {
    if (raw.isBlank()) return ""
    val sb = StringBuilder(raw.length.coerceAtMost(maxLen + 64))
    for (c in raw) {
        when {
            c == '\t' || c == '\r' || c == '\n' -> sb.append(' ')
            // 全角空格、NBSP、其它 Unicode 空白 → 普通空格
            c == '\u3000' || c == '\u00a0' || c == '\u2007' || c == '\u202f' -> sb.append(' ')
            c.isISOControl() -> sb.append(' ')
            // 私有区字符（乱码常见）与零宽字符直接丢
            c in '\uE000'..'\uF8FF' || c == '\u200B' || c == '\uFEFF' -> { /* 丢弃 */ }
            else -> sb.append(c)
        }
    }
    // 折叠连续空格
    var s = sb.toString().replace(Regex(" +"), " ").trim()
    // 在第一段「超长连续汉字」处截断：整段乱码连同其后内容一起丢掉
    val garbleRun = 60
    var run = 0
    var cut = -1
    for (i in s.indices) {
        val c = s[i]
        val isCjk = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'
        if (isCjk) {
            run++
            if (run == garbleRun) { cut = i - garbleRun + 1; break }
        } else run = 0
    }
    if (cut > 0) s = s.substring(0, cut).trimEnd()
    if (s.length > maxLen) {
        val head = s.substring(0, maxLen)
        // 尽量在词/句边界收尾，别把英文单词劈成两半
        val back = head.lastIndexOf(' ').coerceAtLeast(head.lastIndexOf('，').coerceAtLeast(head.lastIndexOf('、')))
        s = if (back > maxLen / 2) head.substring(0, back) else head
        s = s.trimEnd(',', '，', '、', ' ', ';', '；', ':', '：') + "…"
    }
    // 乱码块前面常留着 "==""~~" 这类分隔垃圾符号，收尾时一并去掉
    // 注意不要 trim '.'：正常英文描述会以句号结尾，去掉它反而像被截断
    return s.trimEnd(' ', '=', '*', '#', '~', '+', '/', '\\', '|', '>', '<', '·', '•', '．')
}

fun osOf(name: String): String {
    val n = name.lowercase()
    return when {
        n.endsWith(".apk") || n.endsWith(".aab") -> "android"
        n.endsWith(".dmg") || n.endsWith(".pkg") || n.contains("macos") || n.contains("darwin") -> "macos"
        n.endsWith(".exe") || n.endsWith(".msi") || n.contains("win") -> "windows"
        n.endsWith(".appimage") || n.endsWith(".deb") || n.endsWith(".rpm") || n.contains("linux") -> "linux"
        else -> "other"
    }
}

fun platformOf(name: String): String {
    val n = name.lowercase()
    val os = when (osOf(name)) {
        "android" -> "Android"
        "macos" -> "macOS"
        "windows" -> "Windows"
        "linux" -> "Linux"
        else -> "通用"
    }
    val arch = when {
        n.contains("x86_64") || n.contains("amd64") || n.contains("x64") -> "x64"
        n.contains("aarch64") || n.contains("arm64") -> "arm64"
        n.contains("armv7") || n.contains("armeabi") -> "arm"
        else -> ""
    }
    return if (arch.isEmpty()) os else "$os · $arch"
}
