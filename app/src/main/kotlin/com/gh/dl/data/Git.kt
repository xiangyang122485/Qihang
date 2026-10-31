package com.gh.dl.data

import android.content.Context
import com.gh.dl.ui.AppState
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteSession
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import java.io.ByteArrayInputStream
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
 * 原理：GitHub 的 HTTPS（443）在此网络中会被重置，但 SSH（22）可正常连接。
 * 因此使用纯 Java 的 SSH 客户端（jsch）直连 GitHub 的真实 IP，
 * 再交由纯 Java 的 git 实现（JGit）通过 git-upload-pack 协议获取仓库。
 *
 * 全程仅连接 GitHub 官方服务器（github.com 的真实 IP），
 * 不经任何第三方中转，不建立隧道，不涉及代理工具的网络穿透能力。
 */
object Git {

    /** GitHub 22 端口的真实 IP 池（写死 IP 是为了绕开被污染的 DNS） */
    val IPS = listOf(
        "140.82.112.3",
        "140.82.113.3",
        "140.82.114.4",
        "20.205.243.166"
    )

    /** 多久未收到任何新数据即判定传输停滞（毫秒） */
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
     * 当前激活密钥的私钥/公钥字节（解密自加密库，仅限本进程内）。
     * 用于 JGit 的 DirectFactory：直接将字节提供给 JSch，不落盘明文文件。
     */
    fun activeKeyBytes(ctx: Context): KeyBundle? {
        val e = SshKeys.active(ctx) ?: return null
        return KeyBundle(
            prv = SshKeys.decryptPriv(ctx, e),
            pub = e.pub.toByteArray(Charsets.UTF_8),
            pass = SshKeys.decryptPass(ctx, e)
        )
    }

    /** SSH 认证所需材料：私钥、公钥行、可选口令（无口令时为 null） */
    data class KeyBundle(val prv: ByteArray, val pub: ByteArray, val pass: ByteArray?)

    /**
     * 将当前激活密钥解密后写入 App 私有临时文件（libgit2 需要文件路径）。
     * 文件位于 filesDir/ssh 下，权限仅限属主读取，外部 App 不可读。
     *
     * 说明：此为明文私钥，用完后必须立即调用 [wipeActiveKeyFile]。写入文件仅因 libgit2
     * 的回调仅接受路径，而 JGit 路径（[activeKeyBytes]）为直接提供字节。
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

    /**
     * 删除 libgit2 使用后的明文私钥临时文件（先覆零再删除，避免删除后仍可从磁盘残留恢复）。
     */
    fun wipeActiveKeyFile(ctx: Context) {
        val f = File(dir(ctx), "active_id_rsa")
        if (!f.exists()) return
        try {
            val raf = java.io.RandomAccessFile(f, "rw")
            raf.use { it.seek(0); it.write(ByteArray(it.length().toInt())); it.setLength(0) }
        } catch (_: Exception) { /* 覆零失败仍需继续删除 */ }
        f.delete()
    }

    private val WS_SPLIT = Regex("\\s+")

