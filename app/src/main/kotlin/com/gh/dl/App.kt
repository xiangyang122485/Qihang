package com.gh.dl

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程级未捕获异常处理。
 *
 * 行为分两类：
 *  - 主线程：记录后交由系统默认处理器，进程正常终止（界面状态已不可信，继续运行会呈现错乱）；
 *  - 其他线程：仅记录，线程自身结束，进程继续。单次后台任务（下载、克隆、探活）失败
 *    不应导致整个应用退出，任务状态由各 ViewModel 的异常分支标记为失败。
 *
 * 记录写入 filesDir/crash/last-crash.txt，可在「设置 → 网络 → 线路诊断」查看与复制。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        val mainThread = Thread.currentThread()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                File(filesDir, CRASH_DIR).let { if (!it.exists()) it.mkdirs() }
                File(filesDir, CRASH_DIR + "/" + CRASH_FILE).writeText(format(thread.name, e))
            } catch (_: Throwable) {
                // 记录失败不阻断后续处理
            }
            if (thread === mainThread) {
                prev?.uncaughtException(thread, e)
            } else {
                Log.e(CRASH_TAG, "后台线程 ${thread.name} 异常终止（进程继续运行）", e)
            }
        }
    }

    private fun format(thread: String, e: Throwable): String {
        val sb = StringBuilder()
        sb.append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .append("  thread=").append(thread).append('\n')
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 6) {
            sb.append(cur.javaClass.name).append(": ").append(cur.message).append('\n')
            for (f in cur.stackTrace.take(20)) sb.append("    at ").append(f).append('\n')
            cur = cur.cause
            depth++
        }
        return sb.toString()
    }

    companion object {
        private const val CRASH_TAG = "gh-crash"
        private const val CRASH_DIR = "crash"
        private const val CRASH_FILE = "last-crash.txt"

        /** 最近一次未捕获异常记录；无记录时返回空串 */
        fun lastCrash(ctx: Context): Pair<String, Long> {
            val f = File(File(ctx.filesDir, CRASH_DIR), CRASH_FILE)
            if (!f.exists()) return "" to 0L
            return try {
                f.readText() to f.lastModified()
            } catch (_: Throwable) {
                "" to 0L
            }
        }

        fun clearCrash(ctx: Context): Boolean {
            val f = File(File(ctx.filesDir, CRASH_DIR), CRASH_FILE)
            return !f.exists() || f.delete()
        }
    }
}
