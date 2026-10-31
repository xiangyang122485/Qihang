package com.gh.dl.data

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URL
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 带 HTTP 状态码的异常：网络层在非 2xx 时抛出，供上层按错误码分支处理
 * （如 Gitee 403/429 限流、GitCode 401 未授权），而非统一视为「未找到结果」。
 */
class HttpException(val code: Int, msg: String) : Exception(msg)

/**
 * 网络层。
 *
 * GitHub 的域名解析结果可能不可信、真实 IP 可能无法直连，因此本类提供以下功能：
 *  1. 通过公共 DoH 查询真实 IP，并与系统 DNS 结果对比，判断解析是否被污染；
 *  2. 内置 GitHub 官方服务器的公开 IP 池，逐个执行真实 HTTP 探测（TCP+TLS+响应码）；
 *  3. 按域名分别记录最快可用 IP，仅连接 GitHub 官方服务器，不经过任何第三方中转。
 *
 * **当前实现：上述三项功能默认不启用。**前两项需将域名解析请求发送至第三方
 * 公共 DoH 服务（dns.alidns.com / doh.pub），第三项会自动外连一批 IP 执行探活。
 * 按用户对敏感行为的约束要求，此类功能必须由用户显式开启，
 * 不得在默认配置下静默执行。开关为 [dnsPinningOn]（设置 → 网络），默认 false。
 * 关闭时本类退化为普通 OkHttp：系统 DNS + 直连，在域名解析不可信的环境下会**如实失败**，
 * 而非通过旁路绕过。用户点击「线路诊断」属于明确的主动行为，不受此开关限制。
 */
object Net {

    /** 统一的用户标识：OkHttp 各请求与 WebView 资源代取均使用此值，避免两处 UA 不一致 */
    const val UA = "gh-dl-android"

    val HOSTS = listOf(
        "github.com", "api.github.com", "codeload.github.com",
        "raw.githubusercontent.com", "objects.githubusercontent.com"
    )

    /** GitHub 官方服务器的公开 IP 记录 */
    private val POOL: Map<String, List<String>> = mapOf(
        "github.com" to listOf(
            "20.205.243.166", "140.82.112.3", "140.82.112.4", "140.82.113.3",
            "140.82.113.4", "140.82.114.3", "140.82.114.4", "140.82.116.3",
            "140.82.116.4", "140.82.121.3", "140.82.121.4"
        ),
        "api.github.com" to listOf(
            "20.205.243.168", "140.82.112.5", "140.82.112.6", "140.82.113.5",
            "140.82.113.6", "140.82.114.5", "140.82.114.6", "140.82.116.5",
            "140.82.116.6", "140.82.121.5", "140.82.121.6"
        ),
        "codeload.github.com" to listOf(
            "20.205.243.165", "140.82.112.9", "140.82.112.10", "140.82.113.9",
            "140.82.113.10", "140.82.121.9", "140.82.121.10"
        ),
        "raw.githubusercontent.com" to listOf(
            "185.199.108.133", "185.199.109.133", "185.199.110.133", "185.199.111.133"
        ),
        "objects.githubusercontent.com" to listOf(
            "185.199.108.133", "185.199.109.133", "185.199.110.133", "185.199.111.133"
        )
    )

    /** 各域名用于探活的轻量路径 */
    private val PROBE_PATH = mapOf(
        "github.com" to "/",
        "api.github.com" to "/zen",
        "codeload.github.com" to "/octocat/Hello-World/zip/refs/heads/master",
        "raw.githubusercontent.com" to "/github/explore/main/README.md",
        // Release/LFS 对象仅通过签名 URL 访问，无可预测的公开路径，探活返回 404 属正常现象：
        // TLS 校验已在此步骤完成，能获取 HTTP 响应即表明该 IP 可达 GitHub。
        "objects.githubusercontent.com" to "/"
    )

    /** 这些域名无可公开访问的内容，探活时获取任意 HTTP 响应即视为可达（2xx~4xx） */
    private val PROBE_ANY_CODE = setOf("objects.githubusercontent.com")

    /** 同一批服务器服务的域名组：组内任一域名优选成功后，整组复用结果 */
    private val REUSE_GROUPS = listOf(
        setOf("github.com", "www.github.com"),
        setOf("raw.githubusercontent.com", "objects.githubusercontent.com")
    )

