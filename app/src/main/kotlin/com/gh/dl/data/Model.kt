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
