package com.gh.dl.data

import android.util.Log

/**
 * 后台线程统一入口。
 *
 * 裸线程中逃逸的异常会终止整个进程，用户侧表现为无提示的「闪退」。
 * 这里集中捕获并记录，使单次后台任务失败不影响应用其余功能；
 * 线程命名为 gh-{tag}，便于在异常记录与日志中定位来源。
 */
object Threads {

    /** 创建带异常保护的后台线程，交由调用方决定启动时机 */
    fun new(tag: String, body: () -> Unit): Thread =
        Thread {
            try {
                body()
            } catch (_: InterruptedException) {
                // 取消或停止：正常退出，不计为异常
            } catch (t: Throwable) {
                Log.e("gh-thread", "后台任务 $tag 异常终止", t)
            }
        }.apply {
            isDaemon = true
            name = "gh-$tag"
        }

    /** 创建并立即启动 */
    fun start(tag: String, body: () -> Unit) {
        new(tag, body).start()
    }
}