    /**
     * 域名 → 优选真实 IP，另以 [pinAt] 记录写入时间。
     *
     * 该表在 OkHttp 的 DNS 回调线程、探测线程与界面线程之间共享。
     * 原「整表复制后替换引用」写法在并发写入时会丢失更新；
     * 且条目永不过期会让网络切换、CDN 轮换之后仍长期绑定失效 IP，
     * 表现为「加速前缀已开启但速度未改善，杀掉应用重新进入才恢复」。
     */
    private val pinned = java.util.concurrent.ConcurrentHashMap<String, List<InetAddress>>()
    private val pinAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 优选结果有效期。过期后重新探测，避免长期绑定已劣化的边缘节点 */
    private const val PIN_TTL_MS = 5L * 60 * 1000

    /** 正在进行补充探测的域名，防止同一 host 被并发触发多次 [pinInBackground] */
    private val probing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** DoH 应答中的 A 记录必须是点分 IPv4 */
    private val IPV4 = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

    /**
     * 「DoH + 真实 IP 优选」总开关，默认 **false**（敏感项一律默认关闭）。
     * 仅影响**自动、静默**执行的路径 [pinInBackground]；
     * 用户主动点击的 [diagnose] 不受此限制（属于明示同意的一次性行为）。
     * 由 [com.gh.dl.ui.SettingsViewModel.initSettings] 与设置页的开关写入。
     */
    @Volatile var dnsPinningOn: Boolean = false

    @Volatile var proxyHost: String = ""
    @Volatile var proxyPort: Int = 0

    private var client: OkHttpClient = build(true)

    /** 供下载器复用同一客户端（含域名优选 / 代理设置 / 加速前缀分流） */
    val http: OkHttpClient get() = client

    /**
     * 「浏览」页专用客户端：同样支持 DoH + 真实 IP 优选与代理设置，但**不挂载** [Accel.interceptor]。
     *
     * 浏览页不可添加加速前缀的原因（真机截图证实）：加速前缀采用 gh-proxy 风格的**文件**中转，
     * 仅识别 /raw、/releases/download、/archive 路径；将仓库主页 URL 提交给该中转时，
     * 其无法识别为文件，直接返回**中转站自身首页**（标题「GitHub加速下载代理」+ logo 与广告）。
     * 网页应当直连官方，而实际需要解决的是域名解析不可信问题——这正是本客户端 IP 优选的作用。
     */
    private var browseClient: OkHttpClient = build(false)
    val browse: OkHttpClient get() = browseClient

