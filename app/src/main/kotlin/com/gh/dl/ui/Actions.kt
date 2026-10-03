package com.gh.dl.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.gh.dl.CloneService
import com.gh.dl.data.Git
import java.io.File

/** 所有异步动作都通过它把结果丢回主线程（供各 ViewModel 复用） */
internal val MAIN = Handler(Looper.getMainLooper())

fun toast(ctx: Context, t: String) {
    Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()
}

fun copyText(ctx: Context, text: String, label: String = "已复制") {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("gh", text))
    toast(ctx, label)
}

/** 递归计算目录（含子目录）字节大小 */
fun dirSize(f: File?): Long {
    if (f == null || !f.exists()) return 0L
    if (f.isFile) return f.length()
    var s = 0L
    try {
        f.walkTopDown().forEach { if (it.isFile) s += it.length() }
    } catch (e: Exception) { /* 权限不足的文件跳过 */ }
    return s
}

/**
 * 仅清理 App 缓存目录（cacheDir / externalCacheDir），不碰下载文件。
 * 返回清理前缓存字节数。
 */
fun clearAppCache(ctx: Context): Long {
    val before = dirSize(ctx.cacheDir) + dirSize(ctx.externalCacheDir)
    listOf(ctx.cacheDir, ctx.externalCacheDir).filterNotNull().distinct().forEach { it.deleteRecursively() }
    return before
}

/**
 * SSH 22 端口线路优选。结果写入全局 [AppState]（[com.gh.dl.CloneService] 也直接读它），
 * 故保留为顶层函数、不迁入 ViewModel。
 */
fun doPickLine(ctx: Context) {
    AppState.lineBusy = true
    AppState.ipInfo = "正在测速…"
    Thread {
        val r = Git.pickIp()
        MAIN.post {
            if (r == null) {
                AppState.ipInfo = "22 端口全部连不上"
            } else {
                AppState.pickedIp = r.first
                AppState.ipInfo = "${r.first} · ${r.second}ms"
            }
            AppState.lineBusy = false
        }
    }.start()
}

/**
 * 启动克隆：交给前台服务 [CloneService] 去跑，进度写全局 [CloneJob]，
 * 界面上无论切到哪个 Tab、甚至切到别的 App，都能持续下载不被系统回收。
 */
fun startClone(ctx: Context, slug: String) {
    if (CloneJob.running) {
        toast(ctx, "已经有一个克隆任务在跑了")
        return
    }
    CloneJob.running = true
    CloneJob.slug = slug
    CloneJob.stage = "正在优选 22 端口线路…"
    CloneJob.bytes = 0L
    CloneJob.objects = 0
    CloneJob.percent = -1f
    CloneJob.speed = 0L
    CloneJob.result = ""
    CloneJob.ok = false
    CloneJob.startedAt = System.currentTimeMillis()
    AppState.tab = 1
    CloneService.start(ctx.applicationContext, slug)
}
