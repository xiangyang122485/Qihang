package com.gh.dl.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 外部页面跳转。
 *
 * ROM 差异会使部分系统设置页或浏览器不存在（定制系统常移除电池优化、未知来源安装等页面），
 * 未捕获的 ActivityNotFoundException 会终止进程，表现为「点一下就闪退」。
 * 统一在此捕获并给出可执行的提示。
 */
fun launchExternal(ctx: Context, intent: Intent, failHint: String): Boolean =
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        toast(ctx, failHint)
        false
    } catch (e: SecurityException) {
        toast(ctx, failHint)
        false
    }

/** 打开系统设置页；[page] 为 Settings.ACTION_* 常量 */
fun launchSettings(ctx: Context, page: String, pkgUri: Boolean, failHint: String): Boolean {
    val i = Intent(page)
    if (pkgUri) i.data = Uri.parse("package:" + ctx.packageName)
    return launchExternal(ctx, i, failHint)
}

/** 打开浏览器链接 */
fun openUrl(ctx: Context, url: String, failHint: String = "未找到可打开链接的应用"): Boolean =
    launchExternal(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)), failHint)
