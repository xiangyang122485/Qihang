package com.gh.dl.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 国内镜像源清单 + 启动探活。
 *
 * 内置 9 个同步了 GitHub Release 的国内镜像站（公开、直连、无中转）：
 * 现有 7 家（清华 / 中科大 / 南大 / 华为云 / 北京理工 / 上海交大 / 华中科技）+
 * 2 个候选（北京外国语大学 / 兰州大学）。候选源是否可用，以启动探活结果为准，不写死。
 *
 * 探活：对 9 个源各发一次 HEAD 请求 {base}torvalds/linux/releases，
 * 取真实往返延迟；不可用的源在「线路诊断」与「解析器→国内镜像」里置灰/不列出。
 */
object MirrorSources {

    /** Src 与 Probe 的公共字段，便于下拉列表统一取 id/name 而不丢类型 */
    interface MirrorItem {
        val id: String
        val name: String
        val base: String
    }

    data class Src(override val id: String, override val name: String, override val base: String) : MirrorItem

    /** 全部 9 个源（顺序即展示顺序） */
    val ALL = listOf(
        Src("tuna", "清华大学", "https://mirrors.tuna.tsinghua.edu.cn/github-release/"),
        Src("ustc", "中科大", "https://mirrors.ustc.edu.cn/github-release/"),
        Src("nju", "南京大学", "https://mirror.nju.edu.cn/github-release/"),
        Src("huawei", "华为云", "https://mirrors.huaweicloud.com/github-release/"),
        Src("bit", "北京理工", "https://mirror.bjtu.edu.cn/github-release/"),
        Src("sjtu", "上海交大", "https://ftp.sjtu.edu.cn/github-release/"),
        Src("hust", "华中科技", "https://mirrors.hust.edu.cn/github-release/"),
        Src("bfsu", "北京外国语大学", "https://mirrors.bfsu.edu.cn/github-release/"),
        Src("lzu", "兰州大学", "https://mirror.lzu.edu.cn/github-release/")
    )

    /** 单个源的探活结果 */
    data class Probe(override val id: String, override val name: String, override val base: String, val ok: Boolean, val ms: Long) : MirrorItem

    /** 探活专用短超时客户端（不复用下载客户端，避免被长超时拖住启动） */
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val TEST_PATH = "torvalds/linux/releases"

    /**
     * 并发对 9 个源发 HEAD，最多等 8 秒。返回每个源的可用状态与延迟（毫秒，失败为 -1）。
     * 在后台线程/协程里调用，不要在主线程直接调。
     */
    fun probeAll(): List<Probe> {
        val pool = Executors.newFixedThreadPool(9)
        val out = ArrayList<Probe>(ALL.size)
        val latch = CountDownLatch(ALL.size)
        val lock = Any()
        for (src in ALL) {
            pool.submit {
                var ok = false
                var ms = -1L
                try {
                    val t0 = System.currentTimeMillis()
                    val req = Request.Builder().url(src.base + TEST_PATH).head()
                        .header("User-Agent", "gh-dl-android").build()
                    probeClient.newCall(req).execute().use { r ->
                        // 拿到 HTTP 响应（含 2xx/3xx/4xx）即视为可达；仅连接级失败算不可用
                        ok = r.code in 100..599
                        ms = System.currentTimeMillis() - t0
                    }
                } catch (e: Exception) {
                    ok = false
                    ms = -1L
                }
                synchronized(lock) { out.add(Probe(src.id, src.name, src.base, ok, ms)) }
                latch.countDown()
            }
        }
        try { latch.await(8, TimeUnit.SECONDS) } catch (e: Exception) { /* 超时也返回已完成的 */ }
        pool.shutdownNow()
        return out.sortedBy { ALL.indexOfFirst { s -> s.id == it.id } }
    }
}
