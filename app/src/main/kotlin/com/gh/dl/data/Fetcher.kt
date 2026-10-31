package com.gh.dl.data

import com.gh.dl.ui.AppState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 浏览的「抓取器」抽象：内置 OkHttp，或调用命令行工具（curl / wget）。
 *
 * curl / wget 采用「内置优先、本机工具备用」的顺序：多数设备缺失系统工具，
 * Android 10+ 的 AOSP 不再自带 GNU wget（仅保留功能有限的 toybox wget），部分机型无 curl。
 *
 * 内置方式与 aria2c / libgit2 完全一致（参见 [AriaEngine] 注释）：
 * 将静态编译的可执行文件命名为 `libcurl.so` / `libwget.so` 放入 jniLibs/arm64-v8a/，
 * 安装时由系统解压至 nativeLibraryDir。SELinux 仅允许在该目录执行，
 * 从 filesDir / cacheDir 执行将被拒绝（error=13）。
 *
 * 浏览耗时主要在取图与内联，与 HTTP 客户端选择无关：同一页面系统 curl 抓取 330KB 用时 0.9s、
 * 内置 OkHttp 用时 1.4s；取图已从渲染关键路径移除（参见 [GhView.IMG_HOST]）。
 *
 * 接入 curl / wget 的原因：
 *  1. 系统工具使用自身的 DNS / 代理 / TLS 配置，在配置全局代理或 OkHttp 受
 *     证书钉扎库干扰的机型上快于应用直连；
 *  2. 与 aria2c 采用同一执行机制，不引入新的执行路径。
 *
 * 安全边界（四条，均为必需）：
 *  - **禁止拼接 shell 字符串**：ProcessBuilder 使用参数数组，URL 作为单个 argv 传入，
 *    因此 `; rm -rf` / 反引号 / `$( )` 等仅会形成无法连接的 URL；
 *  - 仅允许 http/https，且 [GhView.isPublicHost] 的内网拦截同样适用于此
 *    （README 为第三方内容，不可由其决定设备连接目标）；
 *  - 输出写入 App 私有缓存目录的临时文件，不使用 shell 重定向，且用完即删；
 *  - **协议限制对内置 curl 同样生效**（[curlArgs] 中写死的 --proto / --proto-redir）：
 *    内置静态构建同样支持 file:// gopher:// 等协议，两者需使用一致参数。
 *    wget 无需额外参数：GNU wget 实现中重定向仅跟随 http(s)/ftp，
 *    内置版本额外添加 --no-config（用户 .wgetrc 可改变抓取行为甚至关闭证书校验）。
 *
 * 失败（工具不存在 / 超时 / 非零退出）统一返回 null，由调用方回退至内置 OkHttp。
 * 此层不可导致用户浏览失败。
 */
object Fetcher {

    const val OKHTTP = "okhttp"
    const val CURL = "curl"
    const val WGET = "wget"

    /**
     * 内置二进制的文件名（伪装为 .so 放入 jniLibs，安装后位于 nativeLibraryDir）。
     * 名称带 `.so` 是为了让打包工具与安装器将其作为 native 库处理并解压；
     * 内容实际为静态链接的 ELF 可执行文件，并非 dlopen 加载的库。
     */
    private const val LIB_CURL = "libcurl.so"
    private const val LIB_WGET = "libwget.so"

    /** 内置 curl 的版本，仅用于界面标注（不参与任何判断） */
    const val BUNDLED_CURL_VERSION = "8.11.0"
    /** 内置 wget 的版本（1.25.0。与 curl 同理，选取可获得源码、
     *  且可静态交叉编译进 APK 的版本，并非「官网最新」） */
    const val BUNDLED_WGET_VERSION = "1.25.0"

    /** 单次抓取的时间上限（秒）。命令行工具无 App 的 8s 连接超时，必须自行限定 */
    private const val TIMEOUT_S = 25L

