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
    val assets: List<Asset> = emptyList(),
    /** Release 正文 / 更新说明（Markdown 原文）。仅官方 API 返回；Atom 兜底路径为空。 */
    val body: String = ""
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
                assets = assets,
                body = o.optString("body")
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
    val num = if (i == 0) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)
    return "$num ${units[i]}"
}

/** 连续半角空格：空白归一化后的折叠目标 */
private val MULTI_SPACE = Regex(" +")

/**
 * 清洗仓库描述（GitHub / Gitee / GitCode 的 description 均为用户自填，内容质量参差不齐）。
 *
 * 实测两类问题（非解码缺陷，API 原样返回的即为该字节序列）：
 *  1. 超长：如 funNLP 将整个 README 目录粘贴至 description，达 5800+ 字符；
 *  2. 乱码：如 Some-Many-Books 中 GBK 被按其他编码存储的 mojibake，达 55000 字符，
 *     并夹杂全角空格 U+3000、私有区字符（U+E000–U+F8FF）与控制字符。
 *
 * 处理方法：先移除不可见字符与私有区字符，并将所有空白（含全角空格）压缩为单个空格；
 * 再在「第一段超长连续汉字」处截断。正常中文描述中最长连续汉字实测 ≤ 29
 * （funNLP 29、chinese-poetry 12、ruanyf 7），而乱码块为 2584，
 * 因此阈值取 40 既可截断乱码又不会误伤正常句子（正常句子必然被标点或空格打断）。
 * 截断点之前若仍有可读内容则保留，可将「个人收藏书籍列表 PDF下载 百度云」这类
 * 有效前缀保留。最后按 maxLen 截尾。
 */
fun cleanDesc(raw: String, maxLen: Int = 240): String {
    if (raw.isBlank()) return ""
    val sb = StringBuilder(raw.length.coerceAtMost(maxLen + 64))
    for (c in raw) {
        when {
            c == '\t' || c == '\r' || c == '\n' -> sb.append(' ')
            // 全角空格、NBSP、其他 Unicode 空白转换为普通空格
            c == '\u3000' || c == '\u00a0' || c == '\u2007' || c == '\u202f' -> sb.append(' ')
            c.isISOControl() -> sb.append(' ')
            // 私有区字符（乱码常见）与零宽字符直接移除
            c in '\uE000'..'\uF8FF' || c == '\u200B' || c == '\uFEFF' -> { /* 丢弃 */ }
            else -> sb.append(c)
        }
    }
    // 折叠连续空格
    var s = sb.toString().replace(MULTI_SPACE, " ").trim()
    // 在第一段「超长连续汉字」处截断：整段乱码连同其后内容一起丢弃
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
        // 尽量在词或句边界收尾，避免将英文单词截断
        val back = head.lastIndexOf(' ').coerceAtLeast(head.lastIndexOf('，').coerceAtLeast(head.lastIndexOf('、')))
        s = if (back > maxLen / 2) head.substring(0, back) else head
        s = s.trimEnd(',', '，', '、', ' ', ';', '；', ':', '：') + "…"
    }
    // 乱码块前常残留 "=="、"~~" 等分隔符号，收尾时一并移除
    // 注意不可 trim '.'：正常英文描述以句号结尾，移除后反而呈现截断状态
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
