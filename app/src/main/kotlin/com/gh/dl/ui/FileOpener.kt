package com.gh.dl.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.MimeTypeMap

/** 是否为可安装的 APK（按扩展名判定，大小写不敏感） */
fun isApkFile(name: String): Boolean = name.endsWith(".apk", ignoreCase = true)

private fun mimeOf(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return null
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
}

/**
 * 打开已完成的下载文件。
 *
 * - APK：直接交给系统安装器（ACTION_VIEW + package-archive MIME）；未授予
 *   「安装未知应用」时先跳转对应设置页，避免点击无任何反应。
 * - 其他类型：按扩展名推出 MIME，调起系统「打开方式」选择器。
 *
 * 文件地址由 [com.gh.dl.data.Fetch.Saved.uri] 提供：SAF 文档 URI 或 FileProvider URI，
 * 均通过 FLAG_GRANT_READ_URI_PERMISSION 临时授权给目标应用，不需要存储权限。
 */
@Suppress("DEPRECATION") // ACTION_INSTALL_PACKAGE 为兼容只响应旧 Action 的定制 ROM 而保留
fun openDownloadedFile(ctx: Context, job: DlJob) {
    val raw = job.whereUri
    if (raw.isBlank()) {
        toast(ctx, "该记录缺少文件位置（旧版本记录），请重新下载后再打开")
        return
    }
    val uri = try {
        Uri.parse(raw)
    } catch (t: Throwable) {
        toast(ctx, "文件位置无效，请重新下载")
        return
    }
    val apk = isApkFile(job.name)
    if (apk) {
        // 查询失败（极少数定制系统）时按已授权处理，交由系统安装器自行提示
        val allowed = try {
            ctx.packageManager.canRequestPackageInstalls()
        } catch (t: Throwable) {
            true
        }
        if (!allowed) {
            toast(ctx, "请先允许本应用安装未知来源的应用")
            launchSettings(
                ctx,
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                true,
                "未找到「安装未知应用」设置页，请手动在系统设置中授权"
            )
            return
        }
    }
    val mime = if (apk) "application/vnd.android.package-archive"
               else (mimeOf(job.name) ?: "*/*")
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (apk) {
        if (!launchExternal(ctx, view, "未找到系统安装程序")) {
            // 部分 ROM 只响应旧的 ACTION_INSTALL_PACKAGE
            val fallback = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            launchExternal(ctx, fallback, "未找到系统安装程序")
        }
    } else {
        launchExternal(ctx, Intent.createChooser(view, "打开方式"), "未找到可打开该文件的应用")
    }
}
