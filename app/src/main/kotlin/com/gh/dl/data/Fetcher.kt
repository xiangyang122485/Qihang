package com.gh.dl.data

import com.gh.dl.ui.AppState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 浏览的「抓取器」抽象：内置 OkHttp，或调命令行工具（curl / wget）。
 *
 * 第八轮把 curl / wget 从「只用本机的」改成「**App 内置优先，本机的做替补**」：
 * 用户原话「我想的是集成到 APP，结果发现你用的是本机的……wget 本机没有，
 * 那就应该集成到 APP 里才对」。实测确实很多人没有：Android 10+ 起 AOSP 就不再自带
 * GNU wget（只剩 toybox 那份功能极小的 wget），不少机型连 curl 都没有。
 *
 * 内置的做法与 aria2c / libgit2 完全一致（见 [AriaEngine] 的注释）：
 * 把静态编译的可执行文件改名成 `libcurl.so` / `libwget.so` 放进 jniLibs/arm64-v8a/，
 * 安装时由系统解压到 nativeLibraryDir —— SELinux 只允许在那里 exec，
 * 从 filesDir / cacheDir 执行会被直接拒（error=13）。
 *
 * 先说结论，免得后来人误解：**慢的不是 curl**。同一份 CYQawa/YunX 页面实测
 *  - 系统 curl（CLI UA）：0.9s 拿到 330KB
 *  - App 内置 OkHttp（同款 UA、同款 IP 优选）：1.4s
 *  - App 完整一次「抓取 + 渲染」：15.2s ← 用户感觉到的「太慢」
 * 差的 13 秒全在**逐张取图并转 base64**，和用哪个 HTTP 客户端无关（第六轮已把取图
 * 从渲染关键路径上摘掉，见 [GhView.IMG_HOST]）。
 *
 * 那为什么还要接 curl / wget：
 *  1. 系统工具走的是它自己的 DNS / 代理 / TLS 配置，某些机型（挂了全局代理、
 *     或 OkHttp 被证书钉扎库干扰）上反而比 App 直连快，多一条路没有坏处；
 *  2. 用户明确要求，而且这确实让「浏览」和 git/aria2 那几条通道口径一致——
 *     App 里本来就在执行外部二进制（aria2c），机制不是新的。
 *
 * 安全边界（四条，都是必须的）：
 *  - **绝不拼 shell 字符串**：ProcessBuilder 用参数数组，URL 作为一个 argv 传进去，
 *    所以 `; rm -rf` / 反引号 / `$( )` 之类只会成为一个连不上的 URL；
 *  - 只允许 http/https，且 [GhView.isPublicHost] 的内网拦截同样适用于这里
 *    （README 是第三方内容，不能由它决定手机去连哪儿）；
 *  - 输出写到 App 私有缓存目录里的临时文件，不用 shell 重定向，且用完即删；
 *  - **协议限制现在对内置 curl 也生效**（[curlArgs] 里写死的 --proto / --proto-redir）：
 *    以前只有本机那份 curl 需要在意，内置那一份是我们自己挑的静态构建，
 *    支持 file:// gopher:// 这些协议，风险一样存在，参数也就一样要给。
 *    wget 那边不需要额外参数：GNU wget 的实现里重定向只跟 http(s)/ftp，
 *    内置那份还额外加 --no-config（实测用户自己的 .wgetrc 能改掉抓取口径甚至关掉证书校验）。
 *
 * 失败（工具不存在 / 超时 / 非零退出）一律返回 null，由调用方回落内置 OkHttp——
 * 这一层不能让用户的浏览失败。
 */
object Fetcher {

    const val OKHTTP = "okhttp"
    const val CURL = "curl"
    const val WGET = "wget"

    /**
     * 内置二进制的文件名（伪装成 .so 放 jniLibs，安装后在 nativeLibraryDir 里）。
     * 名字里带 `.so` 是为了让打包工具与安装器把它当 native 库处理并解压出来；
     * 内容其实是静态链接的 ELF 可执行文件，不是被 dlopen 的库。
     */
    private const val LIB_CURL = "libcurl.so"
    private const val LIB_WGET = "libwget.so"