    /** 响应体大小上限：GitHub 仓库页实测 330KB~690KB，8MB 已非常宽松，
     *  超过此值通常表示被重定向至其他内容（如大文件），直接判定失败。 */
    private const val MAX_BODY_BYTES = 8_000_000L

    /**
     * 抓取结果。[truncated] 为真表示响应体达到 [MAX_BODY_BYTES] 后即停止读取。
     * 「静默提供不完整内容」比「直接失败」更严重：用户会误以为仓库内容即如此，
     * 因此该标志必须传递至页脚提示（参见 [GhView.fetch]）。
     */
    class Got(val status: Int, val body: String, val truncated: Boolean = false)

    /** 缓存目录由界面层注入（与 [GhView.imageCacheDir] 同源）；未注入时不可使用外部工具 */
    @Volatile var tmpDir: File? = null

    /**
     * nativeLibraryDir，由界面层注入（参见 [com.gh.dl.ui.SettingsViewModel.initSettings]）。
     * 内置的 libcurl.so / libwget.so 位于此目录；未注入时仅剩「查找本机工具」一条路径。
     */
    @Volatile var binDir: File? = null

    /**
     * 内置 curl / wget 使用的根证书包：与 aria2 复用同一份文件、同一套「按 App 版本重解」的规则
     * （[AriaEngine.caBundleFor] → filesDir/aria2/cacert.pem，122 张根证书）。
     *
     * 内置 curl 必须显式指定 CA 的原因：静态编译的 curl 无法读取系统 CA 路径
     * （Android 的系统信任库位于 /apex 的 keystore 中，非 curl 识别的 PEM 文件），
     * 未指定时仅剩两种不可接受的选择：要么放弃证书校验（等同于将浏览内容暴露于中间人攻击，绝不允许），
     * 要么每次 TLS 均失败。提供 PEM 文件方可**继续完整校验**：
     * 实测同一份 curl 对自签证书、过期证书统一以 rc=60 拒绝。
     * 本机 curl 无需此参数（其自带 /system 的 CA 路径），因此仅内置时传递。
     */
    @Volatile var cacert: File? = null

    /**
     * 活跃子进程与 OkHttp 请求登记处。
     *
     * 必须登记的原因：页面抓取运行于 [com.gh.dl.ui.BrowseViewModel] 自启的后台线程，
     * 用户退出界面时该线程不会因此中断，curl 子进程将继续运行满 25s，
     * 期间的网络、内存以及其在私有目录留下的临时文件仍被该 App 占用。
     * 通过登记处，[cancelAll] 可在界面销毁时统一终止（此为必需的收尾操作，非优化）。
     */
    private val liveProcs = ConcurrentHashMap.newKeySet<Process>()
    private val liveCalls = ConcurrentHashMap.newKeySet<okhttp3.Call>()

    /** 终止所有在途请求：销毁子进程、取消 OkHttp call。可在任意线程调用，幂等。 */
    fun cancelAll() {
        liveProcs.toList().forEach { p ->
            runCatching { p.destroy() }          // 先发送 SIGTERM
            runCatching { p.destroyForcibly() }  // 再立即强制终止
        }
        liveProcs.clear()
        liveCalls.toList().forEach { runCatching { it.cancel() } }
        liveCalls.clear()
    }

    /** 该工具对应的内置库文件名；未知工具名返回 null */
    private fun libName(name: String): String? = when (name) {
        CURL -> LIB_CURL
        WGET -> LIB_WGET
        else -> null
    }

    /** 本机检测 App 是否自带该二进制 */
    fun hasBundled(name: String): Boolean = bundledPath(name) != null

