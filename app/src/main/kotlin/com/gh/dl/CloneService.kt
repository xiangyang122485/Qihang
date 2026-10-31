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
import com.gh.dl.data.Threads
import com.gh.dl.data.fmtSize
import com.gh.dl.ui.AppState
import com.gh.dl.ui.CloneJob

/**
 * 克隆用的前台服务。
 *
 * 需要此服务的原因：整仓克隆通常达数十 MB、需运行十几分钟。
 * 若仅在普通线程中运行，用户切换至其他 App 或锁屏时，系统可能回收进程，
 * 或限制其网络，导致「下载中途无故停止」。
 * 使用前台服务加通知栏进度，系统不会干预。
 */
class CloneService : Service() {

    companion object {
        private const val CHANNEL = "gh_dl_clone"
        private const val NOTI_ID = 4101

        /**
         * 「是否有克隆正在运行」。**必须使用 CAS 而非 volatile 布尔**：
         * onStartCommand 中为 check-then-set（if (busy) return; busy = true），
         * 两次连续 startForegroundService 会分派至两次 onStartCommand，
         * 中间无原子性保证，可能导致两个线程同时对同一目录执行 deleteRecursively + clone
         * ，结果为损坏的仓库（Git.clone 中的 out.deleteRecursively() 会删除对方正在写入的目录）。
         * 仅添加 @Volatile 不足：其解决可见性问题，不解决竞态问题。
         */
        private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

        fun start(ctx: Context, slug: String) {
            val i = Intent(ctx, CloneService::class.java)
            i.putExtra("slug", slug)
            // 后台启动前台服务在 Android 12+ 抛 ForegroundServiceStartNotAllowedException，
            // 降级为 startService：克隆仍执行，仅不保证锁屏后的进程优先级
            try {
                ctx.startForegroundService(i)
            } catch (t: Throwable) {
                android.util.Log.w("CloneService", "前台服务启动被拒绝，降级", t)
                try {
                    ctx.startService(i)
                } catch (_: Throwable) {
                    // 后台启动同样被拒（系统限制），克隆在界面内保持可重试状态
                }
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastPost = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "仓库克隆", NotificationManager.IMPORTANCE_LOW)
        ch.description = "SSH 克隆仓库时的进度"
        nm.createNotificationChannel(ch)
    }

    private fun build(slug: String, text: String, bytes: Long): Notification {
        // 点击通知返回 App（与下载通知一致）。克隆完成后用户通常需知晓代码落点。
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
        // 见 DownloadService 同类说明：此处抛异常会使 busy 标记无法复位，
        // 后续所有克隆请求都因抢占失败被静默丢弃
        try {
            ServiceCompat.startForeground(
                this,
                NOTI_ID,
                build(slug, "准备中", 0),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (t: Throwable) {
            android.util.Log.w("CloneService", "startForeground 失败", t)
            stopSelf()
            return START_NOT_STICKY
        }

        if (slug.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 抢占成功方可开始；抢占失败说明上次克隆尚未收尾（同目录会被写坏）
        if (!busy.compareAndSet(false, true)) return START_NOT_STICKY

        val app = applicationContext
        Threads.start("克隆") {
            // 整段使用 try/finally 包裹：Git.clone 内部为 JGit + 网络 + 磁盘，抛出异常属常态
            // （断网、仓库过大、磁盘满）。此前无任何备用方案，一旦抛出异常将导致三种情况：
            //  1) busy 始终为 true，之后**无法再进行克隆**，只能终止 App 进程；
            //  2) CloneJob.running 始终为 true，界面持续显示「克隆中」；
            //  3) 前台服务不撤销，通知栏持续显示「正在克隆」直至系统回收。
            // 而该线程无 UncaughtExceptionHandler，用户无法看到任何原因。
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
                    // 通知栏最多每秒刷新一次，避免过度刷新
                    val now = System.currentTimeMillis()
                    if (now - lastPost > 1000) {
                        lastPost = now
                        post(slug, p.stage, p.bytes)
                    }
                }
            } catch (e: Exception) {
                result = "克隆失败：" + (e.message ?: e.javaClass.simpleName)
            } finally {
                // 先释放闸门再停止服务：反之会出现短暂的「服务已停但 busy=true」窗口，阻碍下一次克隆
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
        }

        return START_NOT_STICKY
    }
}