    /** 内置 curl 的版本，只用于界面如实标注（不参与任何判断） */
    const val BUNDLED_CURL_VERSION = "8.11.0"
    /** 内置 wget 的版本（1.25.0 —— 和 curl 同理，取的是能拿到源码、
     *  且能静态交叉编译进 APK 的那一版，不是「官网最新」） */
    const val BUNDLED_WGET_VERSION = "1.25.0"

    /** 单次抓取的时间上限（秒）。命令行工具没有 App 的 8s 连接超时，必须自己掐死 */
    private const val TIMEOUT_S = 25L

    /** 响应体大小上限：GitHub 仓库页实测 330KB~690KB，给 8MB 已经非常宽松，
     *  再大就是被重定向到别的东西了（比如某个大文件），直接判失败。 */
    private const val MAX_BODY_BYTES = 8_000_000L

    /**
     * 抓取结果。[truncated] 为真表示响应体达到了 [MAX_BODY_BYTES] 就被停止读取——
     * 「静悄悄给一半内容」比「直接失败」更坏：用户会以为仓库就是长这样，
     * 所以这一位必须一路带到页脚提示里（见 [GhView.fetch]）。
     */
    class Got(val status: Int, val body: String, val truncated: Boolean = false)

    /** 缓存目录由界面层注入（和 [GhView.imageCacheDir] 同源）；没注入就不能用外部工具 */
    @Volatile var tmpDir: File? = null

    /**
     * nativeLibraryDir，由界面层注入（见 [com.gh.dl.ui.SettingsViewModel.initSettings]）。
     * 内置的 libcurl.so / libwget.so 就在这里；没注入时只剩「找本机的」这条路。
     */
    @Volatile var binDir: File? = null

    /**
     * 内置 curl / wget 用的根证书包：与 aria2 复用同一份文件、同一套「按 App 版本重解」的规则
     * （[AriaEngine.caBundleFor] → filesDir/aria2/cacert.pem，122 张根证书）。
     *
     * 为什么内置 curl 一定要显式给 CA：静态编译的 curl 里没有系统 CA 路径可读
     * （Android 的系统信任库在 /apex 的 keystore 里，不是 curl 认的那个 PEM 文件），
     * 不给就只剩两种坏选择 —— 要么放弃证书校验（等于把浏览内容交给中间人，绝不允许），
     * 要么每次 TLS 都失败。给它一份 PEM 才会**继续完整校验**：
     * 实测同一份 curl 对自签证书、过期证书一律 rc=60 拒绝。
     * 本机那份 curl 不需要这个参数（它自带 /system 的 CA 路径），所以只在内置时传。
     */
    @Volatile var cacert: File? = null

    /**
     * 活着的子进程与 OkHttp 请求登记处。
     *
     * 为什么必须登记：页面抓取跑在 [com.gh.dl.ui.BrowseViewModel] 自起的后台线程上，
     * 用户退出界面时那个线程不会因此中断——curl 子进程会继续跑满 25s，
     * 期间的网络、内存、以及它在私有目录里留下的临时文件都还在被这个 App 占着。
     * 有了登记处，[cancelAll] 就能在界面销毁时一次掐掉（这是必须的收尾，不是优化）。
     */
    private val liveProcs = ConcurrentHashMap.newKeySet<Process>()
    private val liveCalls = ConcurrentHashMap.newKeySet<okhttp3.Call>()