    /**
     * 定位内置二进制。目录形态有两种，均需尝试：
     * 多数机型 nativeLibraryDir 直接为 `.../lib/arm64`，但也有机型为父目录 `.../lib`，
     * 实际的 .so 位于 `lib/arm64/` 下。此问题 aria2c 早已遇到（参见 [AriaEngine.resolve] 注释），
     * 当时仅按一种实现，导致 Fetcher 在此类机型上始终「找不到内置版本」，静默回退至本机工具。
     */
    private fun bundledPath(name: String): String? {
        val lib = libName(name) ?: return null
        val dir = binDir ?: return null
        // **无根证书时视内置版本不存在**：静态编译的 curl / wget 无法读取 Android 系统信任库，
        // 缺少该 PEM 仅有两种选择：放弃证书校验（绝不允许，浏览内容为第三方内容），
        // 或者每次 TLS 均失败（表现为「选择 curl 却比不使用更慢，因无效请求后再回退」）。
        // 返回 null 使 toolPath 查找本机工具（其自带 /system 的 CA 路径），找不到则直接回退至 OkHttp。
        // cacert 需等待界面侧注入（SettingsViewModel.initSettings），注入前此处同样为 null。
        // 而实际抓取均发生于注入之后，不会因此减少内置版本的使用。
        if (cacert == null) return null
        val abi = runCatching { android.os.Build.SUPPORTED_ABIS.firstOrNull() }.getOrDefault("arm64-v8a")
        for (f in listOf(File(dir, lib), File(dir, "$abi/$lib"))) {
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        return null
    }

    /**
     * 定位工具：**内置优先，本机工具作为备用**。
     *
     * 此顺序的原因：内置版本的版本、编译参数（协议白名单、CA、HTTP 实现）均为已知，
     * 出问题时可在本端复现；本机工具各厂商裁剪过参数子集（Android 10+ 的 wget 基本仅剩
     * toybox 的 `-O`/`-T`），传递一个未识别的参数将导致整条通道失效。
     * 本机工具仍为有价值的备用：部分配置全局代理的机型上，系统工具使用自身的 DNS/代理，
     * 反而比 App 直连更快，因此设备无内置版本（如非 arm64 机型）时仍可使用。
     */
    fun toolPath(name: String): String? = bundledPath(name) ?: BIN.find(name)

    /** 当前使用的是「内置」还是「本机」，界面需如实显示 */
    fun sourceOf(name: String): String = when {
        hasBundled(name) -> "内置"
        BIN.find(name) != null -> "本机"
        else -> "无"
    }

    /**
     * 按 [AppState.fetcher] 的选择抓取 URL。
     * 返回 null 表示「该路径未成功」，调用方应回退至内置 OkHttp。
     */
    fun fetch(url: String, ua: String = GhView.CLI_UA): Got? {
        if (!GhView.isPublicHost(url)) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return when (AppState.fetcher) {
            CURL -> runCatching { exec(url, ua, curlArgs(url, ua)) }.getOrNull()
            WGET -> runCatching { exec(url, ua, wgetArgs(url, ua)) }.getOrNull()
            else -> null
        }
    }

    /** 仅内置版本需要显式指定 CA（本机自带系统 CA 路径），返回需追加的参数 */
    private fun caArgsFor(name: String): List<String> =
        if (!hasBundled(name)) emptyList()
        else when (name) {
            // --cacert：使内置 curl 使用打包进 APK 的根证书进行完整校验
            CURL -> cacert?.let { listOf("--cacert", it.absolutePath) } ?: emptyList()
            // wget 的写法为 --ca-certificate=FILE（等号形式，仅 GNU wget 识别）。
            // 需明确说明，否则易被误解为「仅信任此一份」：wget 的 openssl.c 在
            // 读取 --ca-certificate 之前**无条件**调用 SSL_CTX_set_default_verify_paths()，
            // 即该参数为「向信任库追加」，而非替换。
            // 该默认路径为编译期常量（本版本链接的 Alpine libcrypto 中为 /etc/ssl），
            // Android 上不存在 /etc/ssl/certs，因此设备上默认库为空。传入的这份 PEM
            // 即为唯一锚点，行为与 curl 的 --cacert 一致（fail-closed）。
            // 自 v1.2.1 起实测印证此结论：同一份二进制，在存在空 /etc/ssl 的挂载命名空间中
            // 使用自签 bogus CA 连接 github.com 时 rc=5 拒绝；将宿主 /etc/ssl/certs（243 张真实根证书）
            // 暴露，同一命令变为 rc=0 通过。区别仅在于默认库，参数语义未变。
            WGET -> cacert?.let { listOf("--ca-certificate=" + it.absolutePath) } ?: emptyList()
            else -> emptyList()
        }

    /**
     * [Net.browse] 的派生客户端：增加**逐跳 SSRF 检查**。
     *
     * 不可仅在入口检查一次的原因：OkHttp 的 followRedirects 会在不可见的位置跟随跳转，
     * 「公网仓库页 302 → http://127.0.0.1:xxxx」这类写法可完全绕过入口检查。
     * 网络拦截器在每一跳（含重定向后的请求）均会执行，因此在此处复查 host 方可拦截。
     * 仅对浏览链路添加，不影响下载主链路（后者地址由用户自行输入，依据不同）。
     *
     * 每次现取现派生，**不可缓存为 val**：[Net.rebuild] 会因更改代理 / 更改优选 IP
     * 而替换 [Net.browse]，缓存将导致该链路始终使用旧配置（用户更改代理不生效）。
     * newBuilder() 复用同一连接池与调度线程，派生开销很低。
     */
    internal fun guarded(): OkHttpClient =
        Net.browse.newBuilder()
            .addNetworkInterceptor(object : okhttp3.Interceptor {
                override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
                    if (!GhView.isPublicHost(chain.request().url.toString())) {
                        throw java.io.IOException("跳转至不允许的地址（内网拦截）")
                    }
                    return chain.proceed(chain.request())
                }
            })
            .build()

