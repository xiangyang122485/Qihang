package com.gh.dl.data

import android.content.Context
import com.gh.dl.ui.AppState
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteSession
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机端独立的 git 克隆。
 *
 * 原理：GitHub 的 HTTPS（443）在这个网络里会被重置，但 SSH（22）是通的。
 * 所以这里用纯 Java 的 SSH 客户端（jsch）直连 GitHub 的真实 IP，
 * 再交给纯 Java 的 git 实现（JGit）走 git-upload-pack 协议取仓库。
 *
 * 全程只连 GitHub 官方服务器（github.com 的真实 IP），
 * 不经过任何第三方中转，不建隧道，不碰任何代理翻墙能力。
 */
object Git {

    /** GitHub 22 端口的真实 IP 池（写死 IP 是为了绕开被污染的 DNS） */
    val IPS = listOf(
        "140.82.112.3",
        "140.82.113.3",
        "140.82.114.4",
        "20.205.243.166"
    )

    /** 多久没有任何新数据就判定传输停滞（毫秒） */
    private const val STALL_MS = 90000L

    data class KeyInfo(val pub: String, val fingerprint: String)

    /** 克隆进度 */
    data class Progress(
        val stage: String,
        val bytes: Long,
        val objects: Int,
        val percent: Float,
        val speed: Long
    )

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "ssh")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun hasKey(ctx: Context): Boolean = SshKeys.list(ctx).isNotEmpty()

    /**
     * 当前激活钥匙的私钥/公钥字节（解密自加密库，仅本进程内）。
     * 用于 JGit 的 DirectFactory：直接把字节喂给 JSch，不落明文文件。
     */
    fun activeKeyBytes(ctx: Context): Pair<ByteArray, ByteArray>? {
        val e = SshKeys.active(ctx) ?: return null
        return SshKeys.decryptPriv(ctx, e) to e.pub.toByteArray(Charsets.UTF_8)
    }

    /**
     * 把当前激活钥匙解密后写到 App 私有临时文件（libgit2 需要文件路径）。
     * 文件在 filesDir/ssh 下，权限仅属主可读，外部 App 不可读。
     */
    fun activeKeyFile(ctx: Context): File? {
        val e = SshKeys.active(ctx) ?: return null
        val f = File(dir(ctx), "active_id_rsa")
        try {
            f.writeBytes(SshKeys.decryptPriv(ctx, e))
        } catch (ex: Exception) {
            android.util.Log.e("gh-ssh", "write active key failed", ex)
            return null
        }
        return f
    }

    /** 算 GitHub 页面上那种 SHA256:xxxx 指纹，方便加完核对 */
    fun sha256Fp(pubLine: String): String {
        return try {
            val parts = pubLine.trim().split(Regex("\\s+"))
            val blob = android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT)
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
            "SHA256:" + android.util.Base64.encodeToString(d, android.util.Base64.NO_WRAP).trimEnd('=')
        } catch (e: Exception) {
            ""
        }
    }

    /** 测每个 IP 的 22 端口，返回最快的那个 */
    fun pickIp(): Pair<String, Long>? {
        var bestIp = ""
        var bestMs = Long.MAX_VALUE
        for (ip in IPS) {
            val t0 = System.currentTimeMillis()
            var ok = false
            val s = Socket()
            try {
                s.connect(InetSocketAddress(ip, 22), 3000)
                ok = true
            } catch (e: Exception) {
                ok = false
            } finally {
                try { s.close() } catch (e: Exception) { /* ignore */ }
            }
            if (ok) {
                val ms = System.currentTimeMillis() - t0
                if (ms < bestMs) {
                    bestMs = ms
                    bestIp = ip
                }
            }
        }
        return if (bestIp.isBlank()) null else bestIp to bestMs
    }

    /** 把 jsch 的 exec 通道包装成 JGit 需要的 Process */
    private class ExecProcess(private val ch: ChannelExec) : Process() {
        override fun getOutputStream(): OutputStream = ch.outputStream
        override fun getInputStream(): InputStream = ch.inputStream
        override fun getErrorStream(): InputStream = ch.errStream
        override fun waitFor(): Int {
            while (!ch.isClosed) {
                try { Thread.sleep(60) } catch (e: InterruptedException) { break }
            }
            return ch.exitStatus
        }
        override fun exitValue(): Int =
            if (ch.isClosed) ch.exitStatus else throw IllegalThreadStateException()
        override fun destroy() {
            try { ch.disconnect() } catch (e: Exception) { /* ignore */ }
        }
    }

    private class SshRemote(private val session: Session) : RemoteSession {
        override fun exec(command: String, timeout: Int): Process {
            val ch = session.openChannel("exec") as ChannelExec
            ch.setCommand(command)
            ch.connect(timeout * 1000)
            return ExecProcess(ch)
        }

        override fun disconnect() {
            try { session.disconnect() } catch (e: Exception) { /* ignore */ }
        }
    }

    /**
     * 直连指定 IP 的 SSH 工厂（不查 DNS，所以污染对它无效）。
     * 保留 session 引用是为了能在传输停滞时主动掐断，避免 JGit 永远挂在那。
     */
    private class DirectFactory(
        private val ip: String,
        private val prv: ByteArray,
        private val pub: ByteArray
    ) : SshSessionFactory() {

        private val sessionRef = AtomicReference<Session?>(null)

        override fun getSession(
            uri: URIish,
            credentialsProvider: CredentialsProvider?,
            fs: FS?,
            tms: Int
        ): RemoteSession {
            val jsch = JSch()
            // 直接把解密后的私钥字节喂给 JSch，不在磁盘留明文文件
            jsch.addIdentity("gh-dl", prv, pub, null)
            val s = jsch.getSession("git", ip, 22)
            s.setConfig("StrictHostKeyChecking", "no")
            s.setConfig("PreferredAuthentications", "publickey")
            s.setHostKeyAlias("github.com")
            val ms = if (tms <= 0) 30000 else tms * 1000
            s.timeout = ms
            s.connect(ms)
            sessionRef.set(s)
            return SshRemote(s)
        }

        fun abort() {
            val s = sessionRef.get() ?: return
            try { s.disconnect() } catch (e: Exception) { /* ignore */ }
        }
    }

    /** 克隆目录：/sdcard/Android/data/com.gh.dl/files/git/{owner}-{repo} */
    fun targetDir(ctx: Context, slug: String): File =
        File(ctx.getExternalFilesDir(null), "git/" + slug.replace("/", "-"))

    /** 量一个目录的大小和文件数；目录正在写，异常直接忽略 */
    private fun dirSize(f: File): Pair<Long, Int> {
        var size = 0L
        var n = 0
        try {
            f.walkTopDown().forEach { x ->
                if (x.isFile) {
                    try {
                        size += x.length()
                        n++
                    } catch (e: Exception) { /* 忽略 */ }
                }
            }
        } catch (e: Exception) { /* 忽略 */ }
        return size to n
    }

    /**
     * 克隆仓库（完整历史）。
     *
     * 进度分两部分：
     *  - JGit 的 ProgressMonitor 给阶段名和百分比
     *  - 一个后台线程每 700ms 量一次 .git 目录大小，给真实的已下载字节和速度
     *
     * 另外带一个停滞看门狗：连续 90 秒没有任何新数据就主动掐断 SSH 会话，
     * 明确报错，而不是让界面永远停在「传输中」。
     *
     * 注：JGit 4.5（最后一个不依赖 java.nio.file、能在 Android 上跑的版本）
     * 不支持浅克隆，所以这里是完整历史。
     */
    fun clone(
        ctx: Context,
        slug: String,
        ip: String,
        onProgress: (Progress) -> Unit
    ): String {
        // 克隆引擎：libgit2（C 实现，HTTPS，经代理前缀触达 GitHub）或 jgit（SSH 直连 IP）
        // 克隆选项总开关关闭时，强制回退到最稳的默认方式（JGit SSH 直连）
        val effEngine = if (AppState.cloneOn) AppState.engine else "jgit"
        if (effEngine == "libgit2") return cloneLibgit2(ctx, slug, onProgress)
        val active = activeKeyBytes(ctx)
            ?: return "还没有 SSH 钥匙，请先到「设置 → SSH 密钥」生成并加到 GitHub"
        val out = targetDir(ctx, slug)
        if (out.exists()) out.deleteRecursively()
        out.parentFile?.mkdirs()

        val factory = DirectFactory(ip, active.first, active.second)
        SshSessionFactory.setInstance(factory)

        val stage = AtomicReference("连接 $ip:22 …")
        val acc = AtomicInteger(0)
        val totalWork = AtomicInteger(0)
        val stopped = AtomicBoolean(false)
        val stalled = AtomicBoolean(false)
        val t0 = System.currentTimeMillis()
        val sub = File(out, ".git")

        val watcher = Thread {
            var lastB = 0L
            var lastT = t0
            var lastGrow = t0
            while (!stopped.get()) {
                val (bytes, n) = dirSize(sub)
                val now = System.currentTimeMillis()
                val dt = (now - lastT).coerceAtLeast(1)
                val grew = bytes > lastB
                val speed = (bytes - lastB) * 1000 / dt
                lastB = bytes
                lastT = now
                if (grew) lastGrow = now

                val tw = totalWork.get()
                val pct = if (tw > 0) (acc.get().toFloat() / tw).coerceIn(0f, 0.99f) else -1f
                onProgress(Progress(stage.get(), bytes, n, pct, if (speed > 0) speed else 0))

                // 已经收到数据了、却长时间一动不动 → 判定停滞并掐断
                if (bytes > 0L && now - lastGrow > STALL_MS) {
                    stalled.set(true)
                    stage.set("传输停滞，正在断开…")
                    factory.abort()
                    break
                }
                try {
                    Thread.sleep(700)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        watcher.isDaemon = true
        watcher.start()

        val mon = object : ProgressMonitor {
            override fun start(totalTasks: Int) { /* ignore */ }
            override fun beginTask(title: String, total: Int) {
                if (title.isNotBlank()) stage.set(title)
                if (total > 0) {
                    totalWork.set(total)
                    acc.set(0)
                }
            }
            override fun update(completed: Int) {
                acc.addAndGet(completed)
            }
            override fun endTask() { /* ignore */ }
            override fun isCancelled(): Boolean = false
        }

        return try {
            onProgress(Progress(stage.get(), 0, 0, -1f, 0))
            val cmd = Git.cloneRepository()
                .setURI("git@github.com:$slug.git")
                .setDirectory(out)
                .setProgressMonitor(mon)
            cmd.setTimeout(60)
            val git = cmd.call()

            val head = try {
                git.repository.getRef("HEAD")?.objectId?.name() ?: ""
            } catch (e: Exception) {
                ""
            }
            git.close()

            // Git LFS：JGit 只拿到指针文件，这里拉真实大文件替换掉
            var lfsMsg = ""
            if (AppState.lfsOn && AppState.cloneOn) {
                val r = Lfs.resolve(slug, out) { s, _, _ ->
                    onProgress(Progress(s, 0, 0, -1f, 0))
                }
                lfsMsg = r.msg
            }

            val (size, files) = dirSize(out)
            onProgress(Progress("克隆完成", size, files, 1f, 0))

            // 用户授权了共享文件夹就复制过去
            var where = "应用私有目录：\n${out.absolutePath}"
            if (Store.isSet(ctx)) {
                val dest = Store.subDir(ctx, out.name)
                if (dest != null) {
                    onProgress(Progress("正在复制到共享文件夹…", size, files, 1f, 0))
                    Store.copyDir(ctx, out, dest) { p ->
                        onProgress(Progress(p, size, files, 1f, 0))
                    }
                    where = "共享文件夹：${Store.displayName(ctx)}/${out.name}"
                }
            }

            val mode = "完整历史"
            "克隆完成（$mode）\n位置：$where\n大小：${fmtSize(size)} · $files 个文件\nHEAD：${head.take(8)}" +
                if (lfsMsg.isNotBlank()) "\n$lfsMsg" else ""
        } catch (e: Exception) {
            if (out.exists()) out.deleteRecursively()
            val secs = (System.currentTimeMillis() - t0) / 1000
            if (stalled.get()) {
                "传输停滞（网络中断）：${secs}s 内没有再收到数据，已主动断开。\n" +
                    "这是网络到 GitHub 22 端口的连接被中途掐了。\n" +
                    "建议：① 勾选「只取最新一次提交」再试，数据量小很多；② 换 Wi-Fi / 换个时段再试。"
            } else {
                "克隆失败（用了 ${secs}s）：${e.message ?: e.javaClass.simpleName}"
            }
        } finally {
            stopped.set(true)
            try { factory.abort() } catch (e: Exception) { /* ignore */ }
        }
    }

    /**
     * 用内置的 libgit2（C 实现）克隆源码。
     * 走 HTTPS（smart-HTTP），URL 由 Git2 套加速代理前缀——这样在手机上才能经公益镜像
     * 触达 GitHub（设备直连 github.com 的 443 被重置）。公开仓库匿名克隆，绝不带 Token。
     */
    private fun cloneLibgit2(
        ctx: Context,
        slug: String,
        onProgress: (Progress) -> Unit
    ): String {
        val out = targetDir(ctx, slug)
        if (out.exists()) out.deleteRecursively()
        out.parentFile?.mkdirs()

        val ssh = AppState.sshChannel == "ssh" && hasKey(ctx) && AppState.cloneOn
        val keyPath = if (ssh) activeKeyFile(ctx)?.absolutePath ?: "" else ""
        onProgress(Progress(if (ssh) "libgit2：正在克隆（SSH 22 端口）…" else "libgit2：正在克隆（HTTPS）…", 0, 0, -1f, 0))
        val r = Git2.clone(slug, out, keyPath, ssh)
        if (!r.startsWith("OK:")) {
            if (out.exists()) out.deleteRecursively()
            val tip = when {
                ssh -> "\n提示：SSH 通道走 22 端口直连 GitHub 官方真实 IP，需先确认 SSH 公私钥已生成且公钥已加到 GitHub。"
                !AppState.accelOn -> "\n提示：libgit2 走 HTTPS，手机上需开启「加速代理」才能经镜像触达 GitHub（直连 443 被重置）。"
                else -> ""
            }
            return "libgit2 克隆失败：$r$tip"
        }
        val head = r.removePrefix("OK:")

        // Git LFS：libgit2 同样只拿到指针，拉真实大文件替换
        var lfsMsg = ""
        if (AppState.lfsOn && AppState.cloneOn) {
            val lr = Lfs.resolve(slug, out) { s, _, _ -> onProgress(Progress(s, 0, 0, -1f, 0)) }
            lfsMsg = lr.msg
        }

        val (size, files) = dirSize(out)
        onProgress(Progress("克隆完成", size, files, 1f, 0))

        var where = "应用私有目录：\n${out.absolutePath}"
        if (Store.isSet(ctx)) {
            val dest = Store.subDir(ctx, out.name)
            if (dest != null) {
                onProgress(Progress("正在复制到共享文件夹…", size, files, 1f, 0))
                Store.copyDir(ctx, out, dest) { p -> onProgress(Progress(p, size, files, 1f, 0)) }
                where = "共享文件夹：${Store.displayName(ctx)}/${out.name}"
            }
        }

        return "克隆完成（libgit2）\n位置：$where\n大小：${fmtSize(size)} · $files 个文件\nHEAD：${head.take(8)}" +
            if (lfsMsg.isNotBlank()) "\n$lfsMsg" else ""
    }
}
