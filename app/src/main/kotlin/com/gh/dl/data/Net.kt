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
 * 现实情况：GitHub 常被 DNS 污染 + 真实 IP 阻断，所以这里做三件事：
 *  1. 公开 DoH 查询真实 IP，并与系统 DNS 结果对比，判断是否被污染；
 *  2. 内置一份 GitHub 官方服务器的公开 IP 池，逐个做真实 HTTP 探测（TCP+TLS+响应码）；
 *  3. 按域名分别记住最快可用的 IP，只连 GitHub 官方服务器，不经过任何第三方中转。
 */
object Net {

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
        "objects.githubusercontent.com" to null
    )

    @Volatile private var pinned: Map<String, List<InetAddress>> = emptyMap()

    @Volatile var proxyHost: String = ""
    @Volatile var proxyPort: Int = 0

    private var client: OkHttpClient = build()

    /** 给下载器复用同一个客户端（带域名优选 / 代理设置） */
    val http: OkHttpClient get() = client

    private fun build(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            // 加速前缀按 host 分流：GitHub 官方下载/源码才套；api.github.com 直连失败回退；
            // 国内镜像 / Gitee / GitLab 永远不套（修复「无差别套前缀」Bug）。
            .addInterceptor(Accel.interceptor())
        if (proxyHost.isNotBlank() && proxyPort > 0) {
            b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
        }
        b.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val hit = pinned[hostname]
                if (hit != null && hit.isNotEmpty()) return hit
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

    fun rebuild() { client = build() }

    fun get(url: String, token: String? = null): String {
        val b = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "gh-dl-android")
        val tk = authTokenFor(url, token)
        if (tk != null) b.header("Authorization", "Bearer $tk")
        client.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}")
            return body
        }
    }

    /** 取任意网页/文本（镜像站目录页等），不带 GitHub 专用请求头 */
    fun text(url: String): String {
        val b = Request.Builder().url(url).header("User-Agent", "gh-dl-android")
        client.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}")
            return body
        }
    }

    /** 下载二进制内容（zip 等），不按文本解码 */
    fun bytes(url: String, token: String? = null): ByteArray {
        val b = Request.Builder().url(url).header("User-Agent", "gh-dl-android")
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
                if (r.code in 200..399) System.currentTimeMillis() - t0 else null
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