    /**
     * 内置通道：OkHttp（带 DoH + 真实 IP 优选，参见 [Net.browse]）。
     *
     * 注意**不可**直接调用 `body.string()`：该方法会将整个响应一次性读入内存，
     * 若被重定向至数百 MB 的内容（或某仓库页本身超大），
     * 单个 String 即可能触发 OOM。此处按 [MAX_BODY_BYTES] 封顶读取，超出部分不读，
     * 并将 [Got.truncated] 标记以便界面如实说明。
     */
    fun okhttp(url: String, ua: String = GhView.CLI_UA): Got? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        val call = guarded().newCall(req)
        liveCalls.add(call)
        try {
            call.execute().use { resp -> readCapped(resp) }
        } finally {
            liveCalls.remove(call)
        }
    }.getOrNull()

    /** 带上限地读取响应体（参见 [okhttp] 说明） */
    private fun readCapped(resp: Response): Got {
        val stream = resp.body?.byteStream()
            ?: return Got(resp.code, "", false)
        stream.use { input ->
            // 多留 1 字节：读满 cap 后是否仍能读出数据，即为「是否超出」的判据，
            // 不依赖 available()（响应流未必被 Content-Length 预填满，否则可能误报 0）
            val cap = MAX_BODY_BYTES.toInt() + 1
            val buf = ByteArray(64 * 1024)
            val out = java.io.ByteArrayOutputStream(64 * 1024)
            var total = 0
            var over = false
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                // 使用 >= 而非 >：读满 cap（= 上限 + 1）本身即说明已超出
                if (total + n >= cap) {
                    out.write(buf, 0, MAX_BODY_BYTES.toInt() - total)
                    over = true
                    break
                }
                out.write(buf, 0, n)
                total += n
            }
            val charset = resp.body?.contentType()?.charset() ?: Charsets.UTF_8
            return Got(resp.code, String(out.toByteArray(), charset), over)
        }
    }

    private fun curlArgs(url: String, ua: String): List<String> = listOf(
        // -s 静默 -S 仍报错；-L 跟随重定向（GitHub 大量 302）；不使用 --fail：
        // 需要 403 的响应体以判断「UA 被拦截」，因此状态码单独用 -w 输出
        "curl", "-s", "-S", "-L", "--compressed",
        // **此两条为 curl 专有的风险，必须写死**：
        // curl 支持 file:// / gopher:// / dict:// 等协议，而 README 中可包含
        // 「公网地址 302 跳转至 file:///data/data/com.gh.dl/...」的链接。
        // SSRF 检查仅针对**初始**地址，跳转后 curl 会将 App 私有文件写入
        // 正要显示的临时文件，等同于将私有内容渲染至界面。
        // --proto 锁定入口协议，--proto-redir 锁定「跳转后仅允许 https」。
        "--proto", "=https,http",
        "--proto-redir", "=https",
        "--max-redirs", "6",
        "--max-time", TIMEOUT_S.toString(),
        "--connect-timeout", "8",
        "-A", ua,
        "-o", "@OUT@",
        "-w", "%{http_code}"
    ) + caArgsFor(CURL) + listOf(url)

    private fun wgetArgs(url: String, ua: String): List<String> {
        // 证书校验保持默认开启：此为显示给用户的内容，不向中间人攻击妥协
        val base = listOf(
            "wget", "-q", "--timeout=8", "--tries=1",
            "--user-agent=" + ua,
            "-O", "@OUT@"
        )
        // 仅内置版本追加 GNU 专有参数。理由与 curl 相反：
        // 非 arm64 机型使用**本机**工具，Android 上其几乎均为 toybox wget，
        // 参数子集极小，仅识别 -q/-O/-T，多写一个未识别的参数将导致整条通道失效。
        // 内置版本为完整 GNU wget 1.25.0，此五条各有必要：
        //  --no-config    不读取 $HOME/.wgetrc / WGETRC：实测用户自行配置的文件可写入
        //                 skip_certificate 等选项，会静默改变抓取行为（含关闭校验）；
        //  --max-redirect 限定重定向链长度，与 curl 的 --max-redirs 6 对齐；
        //  --no-hsts      禁止向私有目录写入 ~/.wget-hsts：单次抓取不应留下副作用文件，
        //                 且 HSTS 记录会反过来决定下次使用的协议，导致行为不稳定；
        //  -S             将响应头输出至 stderr（exec 从该处获取状态码，参见 [readWgetStatus]），
        //                 与 curl 的 -w %{http_code} 目的相同：403/404 的响应体也需显示；
        //  --content-on-error 报错页（404 的 GitHub 页面本身即为有用内容）也落盘，
        //                 否则 wget 遇到 4xx/5xx 直接丢弃响应体，App 仅能看到「失败」。
        val gnu = if (hasBundled(WGET))
            listOf("--no-config", "--max-redirect=6", "--no-hsts", "-S", "--content-on-error")
        else emptyList()
        return base + gnu + caArgsFor(WGET) + listOf(url)
    }

    /**
     * 从 wget -S 写入 stderr 的响应头中取**最后一次**出现的状态码。
     *
     * 必须取最后一次：跟随重定向时每一跳均会输出一行 `HTTP/1.1 301 Moved Permanently`，
     * 取第一行会将「跳转至他处」误认为结果。
     * wget 的格式为 `  HTTP/1.1 404 Not Found`（前导两空格），HTTP/2 侧输出为
     * `  HTTP/2 200 OK`，因此正则不可写死版本号。
     * 解析失败返回 null，由调用方按退出码回退，不可猜测 200 蒙混过关。
     */
    private val HTTP_STATUS = Regex("HTTP/[0-9.]+\\s+(\\d{3})")

    internal fun parseHttpStatus(err: String): Int? =
        HTTP_STATUS.findAll(err).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()

    /**
     * 执行一次。`@OUT@` 占位符替换为私有缓存目录中的临时文件，
     * 避免将用户可控的 URL 与文件名拼接。
     */
    private fun exec(url: String, ua: String, args: List<String>): Got? {
        val dir = tmpDir ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        val out = File.createTempFile("ghf", ".html", dir)
        try {
            val cmd = args.map { if (it == "@OUT@") out.absolutePath else it }
            // 内置优先、本机备用（参见 [toolPath]）；找不到则跳过外部通道，回退至 OkHttp
            val path = toolPath(cmd.first()) ?: return null
            val pb = ProcessBuilder(listOf(path) + cmd.drop(1))
            // stderr **必须有去处**：curl -s -S 的报错、wget 的告警均写入此流。
            // 仅读 stdout 而任 stderr 堆积于管道，攒满 64KB 内核缓冲将导致子进程
            // 直接阻塞于 write（表现为「卡至超时」，难以排查）。
            // 此处不使用 redirectErrorStream(true)：该方法会将错误文本混入 stdout，
            // 而 stdout 需用于读取 %{http_code}，混入后将无法解析。
            val err = File.createTempFile("ghf", ".err", dir)
            pb.redirectError(err)
            val p = pb.start()
            liveProcs.add(p)
            try {
                // stdout 仅含 curl 的 %{http_code}（几十字节），读满不会阻塞
                val code = p.inputStream.bufferedReader().use { it.readText().trim() }
                if (!p.waitFor(TIMEOUT_S + 5, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    return null
                }
                val exit = p.exitValue()
                val errText = runCatching { err.readText() }.getOrDefault("")
                val size = out.length()
                if (size == 0L) {
                    // 将 stderr 末尾带入异常文本：外部工具失败最常见的表现为
                    // 「什么也未返回」，无此行日志用户在设置页无从判断
                    throw IllegalStateException(
                        "stderr: " + errText.trim().take(160)
                    )
                }
                val over = size > MAX_BODY_BYTES
                val body = if (over) {
                    // 命令行工具无流式封顶能力，只能读全量后截断。
                    // 但既然超出上限，仅取前面所需部分，避免将数十 MB 字符串留在内存中
                    val raf = java.io.RandomAccessFile(out, "r")
                    try {
                        val len = minOf(size, MAX_BODY_BYTES).toInt()
                        val bytes = ByteArray(len)
                        raf.readFully(bytes)
                        String(bytes, Charsets.UTF_8)
                    } finally {
                        runCatching { raf.close() }
                    }
                } else out.readText()
                // 状态码有两个来源：curl 输出至 stdout（-w %{http_code}），
                // wget 无输出状态码的参数，只能从 -S 写入 stderr 的「HTTP/1.1 404 …」中提取。
                // 不可仅按「退出码 0 = 成功」判断：内置版本添加 --content-on-error 后，
                // 4xx/5xx 也会落盘但退出码非 0，否则会将有用的报错页误判为整条通道失败。
                val status = code.toIntOrNull()
                    ?: parseHttpStatus(errText)
                    ?: if (exit == 0) 200 else 0
                if (exit != 0 && status == 0) {
                    throw IllegalStateException("退出码 $exit：" + errText.trim().take(160))
                }
                return Got(status, body, over)
            } finally {
                liveProcs.remove(p)
                runCatching { err.delete() }
                // 超时或被中断的分支也需保证进程已终止：destroyForcibly 幂等，
                // 对正常退出的进程调用无任何副作用
                if (p.isAlive) runCatching { p.destroyForcibly() }
            }
        } finally {
            runCatching { out.delete() }
        }
    }
}

/**
 * 在系统 PATH 中查找**本机**可执行文件（仅作为内置二进制的备用，参见 [Fetcher.toolPath]）。
 *
 * Android 上 ProcessBuilder 会自行按 PATH 查找，但需先确认「本机是否存在该工具」，
 * 以便在设置页如实显示，并在不存在时不尝试。
 * 注意不可仅查 /system/bin：较新机型将 curl 置于 /apex/... 或 /vendor/bin，因此补充常见目录。
 */
private object BIN {
    private val DIRS = listOf(
        "/system/bin", "/system/xbin", "/vendor/bin", "/bin", "/usr/bin",
        "/apex/com.android.resolv/bin"
    )

    fun find(name: String): String? {
        if (name.contains('/')) return if (File(name).canExecute()) name else null
        val pathEnv = runCatching { System.getenv("PATH").orEmpty() }.getOrDefault("")
        for (d in (DIRS + pathEnv.split(':')).filter { it.isNotBlank() }) {
            val f = File(d, name)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        return null
    }
}
