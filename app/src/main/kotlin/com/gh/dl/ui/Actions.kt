package com.gh.dl.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import com.gh.dl.CloneService
import com.gh.dl.data.Git
import com.gh.dl.data.Threads
import java.io.File

/** 所有异步动作通过此 Handler 将结果回传主线程（供各 ViewModel 复用） */
internal val MAIN = Handler(Looper.getMainLooper())

fun toast(ctx: Context, t: String) {
    Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()
}

/**
 * 写入剪贴板并读回校验。
 *
 * 原实现写入后无条件提示「已复制」：部分 ROM 的隐私开关、输入法接管或无焦点窗口
 * 会丢弃写入，用户看到成功提示、粘贴时却是旧内容。此处读回比对，失败时
 * 弹出可选择文本的对话框作为兜底，不再给出虚假成功提示。
 *
 * @return 是否确认写入成功
 */
fun copyText(ctx: Context, text: String, label: String = "已复制"): Boolean {
    if (text.isBlank()) {
        toast(ctx, "内容为空，未复制")
        return false
    }
    val cm = try {
        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    } catch (t: Throwable) {
        null
    }
    if (cm == null) {
        toast(ctx, "系统剪贴板不可用")
        return false
    }
    val ok = try {
        cm.setPrimaryClip(ClipData.newPlainText("GitHub 下载器", text))
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() == text
    } catch (t: Throwable) {
        false
    }
    if (ok) {
        toast(ctx, label)
        return true
    }
    showCopyFallback(ctx, text, label)
    return false
}

/** 剪贴板写入失败时的兜底：显示可选文本，由用户手动复制 */
private fun showCopyFallback(ctx: Context, text: String, label: String) {
    try {
        val tv = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setPadding(48, 24, 48, 24)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(ctx)
            .setTitle("剪贴板不可用")
            .setMessage("系统未接受本次复制，请长按下方文本手动选择并复制。")
            .setView(tv)
            .setPositiveButton("重试", { _, _ -> copyText(ctx, text, label) })
            .setNegativeButton("关闭", null)
            .show()
    } catch (t: Throwable) {
        toast(ctx, "无法写入剪贴板，请手动复制")
    }
}

/**
 * 后台获取一个值、回到主线程显示结果（必须在主线程调用）。
 *
 * 存在原因：此类「点击 → 联网计算 → 复制/提示」的操作均使用裸 Thread 执行，
 * 一旦抛出异常（断网、镜像站 500、校验文件缺失），线程直接终止，
 * 用户看到的是「点击无响应」——既无失败提示，也无日志记录。
 * 通过 runCatching 捕获异常后统一回到主线程提示，确保失败信息可见。
 * 仅在主线程 post 结果，不写入任何 Compose 状态，因此调用方可在任意线程。
 */
fun verifying(ctx: Context, what: String, label: String = what + "已复制", block: () -> String?) {
    Threads.start("获取直链") {
        val v = runCatching(block).getOrNull()
        MAIN.post {
            when {
                v != null -> copyText(ctx, v, label)
                else -> toast(ctx, "无法获取$what：该文件可能未提供，或网络连接异常")
            }
        }
    }
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
 * 仅清理应用缓存目录（cacheDir / externalCacheDir），不涉及下载文件。
 * 返回清理前的缓存字节数。
 */
fun clearAppCache(ctx: Context): Long {
    val before = dirSize(ctx.cacheDir) + dirSize(ctx.externalCacheDir)
    listOf(ctx.cacheDir, ctx.externalCacheDir).filterNotNull().distinct().forEach { it.deleteRecursively() }
    return before
}

/**
 * SSH 22 端口线路优选。结果写入全局 [AppState]（[com.gh.dl.CloneService] 也直接读取），
 * 因此保留为顶层函数、不迁入 ViewModel。
 */
fun doPickLine(ctx: Context) {
    AppState.lineBusy = true
    AppState.ipInfo = "正在测速…"
    Threads.start("线路检测") {
        // pickIp 内部逐 IP 试连，仍可能因线程池/权限异常抛出；
        // 使用 runCatching 保证 lineBusy 必然复位，否则界面永久停在「正在测速…」
        val r = runCatching { Git.pickIp() }.getOrNull()
        MAIN.post {
            if (r == null) {
                AppState.ipInfo = "22 端口全部无法连接"
            } else {
                AppState.pickedIp = r.first
                AppState.ipInfo = "${r.first} · ${r.second}ms"
            }
            AppState.lineBusy = false
        }
    }
}

/**
 * 启动克隆：交由前台服务 [CloneService] 执行，进度写入全局 [CloneJob]，
 * 界面中无论切换到哪个 Tab、甚至切换到其他应用，均可持续下载而不被系统回收。
 */
fun startClone(ctx: Context, slug: String) {
    if (CloneJob.running) {
        toast(ctx, "已有一个克隆任务正在执行")
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
    AppState.tab = AppState.TAB_DOWNLOADS
    CloneService.start(ctx.applicationContext, slug)
}