    private fun build(accel: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
        if (accel) {
            // 加速前缀按 host 分流：GitHub 官方域名（含 api.github.com）统一添加；
            // 中转站不支持 API 时，由调用方改用 direct=true 的 [browse] 客户端重试；
            // 国内镜像 / Gitee / GitLab 永不添加（修复「无差别添加前缀」缺陷）。
            b.addInterceptor(Accel.interceptor())
        }
        if (proxyHost.isNotBlank() && proxyPort > 0) {
            b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
        }
        b.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val hit = fresh(hostname)
                if (hit != null) return hit
                // 同组域名共用同一批服务器（例如 raw/objects 都是 GitHub 的同一组 CDN 段）。
                // objects.githubusercontent.com 结尾是 .githubusercontent.com，靠下面的父域
                // 后缀规则匹配不到，Release/LFS 大文件就会退回系统 DNS —— 这里显式按组复用。
                REUSE_GROUPS.firstOrNull { it.contains(hostname) }?.let { g ->
                    val ips = g.flatMap { fresh(it).orEmpty() }.distinct()
                    if (ips.isNotEmpty()) return ips
                }
                // 同一批服务器服务所有 *.github.com，可复用父域结果
                for ((h, ips) in pinned) {
                    if (ips.isEmpty() || isExpired(h)) continue
                    if (hostname.endsWith("." + h)) return ips
                }
                return Dns.SYSTEM.lookup(hostname)
            }
        })
        return b.build()
    }

    fun rebuild() {
        client = build(true)
        browseClient = build(false)
    }

    /** 记录某域名的优选结果与时间；空结果等同于清除 */
    private fun pin(host: String, ips: List<InetAddress>) {
        if (ips.isEmpty()) {
            unpin(host); return
        }
        pinned[host] = ips
        pinAt[host] = System.currentTimeMillis()
    }

    /** 清除某域名的优选结果：连接失败后调用，下一次请求回退系统 DNS 或重新探测 */
    fun unpin(host: String) {
        pinned.remove(host)
        pinAt.remove(host)
    }

    /**
     * 纯函数：判断某条优选记录是否已过期。
     * [pinnedAt] 为记录写入时间（null 表示无记录），[now] 为当前时间，[ttlMs] 为有效期。
     * 从 [isExpired] 中提取，以便 JVM 单元测试覆盖 TTL 边界行为。
     */
    internal fun isExpiredAt(pinnedAt: Long?, now: Long, ttlMs: Long): Boolean {
        if (pinnedAt == null) return false
        return now - pinnedAt > ttlMs
    }

    private fun isExpired(host: String): Boolean {
        val at = pinAt[host]
        return isExpiredAt(at, System.currentTimeMillis(), PIN_TTL_MS)
    }

    /** 未过期的优选结果；已过期则清除并返回 null */
    private fun fresh(host: String): List<InetAddress>? {
        val ips = pinned[host]
        if (ips == null || ips.isEmpty()) return null
        if (isExpired(host)) {
            unpin(host)
            return null
        }
        return ips
    }

    /**
     * 丢弃全部优选结果并断开既有连接。
     * WiFi 与移动数据切换后，上一张网络探测到的真实 IP 与已建立的连接通常不可复用；
     * 仅重建客户端而保留优选表，DNS 回调仍会命中失效 IP。
     * evictAll 只回收空闲连接，进行中的传输不受影响。
     */
    fun clearPins() {
        pinned.clear()
        pinAt.clear()
        probing.clear()
        client.connectionPool.evictAll()
        browseClient.connectionPool.evictAll()
    }

    /**
     * 判断该域名通过 [browse]/[http] 访问时能否获取优选 IP——判定逻辑必须与上方 dns() 的复用规则一致，
     * 否则浏览页会出现「实际可用但判断为不可用」的情况，导致探测无效且未接管请求。
     * 三层判断：精确命中 → 同组复用 → 父域后缀复用。
     */
    fun hasPinned(host: String): Boolean {
        if (fresh(host) != null) return true
        REUSE_GROUPS.firstOrNull { it.contains(host) }?.let { g ->
            if (g.any { fresh(it) != null }) return true
        }
        return pinned.any { (h, ips) -> ips.isNotEmpty() && host.endsWith(".$h") }
    }

    /**
     * 后台为单个域名执行一次真实 IP 优选（DoH 解析 → 候选 IP 逐个真实 HTTPS 探测 → 记录最快者）。
     *
     * 浏览功能需要此方法的原因：WebView 使用自身网络栈、仅识别系统 DNS，当 github.com 域名解析不可信时页面无法打开；
     * 而本应用的「DoH + IP 优选」仅对 OkHttp 生效。因此浏览的加速方式是将 GitHub 域名请求
     * 交由 [browse] 代取，前提是本方法已预先探测到可用 IP。
     *
     * **必须异步调用**：本方法自行创建线程，[onDone] 也在该线程回调（调用方需 post 回界面线程）。
     * 探测期间本次加载仍走 WebView 原生网络栈，探测完成后**下一次**加载方可受益，是否重载由调用方决定。
     * 每次仅探测一个域名、候选 4 个 IP；同一域名并发调用仅执行一次。
     */
    fun pinInBackground(host: String, onDone: ((Boolean) -> Unit)? = null) {
        // 默认关闭：不替用户静默向第三方 DoH 查询域名、也不自动外连探活。
        // 关闭时调用方无法获取 onDone 回调，但不影响功能——本方法仅为「补充执行一次优选」。
        if (!dnsPinningOn) { onDone?.invoke(false); return }
        if (host.isBlank() || hasPinned(host) || !probing.add(host)) return  // hasPinned 内含过期判定
        Threads.start("优选IP探测") {
            var ok = false
            try {
                val cands = (doh(host) + POOL[host].orEmpty()).distinct().take(4)
                if (cands.isNotEmpty()) {
                    val best = AtomicReference<String?>(null)
                    val bestMs = AtomicLong(Long.MAX_VALUE)
                    val latch = CountDownLatch(cands.size)
                    val pool = Executors.newFixedThreadPool(cands.size)
                    for (ip in cands) {
                        pool.submit {
                            try {
                                val ms = probe(ip, host, PROBE_PATH[host])
                                if (ms != null && ms < bestMs.get()) { bestMs.set(ms); best.set(ip) }
                            } catch (_: Exception) {
                                // 单个候选 IP 探测失败：继续下一候选
                            } finally { latch.countDown() }
                        }
                    }
                    latch.await(12, TimeUnit.SECONDS)
                    pool.shutdownNow()
                    // 等待线程池实际终止。shutdownNow 仅发送 interrupt 信号，而 DNS/连接等调用未必可中断，
                    // 若不等待，每次探测可能遗留 1~2 个仍在运行的线程；
                    // 用户在设置中反复切换镜像域名时会导致「线程持续累积」。
                    // 2 秒为不可中断线程提供的等待时间，超时则放弃——不以牺牲主流程响应速度为代价。
                    runCatching { pool.awaitTermination(2, TimeUnit.SECONDS) }
                    val ip = best.get()
                    if (ip != null) {
                        // 仅补充该域名的结果，不覆盖已有数据
                        pin(host, listOf(InetAddress.getByName(ip)))
                        rebuild()
                        ok = true
                    }
                }
            } catch (_: Exception) {
                // 探测失败仅表示该 IP 当前不可用，保留原有优选结果
            } finally {
                probing.remove(host)
                onDone?.invoke(ok)
            }
        }
    }

    /**
     * 选择本次请求使用的客户端。
     *
     * [direct] = true 时使用不挂载加速前缀拦截器的 [browse] 客户端：
     * 加速前缀为文件中转，部分中转站不支持 api.github.com，此时需要绕开前缀直连重试。
     * [timeoutMs] > 0 时对该次调用设置整体超时（callTimeout），用于解析等
     * 需要快速失败并切换线路的场景；单个连接/读取仍受客户端默认值约束。
     */
    private fun clientFor(direct: Boolean, timeoutMs: Long): OkHttpClient {
        val base = if (direct) browseClient else client
        if (timeoutMs <= 0) return base
        return base.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
    }

    fun get(
        url: String,
        token: String? = null,
        direct: Boolean = false,
        timeoutMs: Long = 0
    ): String {
        val b = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", UA)
        val tk = authTokenFor(url, token)
        if (tk != null) b.header("Authorization", "Bearer $tk")
        clientFor(direct, timeoutMs).newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}")
            return body
        }
    }

    /**
     * 获取任意网页/文本（如镜像站目录页等），不携带 GitHub 专用请求头。
     * [authHeader] 为「已由调用方确认目标域名」的完整头值（如 GitCode 的
     * `Bearer <token>`）——该值不经 [authTokenFor] 过滤，因此调用方必须自行确保
     * url 属于该令牌的归属域名，严禁将 GitHub PAT 通过此方法发送至第三方。
     */
    fun text(
        url: String,
        authHeader: String? = null,
        direct: Boolean = false,
        timeoutMs: Long = 0
    ): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        if (!authHeader.isNullOrBlank()) b.header("Authorization", authHeader)
        clientFor(direct, timeoutMs).newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}")
            return body
        }
    }

    /** 下载二进制内容（如 zip 等），不按文本解码 */
    fun bytes(url: String, token: String? = null, direct: Boolean = false): ByteArray {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        val tk = authTokenFor(url, token)
        if (tk != null) b.header("Authorization", "Bearer $tk")
        clientFor(direct, 0).newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    /**
     * 令牌仅在请求实际发往 GitHub 官方域名时携带。
     * 访问公共镜像 / 加速代理（ghproxy 等第三方中转）时一律剥离，
     * 防止用户 PAT 泄露给第三方服务器——此为合规要求。
     */
    private fun authTokenFor(url: String, token: String?): String? {
        if (token.isNullOrBlank()) return null
        val host = runCatching { URL(url).host }.getOrDefault("")
        val official = host == "api.github.com" || host == "github.com" ||
            host.endsWith(".github.com") || host == "raw.githubusercontent.com" ||
            host == "objects.githubusercontent.com" || host == "codeload.github.com"
        return if (official) token else null
    }

    /** 公共 DoH，获取未受干扰的 A 记录 */
    fun doh(host: String): List<String> {
        for (server in listOf("https://dns.alidns.com/resolve", "https://doh.pub/resolve")) {
            try {
                val txt = client.newCall(
                    Request.Builder().url("$server?name=$host&type=A").build()
                ).execute().use { it.body?.string().orEmpty() }
                val arr = JSONObject(txt).optJSONArray("Answer") ?: continue
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val ip = arr.optJSONObject(i)?.optString("data").orEmpty()
                    if (ip.matches(IPV4)) out.add(ip)
                }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) { /* 尝试下一个 */ }
        }
        return emptyList()
    }

    /** 使用指定 IP 访问 host，成功时返回耗时（完成真实 HTTP 往返） */
    private fun probe(ip: String, host: String, path: String?): Long? {
        if (path == null) return null
        return try {
            val c = OkHttpClient.Builder()
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        listOf(InetAddress.getByName(ip))
                })
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
            val t0 = System.currentTimeMillis()
            c.newCall(Request.Builder().url("https://$host$path").build()).execute().use { r ->
                val reachable = if (host in PROBE_ANY_CODE) r.code in 200..499 else r.code in 200..399
                if (reachable) System.currentTimeMillis() - t0 else null
            }
        } catch (e: Exception) { null }
    }

    data class Diag(
        val host: String,
        val sysIps: List<String>,
        val dohIps: List<String>,
        val best: String?,
        val ms: Long,
        val verdict: String
    )

    /** 全域名诊断：DNS 对比 → 候选 IP → 并发真实 HTTP 探测 → 记录最快 IP */
    fun diagnose(): List<Diag> {
        val pool = Executors.newFixedThreadPool(10)
        val out = Collections.synchronizedList(ArrayList<Diag>())
        val map = ConcurrentHashMap<String, List<InetAddress>>()

        val jobs = HOSTS.map { host ->
            Callable {
                val sysIps = try {
                    InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
                } catch (e: Exception) { emptyList() }
                val dohIps = doh(host)
                val cands = (dohIps + POOL[host].orEmpty()).distinct().take(6)

                val bestIp = AtomicReference<String?>(null)
                val bestMs = AtomicLong(Long.MAX_VALUE)
                val latch = CountDownLatch(cands.size)
                for (ip in cands) {
                    pool.submit {
                        try {
                            val ms = probe(ip, host, PROBE_PATH[host])
                            if (ms != null && ms < bestMs.get()) {
                                bestMs.set(ms)
                                bestIp.set(ip)
                            }
                        } catch (_: Exception) {
                            // 单个候选 IP 探测失败：继续下一候选
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                latch.await(25, TimeUnit.SECONDS)

                val best = bestIp.get()
                if (best != null) map[host] = listOf(InetAddress.getByName(best))
                val polluted = sysIps.isNotEmpty() && dohIps.isNotEmpty() &&
                    sysIps.none { dohIps.contains(it) }
                val verdict = when {
                    best != null -> "可用 $best · ${bestMs.get()}ms"
                    sysIps.isEmpty() && dohIps.isEmpty() -> "解析失败"
                    polluted -> "系统 DNS 解析结果不可信（返回非预期 IP），真实 IP 亦无法连接"
                    else -> "真实 IP 均无法建立连接"
                }
                out.add(Diag(host, sysIps, dohIps, best, bestMs.get(), verdict))
            }
        }
        try {
            pool.invokeAll(jobs, 90, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // 超时：返回已完成域名的结果，未完成域名保持原状态
        }
        pool.shutdownNow()
        // 合并而非整体替换：诊断只覆盖部分域名，整体替换会丢弃其余域名的既有结果，
        // 使这些域名退回系统 DNS，表现为「做过一次诊断后下载变慢」
        for ((h, ips) in map) pin(h, ips)
        rebuild()
        return out.sortedBy { HOSTS.indexOf(it.host) }
    }

    /**
     * 使用浏览器同款方式探测当前网络能否直连 GitHub（不执行任何改写）。
     *
     * 必须使用 [browseClient]：它没有挂载 [Accel.interceptor]。
     * 旧实现使用 [client]，在加速开启时会把 api.github.com 也套上前缀，
     * 于是「直连探测」实际走的是中转站，诊断结果与用户所选线路不符。
     */
    fun plainCheck(): String {
        return try {
            val t0 = System.currentTimeMillis()
            browseClient.newCall(
                Request.Builder().url("https://api.github.com/zen")
                    .header("User-Agent", UA).build()
            ).execute().use { r ->
                    if (r.isSuccessful) "可直连 · ${System.currentTimeMillis() - t0}ms" else "HTTP ${r.code}"
                }
        } catch (e: Exception) {
            "无法直连（${e.message?.take(60) ?: "超时"}）"
        }
    }
}
