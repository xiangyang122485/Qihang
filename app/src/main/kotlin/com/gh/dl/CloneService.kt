package com.gh.dl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.gh.dl.data.Git
import com.gh.dl.data.fmtSize
import com.gh.dl.ui.AppState
import com.gh.dl.ui.CloneJob

/**
 * 克隆用的前台服务。
 *
 * 为什么需要它：整仓克隆动辄几十 MB、要跑十几分钟。
 * 如果只在一个普通线程里跑，用户一切到别的 App 或锁屏，系统就可能把进程回收，
 * 或者限制它的网络，结果就是「下到一半莫名其妙停了」。
 * 挂上前台服务 + 通知栏进度，系统就不会动它。
 */
class CloneService : Service() {

    companion object {
        private const val CHANNEL = "gh_dl_clone"
        private const val NOTI_ID = 4101

        @Volatile
        private var busy = false

        fun start(ctx: Context, slug: String) {
            val i = Intent(ctx, CloneService::class.java)
            i.putExtra("slug", slug)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastPost = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "仓库克隆", NotificationManager.IMPORTANCE_LOW)
        ch.description = "SSH 克隆仓库时的进度"
        nm.createNotificationChannel(ch)
    }

    private fun build(slug: String, text: String, bytes: Long): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在克隆 $slug")
            .setContentText(text + if (bytes > 0) " · 已接收 ${fmtSize(bytes)}" else "")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
        return b.build()
    }

    private fun post(slug: String, text: String, bytes: Long) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTI_ID, build(slug, text, bytes))
        } catch (e: Exception) { /* 通知失败不影响克隆 */ }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val slug = intent?.getStringExtra("slug").orEmpty()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTI_ID,
            build(slug, "准备中…", 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )

        if (slug.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (busy) return START_NOT_STICKY
        busy = true

        val app = applicationContext
        Thread {
            val ip = AppState.pickedIp.ifBlank { Git.pickIp()?.first ?: Git.IPS.first() }
            if (AppState.pickedIp.isBlank()) {
                main.post {
                    AppState.pickedIp = ip
                    AppState.ipInfo = ip
                }
            }
            val r = Git.clone(app, slug, ip) { p ->
                main.post {
                    CloneJob.stage = p.stage
                    CloneJob.bytes = p.bytes
                    CloneJob.objects = p.objects
                    CloneJob.percent = p.percent
                    CloneJob.speed = p.speed
                }
                // 通知栏最多一秒刷一次，别刷爆
                val now = System.currentTimeMillis()
                if (now - lastPost > 1000) {
                    lastPost = now
                    post(slug, p.stage, p.bytes)
                }
            }
            main.post {
                CloneJob.result = r
                CloneJob.ok = r.startsWith("克隆完成")
                CloneJob.running = false
            }
            busy = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()

        return START_NOT_STICKY
    }
}
