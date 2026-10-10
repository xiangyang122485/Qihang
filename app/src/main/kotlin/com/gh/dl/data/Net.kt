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
 * 带 HTTP 状态码的异常：网络层在非 2xx 时抛出，便于上层按错误码分支
 * （例如 Gitee 403/429 限流、GitCode 401 未授权），而不是笼统地当成「没找到结果」。
 */
class HttpException(val code: Int, msg: String) : Exception(msg)

/**
 * 网络层。
 *
 * 现实情况：GitHub 常被 DNS 污染 + 真实 IP 阻断，所以这里能做三件事：
 *  1. 公开 DoH 查询真实 IP，并与系统 DNS 结果对比，判断是否被污染；
 *  2. 内置一份 GitHub 官方服务器的公开 IP 池，逐个做真实 HTTP 探测（TCP+TLS+响应码）；
 *  3. 按域名分别记住最快可用的 IP，只连 GitHub 官方服务器，不经过任何第三方中转。
 *
 * **第九轮：这三件事默认不做。**前两条要把「你在解析 github.com」这件事发给第三方
 * 公共 DoH 服务（dns.alidns.com / doh.pub），第三条会自动外连一批 IP 探活 ——
 * 按用户「只要是敏感的，都默认关闭」的要求，这类行为必须由用户显式开启，
 * 不能出厂就静默跑。开关是 [dnsPinningOn]（设置 → 网络），默认 false。
 * 关掉时本类退化成普通 OkHttp：系统 DNS + 直连，污染环境下会**如实失败**，
 * 而不是偷偷走旁路。用户点「线路诊断」是明确的主动行为，不受本开关限制。
 */
object Net {

    /** 统一的用户标识：OkHttp 各请求与 WebView 资源代取都用它，避免两处 UA 漂移 */
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

    /** 各域名用来探活的轻量路径 */
    private val PROBE_PATH = mapOf(
        "github.com" to "/",
        "api.github.com" to "/zen",
        "codeload.github.com" to "/octocat/Hello-World/zip/refs/heads/master",
        "raw.githubusercontent.com" to "/github/explore/main/README.md",
        // Release/LFS 对象只发签名 URL，没有可预测的公开路径，探活拿 404 是正常的：
        // TLS 校验已在这一步完成，能拿到 HTTP 响应就说明这条 IP 真的通到 GitHub。
        "objects.githubusercontent.com" to "/"
    )

    /** 这些域名没有公开内容，探活只要拿到任意 HTTP 响应就算可达（2xx~4xx） */
    private val PROBE_ANY_CODE = setOf("objects.githubusercontent.com")

    /** 同一批服务器服务的域名组：组内任一域名优选出来，整组复用 */
    private val REUSE_GROUPS = listOf(
        setOf("github.com", "www.github.com"),
        setOf("raw.githubusercontent.com", "objects.githubusercontent.com")
    )

    @Volatile private var pinned: Map<String, List<InetAddress>> = emptyMap()

    /** 正在补探测中的域名，避免同一 host 被并发触发多次 [pinInBackground] */
    private val probing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 「DoH + 真实 IP 优选」总开关，默认 **false**（第九轮：敏感项一律默认关闭）。
     * 只影响**自动、静默**的那条路 [pinInBackground]；
     * 用户主动点的 [diagnose] 不受它限制（那是明示同意的一次性行为）。
     * 由 [com.gh.dl.ui.SettingsViewModel.initSettings] 与设置页的开关写入。
     */
    @Volatile var dnsPinningOn: Boolean = false

    @Volatile var proxyHost: String = ""
    @Volatile var proxyPort: Int = 0

    private var client: OkHttpClient = build(true)

    /** 给下载器复用同一个客户端（带域名优选 / 代理设置 / 加速前缀分流） */
    val http: OkHttpClient get() = client