    /** 计算 GitHub 页面上的 SHA256:xxxx 指纹，便于添加后核对 */
    fun sha256Fp(pubLine: String): String {
        return try {
            val parts = pubLine.trim().split(WS_SPLIT)
            val blob = android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT)
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
            "SHA256:" + android.util.Base64.encodeToString(d, android.util.Base64.NO_WRAP).trimEnd('=')
        } catch (e: Exception) {
            ""
        }
    }

    /** 测试每个 IP 的 22 端口，返回延迟最低者 */
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

    /** 将 jsch 的 exec 通道包装为 JGit 所需的 Process */
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
     * 直连指定 IP 的 SSH 工厂（不查询 DNS，因此污染对其无效）。
     * 保留 session 引用是为了在传输停滞时能够主动断开，避免 JGit 无限挂起。
     */
    private class DirectFactory(
        private val ip: String,
        private val prv: ByteArray,
        private val pub: ByteArray,
        private val pass: ByteArray?
    ) : SshSessionFactory() {

        private val sessionRef = AtomicReference<Session?>(null)

        override fun getSession(
            uri: URIish,
            credentialsProvider: CredentialsProvider?,
            fs: FS?,
            tms: Int
        ): RemoteSession {
            val jsch = JSch()
            // 私钥字节直接提供给 JSch，不在磁盘保留明文文件
            jsch.addIdentity("gh-dl", prv, pub, pass)
            // 内置 GitHub 官方主机公钥并强制校验：连接目标取自可被污染的解析结果，
            // 不校验即可能被指向非 GitHub 主机
            jsch.setKnownHosts(
                ByteArrayInputStream(GhHostKeys.KNOWN_HOSTS.toByteArray(Charsets.UTF_8))
            )
            val s = jsch.getSession("git", ip, 22)
            s.setConfig("StrictHostKeyChecking", "yes")
            s.setConfig("PreferredAuthentications", "publickey")
            s.setHostKeyAlias("github.com")
            val ms = if (tms <= 0) 30000 else tms * 1000
            s.timeout = ms
            try {
                s.connect(ms)
            } catch (e: JSchException) {
                val m = e.message ?: ""
                val rejected = m.contains("HostKey", true) ||
                    m.contains("host key", true) ||
                    m.contains("fingerprint", true)
                try { s.disconnect() } catch (_: Exception) { /* 连接未建立或已断开 */ }
                if (rejected) {
                    val err = JSchException(
                        "主机密钥校验未通过：" + ip + " 呈现的主机密钥与内置 GitHub 官方公钥不一致。" +
                            "若 GitHub 已轮换主机密钥请升级应用，否则该 IP 可能已被污染替换。"
                    )
                    err.initCause(e)
                    throw err
                }
                throw e
            }
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
        Dirs.external(ctx, "git/" + slug.replace("/", "-"))

    /** 测量目录的大小和文件数；目录正在写入时，异常直接忽略 */
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
     *  - JGit 的 ProgressMonitor 提供阶段名和百分比
     *  - 后台线程每 700ms 测量一次 .git 目录大小，提供真实的已下载字节和速度
     *
     * 另外配置停滞看门狗：连续 90 秒未收到任何新数据即主动断开 SSH 会话，
     * 明确报错，而非让界面永远停留在「传输中」。
     *
     * 说明：JGit 4.5（最后一个不依赖 java.nio.file、可在 Android 上运行的版本）
     * 不支持浅克隆，因此此处为完整历史。
     */
    fun clone(
        ctx: Context,
        slug: String,
        ip: String,
        onProgress: (Progress) -> Unit
    ): String {
        // 克隆引擎：libgit2（C 实现，HTTPS，经代理前缀触达 GitHub）或 jgit（SSH 直连 IP）
        // 克隆选项总开关关闭时，强制回退至最稳定的默认方式（JGit SSH 直连）
        val effEngine = if (AppState.cloneOn) AppState.engine else "jgit"
        if (effEngine == "libgit2") return cloneLibgit2(ctx, slug, onProgress)
        val active = activeKeyBytes(ctx)
            ?: return "尚无 SSH 密钥，请先前往「设置 → 克隆 → SSH 密钥管理」生成并添加至 GitHub"
        val out = targetDir(ctx, slug)
        if (out.exists()) out.deleteRecursively()
        out.parentFile?.mkdirs()

        val factory = DirectFactory(ip, active.prv, active.pub, active.pass)
        SshSessionFactory.setInstance(factory)

        val stage = AtomicReference("连接 $ip:22")
        val acc = AtomicInteger(0)
        val totalWork = AtomicInteger(0)
        val stopped = AtomicBoolean(false)
        val stalled = AtomicBoolean(false)
        val t0 = System.currentTimeMillis()
        val sub = File(out, ".git")

        val watcher = Threads.new("克隆看门狗") {
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
                    stage.set("传输停滞，正在断开")
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

            // Git LFS：JGit 仅获取指针文件，此处拉取真实大文件进行替换
            var lfsMsg = ""
            if (AppState.lfsOn && AppState.cloneOn) {
                val r = Lfs.resolve(slug, out) { s, _, _ ->
                    onProgress(Progress(s, 0, 0, -1f, 0))
                }
                lfsMsg = r.msg
            }

            val (size, files) = dirSize(out)
            onProgress(Progress("克隆完成", size, files, 1f, 0))

            // 用户已授权共享文件夹时进行复制
            var where = "应用私有目录：\n${out.absolutePath}"
            if (Store.isSet(ctx)) {
                val dest = Store.subDir(ctx, out.name)
                if (dest != null) {
                    onProgress(Progress("正在复制到共享文件夹", size, files, 1f, 0))
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
                "传输停滞：${secs}s 内未收到数据，已主动断开（到 GitHub 22 端口的连接被中途切断）。\n" +
                    // 克隆始终取完整历史，界面不存在浅克隆选项，建议项必须是可执行的
                    "可执行操作：① 仅需源码时改用解析器页「源码包（zip）」下载；② 更换网络后重试；③ 关闭 LFS 拉取。"
            } else {
                "克隆失败（耗时 ${secs}s）：${e.message ?: e.javaClass.simpleName}"
            }
        } finally {
            stopped.set(true)
            try { factory.abort() } catch (e: Exception) { /* ignore */ }
        }
    }

    /**
     * 使用内置的 libgit2（C 实现）克隆源码。
     * 走 HTTPS（smart-HTTP），URL 由 Git2 套用加速代理前缀，如此在手机上才能经公益镜像
     * 触达 GitHub（设备直连 github.com 的 443 被重置）。公开仓库匿名克隆，不携带 Token。
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
        if (ssh && SshKeys.active(ctx)?.hasPass == true)
            return "libgit2 引擎不支持带口令的私钥，请改用 JGit 引擎或导入无口令私钥"
        val keyFile = if (ssh) activeKeyFile(ctx) else null
        val keyPath = keyFile?.absolutePath ?: ""
        onProgress(Progress(if (ssh) "libgit2：正在克隆（SSH 22 端口）" else "libgit2：正在克隆（HTTPS）", 0, 0, -1f, 0))
        val r = try {
            Git2.clone(slug, out, keyPath, ssh)
        } finally {
            // 克隆结束后（无论成功、失败或抛出异常）立即清除明文私钥，避免其常驻磁盘
            if (keyFile != null) wipeActiveKeyFile(ctx)
        }
        if (!r.startsWith("OK:")) {
            if (out.exists()) out.deleteRecursively()
            val tip = when {
                ssh -> "\nSSH 通道经 22 端口直连 GitHub 官方 IP，需已生成密钥并将公钥添加至 GitHub。"
                !AppState.accelOn -> "\nlibgit2 走 HTTPS，需开启下载加速前缀（443 在部分网络被重置）。"
                else -> ""
            }
            return "libgit2 克隆失败：$r$tip"
        }
        val head = r.removePrefix("OK:")

        // Git LFS：libgit2 同样仅获取指针，拉取真实大文件进行替换
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
                onProgress(Progress("正在复制到共享文件夹", size, files, 1f, 0))
                Store.copyDir(ctx, out, dest) { p -> onProgress(Progress(p, size, files, 1f, 0)) }
                where = "共享文件夹：${Store.displayName(ctx)}/${out.name}"
            }
        }

        return "克隆完成（libgit2）\n位置：$where\n大小：${fmtSize(size)} · $files 个文件\nHEAD：${head.take(8)}" +
            if (lfsMsg.isNotBlank()) "\n$lfsMsg" else ""
    }
}
