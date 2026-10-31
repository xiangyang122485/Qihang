package com.gh.dl.data

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Aria2 原生引擎封装。
 *
 * 不将 aria2 源码编译进 APK（该方案涉及复杂的 NDK 交叉编译链），而是直接内置
 * 预编译的 [aria2c] 可执行文件：以 `libaria2c.so` 形式存放于 jniLibs/<abi>/，
 * 安装时由系统解压至 `nativeLibraryDir`。原因如下：
 *
 *   Android 的 SELinux 规则（untrusted_app 域）**禁止**执行 `filesDir`/`cacheDir`
 *   中的文件（标签 app_data_file 无 exec 类型，会报 EACCES / error=13）；
 *   但 `nativeLibraryDir` 中的 .so（标签 apk_data_file）允许执行，
 *   此即所有 App 加载自身 so 的方式。因此将二进制以 .so 形式内置，是
 *   唯一稳定的「内置并执行原生二进制」方案（libsu / BusyBox 等亦采用此方式）。
 *
 * 若设备 ABI 无对应的 libaria2c.so（当前仅内置 arm64-v8a），[available]
 * 返回 false，调用方自动回退至单线程，不会导致下载失败。
 */
object AriaEngine {

    /** 已定位的 aria2c 可执行文件（缓存） */
    @Volatile
    private var binFile: File? = null

    /**
     * 二进制与 CA 根证书包均就绪方视为可用。
     *
     * CA 为必要条件：[download] 绝不在无法获取根证书时退回 --check-certificate=false，
     * 该操作等同于将要安装的二进制交予中间人。任一条件缺失时由调用方回退至
     * OkHttp 引擎（走系统信任链，证书校验始终开启）。
     */
    fun available(ctx: Context): Boolean =
        resolve(ctx) != null && cacert(ctx) != null && execState != S_BROKEN

    /** 引擎在本机不可执行（ABI 不匹配、SELinux 拒绝 execve、ELF 无法加载）。
     *  调用方应改用其他引擎重试，而不是把「设备不支持」报告成「下载失败」。 */
    class Unavailable(msg: String) : RuntimeException(msg)

    private const val S_UNKNOWN = 0
    private const val S_OK = 1
    private const val S_BROKEN = 2

    /** 执行探测结果缓存：0 未探测、1 可执行、2 不可执行 */
    @Volatile
    private var execState = S_UNKNOWN

    /** 不可执行的原因，供设置页与日志展示 */
    @Volatile
    var execError: String? = null
        private set

    /** 诊断页展示用：定位结果与执行探测状态 */
    fun status(ctx: Context): String = when {
        resolve(ctx) == null -> "未内置匹配 ABI 的二进制"
        cacert(ctx) == null -> "缺少根证书包"
        execState == S_BROKEN -> "不可执行：" + (execError ?: "")
        execState == S_OK -> "可执行"
        else -> "待探测（首次下载时验证）"
    }

