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
 * 需要此服务的原因：切换至后台 / 锁屏 / 息屏时，纯后台线程下载会被系统限流甚至回收。
 * 使用前台服务加通知栏进度加 PARTIAL_WAKE_LOCK，下载可在后台完成，
 * 完成后自动释放资源回到完全后台，不常驻。
 *
 * 实际下载逻辑仍由 [com.gh.dl.ui.DownloadsViewModel] 的线程执行（保持现有引擎不变），
 * 本服务仅负责「维持前台存在 + 唤醒锁 + 进度通知」，通过读取 [AppState] 的下载状态字段刷新通知。
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL = "gh_dl_download"
        private const val NOTI_ID = 4201
        private const val WAKE_TAG = "GitHubDownloader::DownloadWakeLock"

        /**
         * 一个下载任务开始：计数加 1，必要时启动前台服务。
         * 计数与启动判定必须在同一把锁内：多个下载线程并发调用时，
         * ++ 与 volatile 布尔只解决可见性，既不原子判重也会丢失计数
         * （归零判定失效，前台通知常驻不消失）。
         */
        @Synchronized
        fun begin(ctx: Context, name: String) {
            AppState.activeDownloads++
            AppState.dlNotiName = name
            lastFinishSuppressed = false
            if (!running) startSafely(ctx)
        }

        /**
         * Android 12 起应用处于后台时启动前台服务会抛 ForegroundServiceStartNotAllowedException
         * （dataSync 类型无后台豁免）。队列在后台继续派发任务时必然命中该限制，
         * 因此降级为普通 startService；仍失败则仅记录，由下载线程继续执行。
         */
        private fun startSafely(ctx: Context) {
            val i = Intent(ctx, DownloadService::class.java)
            try {
                ctx.startForegroundService(i)
            } catch (t: Throwable) {
                android.util.Log.w("DownloadService", "前台服务启动被拒绝，降级", t)
                try {
                    ctx.startService(i)
                } catch (_: Throwable) {
                    // 后台启动同样被拒（系统限制），下载进度改由界面刷新
                }
            }
        }

        /**
         * 一个下载任务结束：计数减 1；归零时由服务内部自动收尾（降级通知 + 停止）。
         * @param suppressDone 因「暂停」而结束时不弹出「下载完成」通知（true），真正完成或失败时为 false。
         *   仅当最后一个任务将 [AppState.activeDownloads] 减至 0 时，本值才决定最终是否弹出完成通知，
         *   避免并发批次中前面的暂停将后续真正完成的通知误抑制。
         */
        @Synchronized
        fun end(ctx: Context, ok: Boolean, name: String, suppressDone: Boolean = false) {
            AppState.dlNotiOk = ok
            AppState.dlNotiName = name
            val remaining = (AppState.activeDownloads - 1).coerceAtLeast(0)
            AppState.activeDownloads = remaining
            if (remaining == 0) lastFinishSuppressed = suppressDone
        }

        /** 最近一次将 activeDownloads 减至 0 的结束是否为「暂停」（暂停则不弹出完成通知） */
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
            // 无下载任务运行 → 收尾：降级为可删除通知 + 停止前台 + 释放唤醒锁 + 停止服务
            if (AppState.activeDownloads <= 0) {
                finish()
                return
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "下载中", NotificationManager.IMPORTANCE_LOW)
        ch.description = "文件下载进度"
        nm.createNotificationChannel(ch)
    }

    /**
     * 点击通知返回 App。前台服务通知原本不可点击，用户从通知栏无法看到「下载完成后去向」；
     * 下载 APK 时，完成通知更是唯一的入口提示。
     * FLAG_IMMUTABLE：targetSdk 31+ 必须显式声明可变性，否则 PendingIntent 直接抛出异常。
     */
    private fun contentIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(): Notification {
        val name = AppState.dlNotiName.ifBlank { "下载中" }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent())
            .setContentTitle("正在下载 $name")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (AppState.dlNotiTotal > 0) {
            val pct = (AppState.dlNotiBytes * 100 / AppState.dlNotiTotal).toInt().coerceIn(0, 100)
            b.setContentText("${fmtSize(AppState.dlNotiBytes)} / ${fmtSize(AppState.dlNotiTotal)} · $pct%")
                .setProgress(100, pct, false)
        } else {
            b.setContentText("${fmtSize(AppState.dlNotiBytes)} · 下载中").setProgress(0, 0, true)
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
                    .setContentIntent(contentIntent())
                    .setContentTitle(if (AppState.dlNotiOk) "下载完成 $name" else "下载失败 $name")
                    .setContentText(if (AppState.dlNotiOk) "已保存至下载位置" else "可重试，或检查网络连接")
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build()
                // 使用与进行中通知相同的 id 原地替换（避免同时出现两条通知），随后 DETACH 使其可被划掉
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
            acquire(6L * 60 * 60 * 1000) // 最长 6 小时，足以覆盖任何单次下载；服务停止时释放
        }
    }

    private fun releaseWake() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { /* ignore */ }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        acquireWake()
        // 通知渠道被用户关闭、前台服务配额受限或应用处于后台时，startForeground 会抛异常。
        // 未捕获会中断 onStartCommand，使 running 停留 false 而通知不消失；
        // 捕获后主动返回，下载本身由 ViewModel 线程继续执行（仅失去前台优先级与唤醒锁）。
        try {
            ServiceCompat.startForeground(
                this, NOTI_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (t: Throwable) {
            android.util.Log.w("DownloadService", "startForeground 失败", t)
            releaseWake()
            return START_NOT_STICKY
        }
        // 网络切换（WiFi ↔ 移动数据）不中断：监听默认网络，变化时重建 OkHttp（断点续传由引擎负责继续）
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // 先丢弃优选结果再重建客户端，否则 DNS 回调仍会命中上一张网络的失效 IP
                    Net.clearPins()
                    Net.rebuild()
                }

                override fun onLost(network: Network) {
                    Net.clearPins()
                }
            }
            netCallback = cb
            cm.registerNetworkCallback(req, cb)
        } catch (e: Exception) { /* 低版本不支持回调亦不影响下载 */ }
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
