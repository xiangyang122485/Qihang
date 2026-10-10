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

        /**
         * 「有没有克隆在跑」。**必须是 CAS 而不是 volatile 布尔**：
         * onStartCommand 里是 check-then-set（if (busy) return; busy = true），
         * 两次连续 startForegroundService 会分派到两次 onStartCommand，
         * 中间没有原子性保证，于是两个线程同时对同一个目录做 deleteRecursively + clone
         * ——结果是一个坏仓库（Git.clone 里那步 out.deleteRecursively() 会删掉对方正在写的目录）。
         * 原来只加 @Volatile 是不够的：那解决可见性，不解决竞态。
         */
        private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

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
        // 点通知回到 App（与下载通知一致）。克隆完通常用户想知道代码落到哪了。
        val pi = android.app.PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
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
        // 抢占成功才开始；抢不到说明上一次克隆还没收尾（同目录会被写坏）
        if (!busy.compareAndSet(false, true)) return START_NOT_STICKY

        val app = applicationContext
        Thread {
            // 整段包 try/finally：Git.clone 里面是 JGit + 网络 + 磁盘，抛异常是常态
            // （断网、仓库太大、磁盘满）。原来没有任何兜底，一抛就发生三件事：
            //  1) busy 永远是 true —— 之后**再也克隆不动**，只能杀掉 App 进程；
            //  2) CloneJob.running 永远是 true —— 界面一直转圈说「克隆中」；
            //  3) 前台服务不撤 —— 通知栏挂着「正在克隆」直到系统回收。
            // 而这条线程没有 UncaughtExceptionHandler，用户看不到任何原因。
            var result = ""
            try {
                val ip = AppState.pickedIp.ifBlank { Git.pickIp()?.first ?: Git.IPS.first() }
                if (AppState.pickedIp.isBlank()) {
                    main.post {
                        AppState.pickedIp = ip
                        AppState.ipInfo = ip
                    }
                }
                result = Git.clone(app, slug, ip) { p ->
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
            } catch (e: Exception) {
                result = "克隆失败：" + (e.message ?: e.javaClass.simpleName)
            } finally {
                // 先释放闸门再停服：反过来会有一小段「服务已停但 busy=true」的窗口挡住下一次克隆
                busy.set(false)
                val r = result
                main.post {
                    CloneJob.result = r
                    CloneJob.ok = r.startsWith("克隆完成")
                    CloneJob.running = false
                }
                runCatching {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }
}