    /**
     * 一次性真实执行探测：`exists` 与 `canExecute` 都不足以说明进程能被启动。
     * 失败场景：部分 ROM 的 SELinux 在 execve 阶段返回 EACCES；设备 ABI 与内置二进制不匹配；
     * 16KB page size 设备上 ELF 段对齐不满足要求导致加载失败。
     *
     * 只可在后台线程调用（含一次 fork/exec，最长等待 8 秒），因此 [available] 不含本探测，
     * 由下载线程与设置页的显式刷新触发。
     * 输出必须重定向到文件而非管道：管道下若子进程挂起，
     * 读取会一直阻塞，`waitFor` 的超时也就无从生效。
     */
    @Synchronized
    fun verifyExec(ctx: Context): Boolean {
        if (execState == S_OK) return true
        if (execState == S_BROKEN) return false
        val bin = resolve(ctx) ?: run {
            execState = S_BROKEN
            execError = "未找到 libaria2c.so"
            return false
        }
        val out = File.createTempFile("aria2probe", ".txt", ctx.cacheDir)
        return try {
            val p = ProcessBuilder(bin.absolutePath, "--version")
                .redirectErrorStream(true)
                .redirectOutput(out)
                .start()
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroy()
                p.destroyForcibly()
                execState = S_BROKEN
                execError = "aria2c 启动超时"
                return false
            }
            val code = p.exitValue()
            val text = runCatching { out.readText() }.getOrDefault("")
            if (code != 0 || !text.contains("aria2")) {
                execState = S_BROKEN
                execError = "aria2c 无法执行（退出码 $code）"
                false
            } else {
                execState = S_OK
                true
            }
        } catch (t: Throwable) {
            execState = S_BROKEN
            execError = "aria2c 执行被拒绝：${t.message ?: t.javaClass.simpleName}"
            android.util.Log.w("AriaEngine", "exec probe failed", t)
            false
        } finally {
            runCatching { out.delete() }
        }
    }

    /**
     * App 版本名，进程内仅查询一次。
     * [cacert] 需据此判断「新版携带的根证书是否需要重新解压」，而 [available] 会被下载设置页
     * 在组合时反复调用——`getPackageInfo` 为一次 binder 调用，不应每次重组均执行。
     * 版本名在进程生命周期内不变（覆盖安装会重启进程），因此缓存是安全的。
     */
    @Volatile private var version: String? = null

    private fun appVersion(ctx: Context): String = version ?: runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?").also { version = it }

    @Synchronized
    private fun resolve(ctx: Context): File? {
        binFile?.let { if (it.exists() && it.canExecute()) return it }
        // 安装时由系统解压至 nativeLibraryDir（标签 apk_data_file，SELinux 允许执行）。
        // 注意：不同 Android 版本下 nativeLibraryDir 可能直接指向 ABI 子目录（.../lib/arm64），
        // 也可能指向 lib 父目录（.../lib，.so 位于 lib/arm64/）。两种路径均需尝试，避免找不到。
        val base = ctx.applicationInfo.nativeLibraryDir
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val candidates = listOf(
            File(base, "libaria2c.so"),
            File(base, "$abi/libaria2c.so")
        )
        for (f in candidates) {
            if (f.exists() && f.canExecute()) {
                binFile = f
                return f
            }
        }
        return null
    }

    /** 设置中配置的 HTTP 代理，转为 aria2c 的 --all-proxy 参数值 */
    private fun ariaProxy(): String? =
        if (Net.proxyHost.isNotBlank() && Net.proxyPort > 0)
            "http://${Net.proxyHost}:${Net.proxyPort}" else null

    /**
     * 供「内置命令行抓取器」（curl / wget）使用的同一份根证书包。
     *
     * 自 v1.2.1 内置 curl / wget 后复用它：静态编译的 curl / wget 无法读取 Android 系统信任库
     * （路径非其识别的 PEM 文件位置），必须显式提供一份 PEM 方可执行完整证书校验。
     * 与 aria2 共用同一份证书包、同一套按版本重新解压的规则，避免两份不同步的 CA。
     */
    fun caBundleFor(ctx: Context): File? = cacert(ctx)

    /**
     * 解压 CA 证书包（assets/aria2/cacert.pem）；不存在则返回 null，调用方按不可用回退引擎。
     *
     * **必须按 App 版本号重新解压**：此为打包进 APK 的静态根证书集合，不随系统信任库更新。
     * 旧实现为「文件存在且非空即直接使用」，导致首次启动解压的证书包会一直使用至卸载重装——
     * 用户升级 App、新版携带更新过的根证书时，磁盘上仍为旧版，
     * 待某根证书过期后开始报 SSL 校验失败，而该问题在代码中难以定位。
     * 当前实现将版本号写入同目录的标记文件，版本不匹配时重新解压（同时覆盖过期根被剔除的情况）。
     */
    private fun cacert(ctx: Context): File? {
        val dir = File(ctx.filesDir, "aria2")
        val out = File(dir, "cacert.pem")
        val stamp = File(dir, "cacert.ver")
        val ver = appVersion(ctx)
        val cached = runCatching { stamp.readText().trim() }.getOrDefault("")
        if (out.exists() && out.length() > 0 && cached == ver) return out
        return try {
            dir.mkdirs()
            // 先写临时文件再重命名：解压中途被终止不会留下半份证书包（半份会导致校验整体失败，
            // 而表现仅为「下载报证书错误」，难以定位至此处）
            val tmp = File(dir, "cacert.pem.tmp")
            ctx.assets.open("aria2/cacert.pem").use { ins ->
                tmp.outputStream().use { ins.copyTo(it, 1 shl 16) }
            }
            if (tmp.length() == 0L) { runCatching { tmp.delete() }; return null }
            if (!tmp.renameTo(out)) { runCatching { out.delete() }; tmp.copyTo(out, overwrite = true) }
            runCatching { stamp.writeText(ver) }
            out
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 使用 aria2c 下载 [url] 至 [name]。
     * -x16 -s16：16 连接分片；-k1M：分片最小 1MB（小文件不分片）；-c：支持断点续传。
     * @param onProgress (已下载字节, 总字节或 -1, 瞬时速度 B/s)
     * @param cancelHolder 引擎启动时写入「停止当前传输」的回调，供暂停按钮调用
     * @param resume true=续传（保留上次暂停留下的临时文件 + .aria2 控制文件，aria2c 自动继续）
     */
    fun download(
        ctx: Context,
        url: String,
        name: String,
        onProgress: (Long, Long, Long) -> Unit,
        cancelHolder: AtomicReference<(() -> Unit)?>,
        resume: Boolean = false,
        totalHint: Long = -1L,
        jobKey: Long = 0L
    ): Fetch.Saved {
        val bin = resolve(ctx)
            ?: throw Unavailable("Aria2 二进制未就绪（需要 arm64-v8a 的 libaria2c.so，当前设备 ABI 不支持）")
        if (!verifyExec(ctx)) throw Unavailable(execError ?: "Aria2 在本机不可执行")
        val outDir = File(ctx.cacheDir, "ariatmp")
        outDir.mkdirs()
        val tmp = File(outDir, Fetch.tmpName(jobKey, name))
        // aria2 的控制文件名 = 输出文件名 + ".aria2"，因此必须与 tmp 保持一致
        val ctrl = File(outDir, tmp.name + ".aria2")
        // 续传：保留临时文件与控制文件；否则清除旧残留
        if (tmp.exists() && !resume) {
            tmp.delete()
            ctrl.delete()
        }

        val args = mutableListOf(
            bin.absolutePath,
            "-x", "16", "-s", "16", "-k", "1M",
            "--dir", outDir.absolutePath,
            "-o", tmp.name,
            "--summary-interval=1",
            "--console-log-level=warn",
            "--allow-overwrite=true",
            "-c" // 断点续传：暂停后保留的 .aria2 控制文件使 aria2c 能够继续
        )
        // HTTPS 证书：必须携带 CA 包执行完整校验。无法获取时直接失败（调用方已按
        // [available] 回退至 OkHttp 引擎），此处绝不启用 --check-certificate=false。
        val ca = cacert(ctx)
            ?: throw RuntimeException("Aria2 缺少 CA 根证书包，本次改用其他引擎")
        args += listOf("--ca-certificate", ca.absolutePath)
        // 用户在设置中配置的 HTTP 代理必须传递给子进程：
        ariaProxy()?.let { args += listOf("--all-proxy", it) }
        args += url

        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        val p = try {
            pb.start()
        } catch (t: Throwable) {
            // execve 被拒（SELinux EACCES 等）：记住原因，后续任务直接走其他引擎
            execState = S_BROKEN
            val why = "aria2c 执行被拒绝：" + (t.message ?: t.javaClass.simpleName)
            execError = why
            throw Unavailable(why)
        }
        // 将「停止」回调交予调用方（暂停按钮使用）。destroy 后 waitFor 返回，循环结束
        val destroyed = AtomicBoolean(false)
        cancelHolder.set {
            destroyed.set(true)
            try {
                p.destroy()
            } catch (_: Throwable) {
                // 进程可能已退出，销毁失败无需处理
            }
        }

        // aria2c 每秒（--summary-interval=1）输出一行进度。两个版本格式不同，均兼容：
        //   1.36 及之前： [#id 84MiB/338MiB(24%) CN:16 DL:12MiB ETA:20s]
        //   1.37+      ： [#id 24% 84MiB/338MiB CN:16 DL:12MiB ETA:20s]   （百分比移至前方）
        // 处理方式：先匹配「CUR/TOT」对（两种格式顺序不变），再匹配「DL: 速度」，百分比由 CUR/TOT 直接计算，
        // 不依赖括号内的数字，因此对两个版本均稳定。
        val rePair = Regex("""(\d[\d.]*)(KiB|MiB|GiB|B)/(\d[\d.]*)(KiB|MiB|GiB|B)""")
        val reSpeed = Regex("""DL:(\d[\d.]*)(KiB|MiB|GiB|B)""")
        val unit = mapOf(
            "B" to 1L,
            "KiB" to 1024L,
            "MiB" to 1024L * 1024,
            "GiB" to 1024L * 1024 * 1024
        )
        var lastT = System.currentTimeMillis()
        var lastB = 0L
        p.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val mp = rePair.find(line) ?: continue
                val cur = (mp.groupValues[1].toDouble() * (unit[mp.groupValues[2]] ?: 1L)).toLong()
                val tot = (mp.groupValues[3].toDouble() * (unit[mp.groupValues[4]] ?: 1L)).toLong()
                // DL: 速度字段（两种格式均包含）；缺失时使用「已下载字节增量 / 时间」回退估算
                val ms = reSpeed.find(line)
                var sp = if (ms != null)
                    (ms.groupValues[1].toDouble() * (unit[ms.groupValues[2]] ?: 1L)).toLong() else 0L
                // aria2c 输出中无法获取总大小时，使用下载前 HEAD 探测到的真实大小作为回退
                val finalTot = if (tot > 0) tot else totalHint
                val now = System.currentTimeMillis()
                if (now - lastT >= 400) {
                    val dt = now - lastT
                    if (sp == 0L && dt > 0) sp = (cur - lastB) * 1000 / dt
                    lastT = now
                    lastB = cur
                    onProgress(cur, finalTot, sp)
                }
            }
        }
        val code = p.waitFor()
        // 用户主动暂停：保留临时文件 + 控制文件（供下次续传），不视为「失败」
        if (destroyed.get()) throw Fetch.PausedException()
        if (code != 0) {
            // 保留临时文件与控制文件：二者是 aria2 的断点依据，删除后「重试」必然从 0 开始。
            // 连接中断、服务端限流都属可续传场景，交由下次 -c 处理。
            throw RuntimeException("aria2c 退出码 $code")
        }

        if (!tmp.exists()) throw RuntimeException("aria2c 未生成文件")
        val saved = Fetch.commit(ctx, tmp, name)
        tmp.delete()
        ctrl.delete()
        return saved
    }
}