    /**
     * 「浏览」页专用客户端：同样享受 DoH + 真实 IP 优选与代理设置，但**不挂** [Accel.interceptor]。
     *
     * 为什么不套前缀（真机截图证实）：闪电用的前缀是 gh-proxy 风格的**文件**中转，
     * 只认 /raw、/releases/download、/archive；把仓库主页 URL 交给它，它判断不出是文件，
     * 就返回**它自己的首页**（标题「GitHub加速下载代理」+ 它的 logo 和广告）。
     * 网页本来就该直连官方，而它真正需要的是「绕开 DNS 污染」——那正是本客户端里 IP 优选的作用。
     */
    private var browseClient: OkHttpClient = build(false)
    val browse: OkHttpClient get() = browseClient

    private fun build(accel: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
        if (accel) {
            // 加速前缀按 host 分流：GitHub 官方下载/源码才套；api.github.com 直连失败回退；
            // 国内镜像 / Gitee / GitLab 永远不套（修复「无差别套前缀」Bug）。
            b.addInterceptor(Accel.interceptor())
        }
        if (proxyHost.isNotBlank() && proxyPort > 0) {
            b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
        }
        b.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val hit = pinned[hostname]
                if (hit != null && hit.isNotEmpty()) return hit
                // 同组域名共用同一批服务器（例如 raw/objects 都是 GitHub 的同一组 CDN 段）。
                // objects.githubusercontent.com 结尾是 .githubusercontent.com，靠下面的父域
                // 后缀规则匹配不到，Release/LFS 大文件就会退回系统 DNS —— 这里显式按组复用。
                REUSE_GROUPS.firstOrNull { it.contains(hostname) }?.let { g ->
                    val ips = g.flatMap { pinned[it].orEmpty() }.distinct()
                    if (ips.isNotEmpty()) return ips
                }
                // 同一批服务器服务所有 *.github.com，可复用父域结果
                for ((h, ips) in pinned) {
                    if (ips.isEmpty()) continue
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

    /**
     * 该域名走 [browse]/[http] 时能否拿到优选 IP——判定口径必须和上面 dns() 的复用规则一致，
     * 否则浏览页会「明明能用却以为不能用」，白跑一次探测还不接管。
     * 三层：精确命中 → 同组复用 → 父域后缀复用。
     */
    fun hasPinned(host: String): Boolean {
        if (pinned[host]?.isNotEmpty() == true) return true
        REUSE_GROUPS.firstOrNull { it.contains(host) }?.let { g ->
            if (g.any { pinned[it]?.isNotEmpty() == true }) return true
        }
        return pinned.any { (h, ips) -> ips.isNotEmpty() && host.endsWith(".$h") }
    }

    /**
     * 后台为单个域名补一次真实 IP 优选（DoH 解析 → 候选逐个真实 HTTPS 探测 → 记最快的）。
     *
     * 为什么浏览需要它：WebView 用自己的网络栈、只认系统 DNS，github.com 被污染时页面直接打不开；
     * 而本 App 的「DoH + IP 优选」只对 OkHttp 生效。于是浏览的加速方式就是把 GitHub 域名的请求
     * 交给 [browse] 代取，前提是这里先探到可用 IP。
     *
     * **必须异步**：本方法自己起线程，[onDone] 也在该线程回调（调用方要 post 回界面线程）。
     * 探测期间本次加载仍走 WebView 原生，探完让**下一次**加载受益，由调用方决定是否重载。
     * 只探一个域名、候选 4 个；同一域名并发只跑一次。
     */
    fun pinInBackground(host: String, onDone: ((Boolean) -> Unit)? = null) {
        // 默认关闭：不替用户静默向第三方 DoH 查询域名、也不自动外连探活。
        // 关掉时调用方拿不到 onDone 回调也不该有事——它只是「顺手补一次优选」。
        if (!dnsPinningOn) { onDone?.invoke(false); return }
        if (host.isBlank() || hasPinned(host) || !probing.add(host)) return
        Thread {
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
                            } catch (e: Exception) {
                            } finally { latch.countDown() }
                        }
                    }
                    latch.await(12, TimeUnit.SECONDS)
                    pool.shutdownNow()
                    // 等它真的散掉。shutdownNow 只是发 interrupt，而 DNS/连接这类调用未必可中断，
                    // 不等的话每次探测都可能留下 1~2 个还在跑的线程；
                    // 用户在设置里反复切镜像域名时就是「线程越积越多」。
                    // 2 秒是给不听的线程一个台阶，超时就算了——不拿它换主流程的响应速度。
                    runCatching { pool.awaitTermination(2, TimeUnit.SECONDS) }
                    val ip = best.get()
                    if (ip != null) {
                        // 只补这一个域名，别把已有结果冲掉
                        pinned = HashMap(pinned).apply { put(host, listOf(InetAddress.getByName(ip))) }
                        rebuild()
                        ok = true
                    }
                }
            } catch (e: Exception) {
            } finally {
                probing.remove(host)
                onDone?.invoke(ok)
            }
        }.start()
    }

    fun get(url: String, token: String? = null): String {
        val b = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", UA)
        val tk = authTokenFor(url, token)
        if (tk != null) b.header("Authorization", "Bearer $tk")
        client.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}")
            return body
        }
    }

    /**
     * 取任意网页/文本（镜像站目录页等），不带 GitHub 专用请求头。
     * [authHeader] 是「已经由调用方确认目标域名」的完整头值（如 GitCode 的
     * `Bearer <token>`）——它不经 [authTokenFor] 过滤，所以调用方必须自己保证
     * url 就是该令牌的归属域名，绝不能把 GitHub PAT 从这里发给第三方。
     */
    fun text(url: String, authHeader: String? = null): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        if (!authHeader.isNullOrBlank()) b.header("Authorization", authHeader)
        client.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}")
            return body
        }
    }

    /** 下载二进制内容（zip 等），不按文本解码 */
    fun bytes(url: String, token: String? = null): ByteArray {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        val tk = authTokenFor(url, token)
        if (tk != null) b.header("Authorization", "Bearer $tk")
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    /**
     * 令牌只有在请求真正发往 GitHub 官方域名时才带上去。
     * 走公共镜像 / 加速代理（ghproxy 等第三方中转）时一律剥离，
     * 避免把用户的 PAT 泄露给第三方服务器——这是合规红线。
     */
    private fun authTokenFor(url: String, token: String?): String? {
        if (token.isNullOrBlank()) return null
        val host = runCatching { URL(url).host }.getOrDefault("")
        val official = host == "api.github.com" || host == "github.com" ||
            host.endsWith(".github.com") || host == "raw.githubusercontent.com" ||
            host == "objects.githubusercontent.com" || host == "codeload.github.com"
        return if (official) token else null
    }

    /** 公共 DoH，拿未受干扰的 A 记录 */
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
                    if (ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) out.add(ip)
                }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) { /* 换下一个 */ }
        }
        return emptyList()
    }

    /** 用指定 IP 访问 host，成功返回耗时（真实完成 HTTP 往返） */
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

    /** 全域名诊断：DNS 对比 → 候选 IP → 并发真实 HTTP 探测 → 记录最快 */
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
                        } catch (e: Exception) {
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
                    polluted -> "系统 DNS 被污染（返回的是别人的 IP），真实 IP 也连不上"
                    else -> "真实 IP 全部无法建立连接"
                }
                out.add(Diag(host, sysIps, dohIps, best, bestMs.get(), verdict))
            }
        }
        try {
            pool.invokeAll(jobs, 90, TimeUnit.SECONDS)
        } catch (e: Exception) { }
        pool.shutdownNow()
        pinned = HashMap(map)
        rebuild()
        return out.sortedBy { HOSTS.indexOf(it.host) }
    }

    /** 用浏览器同款方式探测当前网络能否直连 GitHub（不做任何改写） */
    fun plainCheck(): String {
        return try {
            val t0 = System.currentTimeMillis()
            client.newCall(Request.Builder().url("https://api.github.com/zen").build())
                .execute().use { r ->
                    if (r.isSuccessful) "可直连 · ${System.currentTimeMillis() - t0}ms" else "HTTP ${r.code}"
                }
        } catch (e: Exception) {
            "无法直连（${e.message?.take(60) ?: "超时"}）"
        }
    }
}
