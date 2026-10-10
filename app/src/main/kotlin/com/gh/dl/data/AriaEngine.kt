package com.gh.dl.data

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Aria2 原生引擎封装。
 *
 * 不把 aria2 源码编进 APK（那是一条脆弱的 NDK 交叉编译链），而是直接内置
 * 预编译好的 [aria2c] 可执行文件：以 `libaria2c.so` 的形式放在 jniLibs/<abi>/，
 * 安装时由系统解压到 `nativeLibraryDir`。关键原因——
 *
 *   Android 的 SELinux 规则（untrusted_app 域）**禁止**执行 `filesDir`/`cacheDir`
 *   里的文件（标签 app_data_file 没有 exec 类型，会报 EACCES / error=13）；
 *   但 `nativeLibraryDir` 里的 .so（标签 apk_data_file）是允许执行的，
 *   这正是所有 App 加载自家 so 的方式。所以把二进制伪装成 .so 内置，是
 *   唯一稳的「内置并执行原生二进制」方案（libsu / BusyBox 等也都这么干）。
 *
 * 若设备 ABI 没有对应的 libaria2c.so（目前只内置了 arm64-v8a），[available]
 * 返回 false，调用方自动回退单线程，不会让下载直接失败。
 */
object AriaEngine {

    /** 已定位的 aria2c 可执行文件（缓存） */
    @Volatile
    private var binFile: File? = null

    /**
     * 二进制与 CA 根证书包都就绪才算可用。
     *
     * CA 是硬性条件：[download] 绝不在拿不到根证书时退回 --check-certificate=false，
     * 那等于把要装到手机上的二进制交给中间人。缺任何一样都由调用方回退到
     * OkHttp 引擎（走系统信任链，证书校验始终开启）。
     */
    fun available(ctx: Context): Boolean = resolve(ctx) != null && cacert(ctx) != null

    /**
     * App 版本名，进程内只查一次。
     * [cacert] 需要它来判断「新版带的根证书要不要重解」，而 [available] 会被下载设置页
     * 在组合时反复调用——`getPackageInfo` 是一次 binder 调用，不该每次重组都发。
     * 版本名在进程生命周期内不会变（覆盖安装会重启进程），所以缓存是安全的。
     */
    @Volatile private var version: String? = null

    private fun appVersion(ctx: Context): String = version ?: runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?").also { version = it }

    @Synchronized
    private fun resolve(ctx: Context): File? {
        binFile?.let { if (it.exists() && it.canExecute()) return it }
        // 安装时由系统解压到 nativeLibraryDir（标签 apk_data_file，SELinux 允许执行）。
        // 注意：不同 Android 版本下 nativeLibraryDir 可能直接指向 ABI 子目录（.../lib/arm64），
        // 也可能指向 lib 父目录（.../lib，.so 在 lib/arm64/）。两种都试，避免找不到。
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

    /**
     * 给「内置的命令行抓取器」（curl / wget）用的同一份根证书包。
     *
     * 第八轮内置 curl / wget 之后复用它：静态编译的 curl / wget 读不到 Android 的系统信任库
     * （那里不是它们认的 PEM 文件路径），必须显式喂一份 PEM 才会做完整证书校验。
     * 与 aria2 共用同一份、同一套按版本重解的规则，避免出现两份不同步的 CA。
     */
    fun caBundleFor(ctx: Context): File? = cacert(ctx)

    /**
     * 解开 CA 证书包（assets/aria2/cacert.pem）；没有则返回 null，调用方按不可用回退引擎。
     *
     * **必须按 App 版本号重解**：这是一份打进 APK 的静态根证书集合，不会跟着系统信任库更新。
     * 老写法是「文件存在且非空就直接用」，于是第一次启动解出来的那一份会一直用到卸载重装——
     * 用户升级 App、新版带的是更新过的根证书，磁盘上却还是旧的，
     * 等某个根过期就开始报 SSL 校验失败，而这种情况在代码里完全看不出问题。
     * 现在版本号写进同目录的一个标记文件，对不上就重新解（顺带覆盖过期根被剔除的情况）。
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
            // 先写临时文件再改名：解到一半被杀不会留下半份证书包（半份会导致校验整体失败，
            // 而表现只是「下载报证书错误」，很难想到是这儿坏的）
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
     * 用 aria2c 下载 [url] 到 [name]。
     * -x16 -s16：16 连接分片；-k1M：分片最小 1MB（小文件不分片）；-c：支持断点续传。
     * @param onProgress (已下载字节, 总字节或 -1, 瞬时速度 B/s)
     * @param cancelHolder 引擎启动时把「停止当前传输」的回调塞进来，供暂停按钮调用
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
            ?: throw RuntimeException("Aria2 二进制未就绪（需要 arm64-v8a 的 libaria2c.so，当前设备 ABI 不支持）")
        val outDir = File(ctx.cacheDir, "ariatmp")
        outDir.mkdirs()
        val tmp = File(outDir, Fetch.tmpName(jobKey, name))
        // aria2 的控制文件名 = 输出文件名 + ".aria2"，所以必须跟着 tmp 走
        val ctrl = File(outDir, tmp.name + ".aria2")
        // 续传：保留临时文件与控制文件；否则清掉旧残留
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
            "-c" // 断点续传：暂停后保留的 .aria2 控制文件让 aria2c 接着下
        )
        // HTTPS 证书：必须带 CA 包做完整校验。拿不到就直接失败（调用方已按
        // [available] 回退到 OkHttp 引擎），这里绝不开 --check-certificate=false。
        val ca = cacert(ctx)
            ?: throw RuntimeException("Aria2 缺少 CA 根证书包，本次改用其它引擎")
        args += listOf("--ca-certificate", ca.absolutePath)
        args += url

        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        val p = pb.start()
        // 把「停止」回调交给调用方（暂停按钮用）。destroy 后 waitFor 会返回，循环结束
        val destroyed = AtomicBoolean(false)
        cancelHolder.set {
            destroyed.set(true)
            try {
                p.destroy()
            } catch (_: Throwable) {
            }
        }

        // aria2c 每秒（--summary-interval=1）打印一行进度。两个版本格式不同，都兼容：
        //   1.36 及之前： [#id 84MiB/338MiB(24%) CN:16 DL:12MiB ETA:20s]
        //   1.37+      ： [#id 24% 84MiB/338MiB CN:16 DL:12MiB ETA:20s]   （百分比挪到前面）
        // 做法：先抓「CUR/TOT」这对（两种格式顺序不变），再抓「DL: 速度」，百分比直接由 CUR/TOT 算，
        // 不依赖那串括号里的数字，所以对两个版本都稳。
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
                // DL: 速度字段（两种格式都有）；没有就用「已下字节增量 / 时间」兜底估算
                val ms = reSpeed.find(line)
                var sp = if (ms != null)
                    (ms.groupValues[1].toDouble() * (unit[ms.groupValues[2]] ?: 1L)).toLong() else 0L
                // aria2c 行里拿不到总大小时，用下载前 HEAD 探到的真实大小兜底
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
        // 用户主动暂停：保留临时文件 + 控制文件（下次续传），不抛成「失败」
        if (destroyed.get()) throw Fetch.PausedException()
        if (code != 0) {
            tmp.delete()
            ctrl.delete()
            throw RuntimeException("aria2c 退出码 $code")
        }

        if (!tmp.exists()) throw RuntimeException("aria2c 未生成文件")
        val saved = Fetch.commit(ctx, tmp, name)
        tmp.delete()
        ctrl.delete()
        return saved
    }
}
