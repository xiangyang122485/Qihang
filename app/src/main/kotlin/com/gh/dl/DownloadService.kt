package com.gh.dl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.gh.dl.data.Net
import com.gh.dl.data.fmtSize
import com.gh.dl.ui.AppState

/**
 * 文件下载的前台服务。
 *
 * 为什么要它：切后台 / 锁屏 / 息屏时，纯后台线程下载会被系统限流甚至回收。
 * 挂上前台服务 + 通知栏进度 + PARTIAL_WAKE_LOCK，下载就能在后台跑完，
 * 跑完自动释放资源回到完全后台，不常驻。
 *
 * 实际下载逻辑仍由 [com.gh.dl.ui.DownloadsViewModel] 的线程执行（保持现有引擎不变），
 * 本服务只负责「维持前台存在 + 唤醒锁 + 进度通知」，通过读取 [AppState] 的下载状态字段刷新通知。
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL = "gh_dl_download"
        private const val NOTI_ID = 4201
        private const val WAKE_TAG = "GitHubDownloader::DownloadWakeLock"

        /** 一个下载任务开始：计数 +1，必要时拉起前台服务 */
        fun begin(ctx: Context, name: String) {
            AppState.activeDownloads++
            AppState.dlNotiName = name
            lastFinishSuppressed = false
            if (!running) {
                val i = Intent(ctx, DownloadService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }

        /**
         * 一个下载任务结束：计数 -1；归零由服务内部自动收尾（降级通知 + 停止）。
         * @param suppressDone 因「暂停」而结束时不弹「下载完成」通知（true），真正完成/失败为 false。
         *   只有最后一个任务把 [AppState.activeDownloads] 减到 0 时，本值才决定最终是否弹完成通知，
         *   避免并发批次里前面的暂停把后续真正完成的通知误抑制。
         */
        fun end(ctx: Context, ok: Boolean, name: String, suppressDone: Boolean = false) {
            AppState.dlNotiOk = ok
            AppState.dlNotiName = name
            val remaining = (AppState.activeDownloads - 1).coerceAtLeast(0)
            AppState.activeDownloads = remaining
            if (remaining == 0) lastFinishSuppressed = suppressDone
        }

        /** 最近一次把 activeDownloads 减到 0 的结束是否「暂停」（暂停则不弹完成通知） */
        @Volatile
        internal var lastFinishSuppressed = false

        @Volatile
        internal var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val ticker = object : Runnable {
        override fun run() {
            refreshNotification()
            // 没有任何下载在跑 → 收尾：降级为可删通知 + 停止前台 + 释放唤醒锁 + 停止服务
            if (AppState.activeDownloads <= 0) {
                finish()
                return
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "下载中", NotificationManager.IMPORTANCE_LOW)
        ch.description = "文件下载进度"
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val name = AppState.dlNotiName.ifBlank { "下载中" }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载 $name")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (AppState.dlNotiTotal > 0) {
            val pct = (AppState.dlNotiBytes * 100 / AppState.dlNotiTotal).toInt().coerceIn(0, 100)
            b.setContentText("${fmtSize(AppState.dlNotiBytes)} / ${fmtSize(AppState.dlNotiTotal)} · $pct%")
                .setProgress(100, pct, false)
        } else {
            b.setContentText("${fmtSize(AppState.dlNotiBytes)} · 下载中…").setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun refreshNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (AppState.activeDownloads > 0) nm.notify(NOTI_ID, buildNotification())
        } catch (e: Exception) { /* 通知失败不影响下载 */ }
    }

    private fun finish() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!lastFinishSuppressed) {
                val name = AppState.dlNotiName.ifBlank { "文件" }
                val done = NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(if (AppState.dlNotiOk) "下载完成 $name" else "下载失败 $name")
                    .setContentText(if (AppState.dlNotiOk) "已保存到下载位置" else "可重试或检查网络")
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build()
                // 用与进行中通知相同的 id 原地替换（避免同时出现两条通知），随后 DETACH 让它可被划掉
                nm.notify(NOTI_ID, done)
            }
        } catch (e: Exception) { /* ignore */ }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        releaseWake()
        running = false
        stopSelf()
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire(6L * 60 * 60 * 1000) // 最长 6 小时，足够任何单次下载；服务停止时会释放
        }
    }

    private fun releaseWake() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { /* ignore */ }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        acquireWake()
        ServiceCompat.startForeground(
            this, NOTI_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        // 网络切换（WiFi ↔ 移动数据）不中断：监听默认网络，变化时重建 OkHttp（断点续传由引擎负责接着下）
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            netCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { Net.rebuild() }
            }
            cm.registerNetworkCallback(req, netCallback!!)
        } catch (e: Exception) { /* 低版本不支持回调也不影响下载 */ }
        running = true
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 1000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            netCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (e: Exception) { /* ignore */ }
        netCallback = null
        releaseWake()
        running = false
        super.onDestroy()
    }
}