    /** 掐掉所有在途请求：销毁子进程、取消 OkHttp call。可在任意线程调用，幂等。 */
    fun cancelAll() {
        liveProcs.toList().forEach { p ->
            runCatching { p.destroy() }          // 先给一次体面的 SIGTERM
            runCatching { p.destroyForcibly() }  // 再立刻补刀，不留恋
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

    /** 这台机器上有没有 App 自带的这份二进制 */
    fun hasBundled(name: String): Boolean = bundledPath(name) != null

    /**
     * 定位内置二进制。目录形态有两种，都得试：
     * 多数机型 nativeLibraryDir 直接就是 `.../lib/arm64`，但也有机型给的是父目录 `.../lib`，
     * 真正的 .so 在 `lib/arm64/` 下面。这个坑 aria2c 早就踩过（见 [AriaEngine.resolve] 的注释），
     * 当时只按一种写，结果 Fetcher 在这类机型上永远「找不到内置的」，静悄悄退回本机那份。
     */
    private fun bundledPath(name: String): String? {
        val lib = libName(name) ?: return null
        val dir = binDir ?: return null
        // **没有根证书就当内置这份不存在**：静态编译的 curl / wget 读不到 Android 的系统信任库，
        // 少了那份 PEM 只有两种选择——放弃证书校验（绝不允许，浏览的是第三方内容），
        // 或者每次 TLS 都失败（表现为「选了 curl 却比不用还慢，因为白跑一趟再回落」）。
        // 返回 null 让 toolPath 去找本机那份（它自带 /system 的 CA 路径），找不到就直接回落 OkHttp。
        // cacert 要等界面侧注入（SettingsViewModel.initSettings），注入前这里也是 null——
        // 而真正的抓取都发生在那之后，不会因此少用内置。
        if (cacert == null) return null
        val abi = runCatching { android.os.Build.SUPPORTED_ABIS.firstOrNull() }.getOrDefault("arm64-v8a")
        for (f in listOf(File(dir, lib), File(dir, "$abi/$lib"))) {
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        return null
    }

    /**
     * 定位工具：**内置优先，本机的做替补**。
     *
     * 顺序这样定的理由：内置那份的版本、编译参数（协议白名单、CA、HTTP 实现）都是已知的，
     * 出问题时在我这边可复现；本机那份各厂商裁过参数子集（Android 10+ 的 wget 基本只剩
     * toybox 的 `-O`/`-T`），传一个它不认的参数就整条通道失效。
     * 本机那份仍然是有价值的替补：某些挂了全局代理的机型上系统工具走自己的 DNS/代理，
     * 反而比 App 直连快，所以设备上没有内置版（比如非 arm64 机型）时照旧能用。
     */
    fun toolPath(name: String): String? = bundledPath(name) ?: BIN.find(name)

    /** 这一档当前用的是「内置」还是「本机」，界面要如实写出来 */
    fun sourceOf(name: String): String = when {
        hasBundled(name) -> "内置"
        BIN.find(name) != null -> "本机"
        else -> "无"
    }

    /**
     * 按 [AppState.fetcher] 的选择抓一个 URL。
     * 返回 null 表示「这条路没走通」，调用方应当回落到内置 OkHttp。
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

    /** 内置那一份才需要显式喂 CA（本机自带系统 CA 路径），返回要追加的参数 */
    private fun caArgsFor(name: String): List<String> =
        if (!hasBundled(name)) emptyList()
        else when (name) {
            // --cacert：让内置 curl 用我们打进 APK 的那份根证书做完整校验
            CURL -> cacert?.let { listOf("--cacert", it.absolutePath) } ?: emptyList()
            // wget 的写法是 --ca-certificate=FILE（等号形式，且 GNU wget 才认）。
            // 有一点必须写清楚，否则以后有人会把这行当成「只信这一份」：wget 的 openssl.c 在
            // 读 --ca-certificate 之前**无条件**先调 SSL_CTX_set_default_verify_paths()，
            // 也就是说这个参数是「往信任库里追加」，不是替换。
            // 那个默认路径是编译期常量（我们这版链接的 Alpine libcrypto 里是 /etc/ssl），
            // Android 上根本没有 /etc/ssl/certs，所以设备上默认库是空的——传进去的这份 PEM
            // 就是唯一锚点，行为和 curl 的 --cacert 一致（fail-closed）。
            // 第九轮实测印证了这个结论：同一份二进制，在有空 /etc/ssl 的挂载命名空间里
            // 用一张自签 bogus CA 连 github.com → rc=5 拒绝；把宿主 /etc/ssl/certs（243 张真根）
            // 露出来，同一命令变成 rc=0 通过。区别只在默认库，参数语义没变。
            WGET -> cacert?.let { listOf("--ca-certificate=" + it.absolutePath) } ?: emptyList()
            else -> emptyList()
        }

    /**
     * [Net.browse] 的派生客户端：多一个**逐跳 SSRF 检查**。
     *
     * 为什么不能只在入口查一次：OkHttp 的 followRedirects 会在我们看不见的地方跟着跳，
     * 「公网仓库页 302 → http://127.0.0.1:xxxx」这种写法可以完全绕过入口检查。
     * 网络拦截器在每一跳（含重定向之后的请求）都会执行，所以在这里复查 host 才拦得住。
     * 只对浏览这条链加，不影响下载主链路（那边的地址由用户自己输入，口径不同）。
     *
     * 每次现取现派生，**不要缓存成 val**：[Net.rebuild] 会因为改代理 / 改优选 IP
     * 而换掉 [Net.browse]，缓存的话这条链会永远用着旧配置（用户改了代理不生效）。
     * newBuilder() 复用同一个连接池与调度线程，派生本身很便宜。
     */
    internal fun guarded(): OkHttpClient =
        Net.browse.newBuilder()
            .addNetworkInterceptor(object : okhttp3.Interceptor {
                override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
                    if (!GhView.isPublicHost(chain.request().url.toString())) {
                        throw java.io.IOException("跳转到了不允许的地址（内网拦截）")
                    }
                    return chain.proceed(chain.request())
                }
            })
            .build()

    /**
     * 内置通道：OkHttp（带 DoH + 真实 IP 优选，见 [Net.browse]）。
     *
     * 注意**不能**直接 `body.string()`：那会把整个响应一次性读进内存，
     * 万一被重定向到一个几百 MB 的东西（或者某个仓库页就是超大），
     * 一个 String 就是一次 OOM。这里按 [MAX_BODY_BYTES] 封顶读，多出来的部分不读，
     * 并把 [Got.truncated] 标起来让界面如实说明。
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

    /** 带上限地读取响应体（见 [okhttp] 的说明） */
    private fun readCapped(resp: Response): Got {
        val stream = resp.body?.byteStream()
            ?: return Got(resp.code, "", false)
        stream.use { input ->
            // 多留 1 字节：读满 cap 之后还能不能读出东西，就是「有没有超出」的判据，
            // 不靠 available()（响应流未必被 Content-Length 预填满，那样它会误报 0）
            val cap = MAX_BODY_BYTES.toInt() + 1
            val buf = ByteArray(64 * 1024)
            val out = java.io.ByteArrayOutputStream(64 * 1024)
            var total = 0
            var over = false
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                // >= 而不是 >：读满 cap（= 上限 + 1）本身就说明已经超了
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
        // -s 静默 -S 仍报错；-L 跟随重定向（GitHub 大量 302）；--fail 不要：
        // 我们需要 403 的响应体来判断「UA 被拦」，所以状态码单独用 -w 打出来
        "curl", "-s", "-S", "-L", "--compressed",
        // **这两条是 curl 专有的风险，必须写死**：
        // curl 支持 file:// / gopher:// / dict:// 这些协议，而 README 里可以放一个
        // 「公网地址 302 跳到 file:///data/data/com.gh.dl/...」的链接。
        // 我们的 SSRF 检查只看**初始**地址，跳完以后 curl 会把 App 私有文件写进
        // 我们正要显示的那个临时文件——等于把私有内容渲染到界面上。
        // --proto 锁死入口协议，--proto-redir 锁死「跳转过后只许是 https」。
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
        // 证书校验保持默认开启：这是要显示给用户的内容，不做中间人让步
        val base = listOf(
            "wget", "-q", "--timeout=8", "--tries=1",
            "--user-agent=" + ua,
            "-O", "@OUT@"
        )
        // 只有内置那份才追加 GNU 专有参数。理由和 curl 反过来：
        // 非 arm64 机型走的是**本机**那份，Android 上它几乎都是 toybox wget，
        // 参数子集小到只认 -q/-O/-T，多写一个不认的参数就整条通道失效。
        // 内置那份是完整 GNU wget 1.25.0，这五条各有必要：
        //  --no-config    不去读 $HOME/.wgetrc / WGETRC：实测用户自己配的那份能塞进
        //                 skip_certificate 之类，会悄悄改掉我们的抓取口径（含关掉校验）；
        //  --max-redirect 掐断重定向链长度，和 curl 那边 --max-redirs 6 对齐；
        //  --no-hsts      不让它往私有目录写 ~/.wget-hsts：一次抓取留一个副作用文件，
        //                 且 HSTS 记录会反过来决定下次用什么协议，行为不稳定；
        //  -S             把响应头打到 stderr（exec 从那儿取状态码，见 [readWgetStatus]），
        //                 和 curl 的 -w %{http_code} 一个目的：403/404 的响应体也要能显示；
        //  --content-on-error 报错页（404 的 GitHub 页面本身就是有用的内容）也落盘，
        //                 否则 wget 遇到 4xx/5xx 直接丢弃响应体，App 就只能看到「失败了」。
        val gnu = if (hasBundled(WGET))
            listOf("--no-config", "--max-redirect=6", "--no-hsts", "-S", "--content-on-error")
        else emptyList()
        return base + gnu + caArgsFor(WGET) + listOf(url)
    }

    /**
     * 从 wget -S 写进 stderr 的响应头里取**最后一次**出现的状态码。
     *
     * 必须是最后一次：跟随重定向时每一跳都会打一行 `HTTP/1.1 301 Moved Permanently`，
     * 取第一行会把「跳到了别处」当成结果。
     * wget 的格式是 `  HTTP/1.1 404 Not Found`（前导两空格），HTTP/2 那侧打成
     * `  HTTP/2 200 OK`，所以正则不能写死版本号。
     * 解析不出来返回 null，由调用方按退出码兜底——绝不猜一个 200 蒙过去。
     */
    internal fun parseHttpStatus(err: String): Int? =
        Regex("HTTP/[0-9.]+\\s+(\\d{3})").findAll(err).lastOrNull()
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

    /**
     * 执行一次。`@OUT@` 占位符换成私有缓存目录里的临时文件，
     * 避免把用户可控的 URL 和文件名拼在一起。
     */
    private fun exec(url: String, ua: String, args: List<String>): Got? {
        val dir = tmpDir ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        val out = File.createTempFile("ghf", ".html", dir)
        try {
            val cmd = args.map { if (it == "@OUT@") out.absolutePath else it }
            // 内置优先、本机替补（见 [toolPath]）；找不到就整条外部通道不试，回落 OkHttp
            val path = toolPath(cmd.first()) ?: return null
            val pb = ProcessBuilder(listOf(path) + cmd.drop(1))
            // stderr **必须有个去处**：curl -s -S 的报错、wget 的告警全写在这条流上。
            // 只读 stdout、任着 stderr 堆在管道里，攒满 64KB 内核缓冲就会把子进程
            // 直接卡在 write 上（表现是「卡到超时」，很难查）。
            // 这里不用 redirectErrorStream(true)：那会把错误文本混进 stdout，
            // 而 stdout 我们靠它读 %{http_code}，混了就解析不出来。
            val err = File.createTempFile("ghf", ".err", dir)
            pb.redirectError(err)
            val p = pb.start()
            liveProcs.add(p)
            try {
                // stdout 只有 curl 的 %{http_code}（几十字节），读满不会阻塞
                val code = p.inputStream.bufferedReader().use { it.readText().trim() }
                if (!p.waitFor(TIMEOUT_S + 5, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    return null
                }
                val exit = p.exitValue()
                val errText = runCatching { err.readText() }.getOrDefault("")
                val size = out.length()
                if (size == 0L) {
                    // 把 stderr 尾巴带进异常文本：外部工具失败最常见的表现就是
                    // 「什么也没返回」，没有这一行日志用户在设置页根本无从判断
                    throw IllegalStateException(
                        "stderr: " + errText.trim().take(160)
                    )
                }
                val over = size > MAX_BODY_BYTES
                val body = if (over) {
                    // 命令行工具没有流式封顶的能力，只能读全量再截——
                    // 但既然超了上限，就只取前面需要的部分，别把几十 MB 字符串留在内存里
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
                // 状态码有两个来源：curl 打在 stdout（-w %{http_code}），
                // wget 没有输出状态码的参数，只能从 -S 写进 stderr 的「HTTP/1.1 404 …」里捞。
                // 不能只按「退出码 0 = 成功」：内置那份加了 --content-on-error，
                // 4xx/5xx 也会落盘但退出码非 0，那样会把有用的报错页当成整条通道失败。
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
                // 超时或被中断的分支也要保证进程没了：destroyForcibly 幂等，
                // 正常退出的进程调用它没有任何副作用
                if (p.isAlive) runCatching { p.destroyForcibly() }
            }
        } finally {
            runCatching { out.delete() }
        }
    }
}

/**
 * 在系统 PATH 里找**本机**的可执行文件（只作为内置二进制的替补，见 [Fetcher.toolPath]）。
 *
 * Android 上 ProcessBuilder 自己会按 PATH 找，但我们想先知道「这台机器到底有没有」，
 * 好在设置页如实显示、并在没有的时候根本不试。
 * 注意不能只查 /system/bin：较新的机型把 curl 放在 /apex/... 或 /vendor/bin，所以补上常见目录。
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
