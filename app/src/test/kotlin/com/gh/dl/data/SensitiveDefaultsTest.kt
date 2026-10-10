package com.gh.dl.data

import com.gh.dl.ui.AppState
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 第九轮的默认值回归测试：敏感功能一律默认关闭。
 *
 * 为什么值得单独占一个用例文件：这些开关的「默认关」不是一次性改动，它同时落在三个地方
 * （AppState 的初始值、SettingsViewModel.initSettings 里 Settings.get 的默认值、
 * 以及 History.enabled 里的 SharedPreferences 默认值）。三处只要有一处漂回 "1"，
 * 表现就是「用户从没打开过，App 却自己开始查第三方 DoH / 自己连镜像站 / 自己存历史」——
 * 这类漂移靠肉眼 review 很难发现，所以在这里用断言钉住。
 *
 * 只测「默认值」这一件事，不去测打开以后行为对不对（那些在 AccelHostRoutingTest /
 * GhViewTest 里已经覆盖了）。
 */
class SensitiveDefaultsTest {

    /** 界面状态层：敏感档全部以关着的状态出生 */
    @Test
    fun sensitiveTogglesStartOff() {
        assertFalse("浏览加速默认必须关", AppState.browseAccelOn)
        assertFalse("下载加速默认必须关", AppState.accelOn)
        assertFalse("自建前缀默认必须为空", AppState.custom.isNotBlank())
        assertFalse("本地历史默认必须关", AppState.historyOn)
        assertFalse("DoH + 真实 IP 优选默认必须关", AppState.dnsPinningOn)
        assertFalse("启动时自动探活镜像源默认必须关", AppState.mirrorProbeOn)
    }

    /**
     * data 层那个镜像开关也必须是 false。
     *
     * Net.dnsPinningOn 与 AppState.dnsPinningOn 是两个变量（data 层读不到 ViewModel），
     * 靠 SettingsViewModel.initSettings 把界面值灌进来。如果这里默认值是 true，
     * 那么在 initSettings 跑完之前的任何一次 pinInBackground 都会偷偷外连——正是第九轮要堵掉的。
     */
    @Test
    fun dataLayerMirrorFlagAlsoStartsOff() {
        assertFalse(Net.dnsPinningOn)
    }

    /**
     * 关着的时候 pinInBackground 必须「立刻回调 false 且不起线程」：
     * 只断言返回值不够，还得确认没往 pinned 表里写东西（写了就说明真的发过 DNS 查询）。
     */
    @Test
    fun pinInBackgroundDoesNothingWhenOff() {
        val called = booleanArrayOf(false)
        val ret = booleanArrayOf(true)
        Net.dnsPinningOn = false
        Net.pinInBackground("github.com") { ok ->
            called[0] = true
            ret[0] = ok
        }
        assertTrue("关掉时必须同步回调", called[0])
        assertFalse("回调参数必须是 false（没有优选成功）", ret[0])
        assertFalse("关掉时不该留下任何已优选的痕迹", Net.hasPinned("github.com"))
    }

    /**
     * 不传回调也不能炸（调用方大多是「顺手补一次优选」，没人等结果）。
     * 顺手把空 host 也钉住：blank host 在开关打开时同样要直接返回，不能起线程去查。
     */
    @Test
    fun pinInBackgroundIsSafeWithoutCallback() {
        Net.dnsPinningOn = false
        Net.pinInBackground("github.com")
        Net.pinInBackground("github.com", null)
        Net.dnsPinningOn = true
        try {
            Net.pinInBackground("   ")
        } finally {
            Net.dnsPinningOn = false
        }
        assertFalse(Net.hasPinned("github.com"))
    }

    /**
     * SharedPreferences 那条路径上的默认值字符串（"0" = 关）。
     * 这一条不连 Android，纯核对源码里写死的默认值文本，改的人一定会撞红。
     *
     * 之所以用读源码的方式而不是直接跑：这三处默认值分散在三个文件里，而单元测试没有
     * Context，拿不到 SharedPreferences。读文本看着糙，但它恰好是「三处必须一致」这个
     * 约定唯一能在 JVM 测试里落地的形式。
     */
    @Test
    fun spDefaultsAreOffStrings() {
        val vm = readMain("ui/SettingsViewModel.kt")
        for (key in listOf("browseAccelOn", "historyOn", "dnsPinningOn", "mirrorProbeOn")) {
            assertTrue(
                "initSettings 里 $key 的默认值必须是 \"0\"",
                Regex("""Settings\.get\(ctx, *"$key", *"0"\)""").containsMatchIn(vm)
            )
        }
        val history = readMain("data/History.kt")
        assertTrue(
            "History.enabled 的 SP 默认值必须是 \"0\"",
            Regex("""Settings\.get\(ctx, *KEY_ON, *"0"\)""").containsMatchIn(history)
        )
    }

    /** 启动时的自动探活必须包在开关里，否则「默认关」只是界面好看 */
    @Test
    fun startupProbeIsGated() {
        val app = readMain("ui/GhApp.kt")
        val i = app.indexOf("probeAll")
        assertTrue("GhApp 里还在调 MirrorSources.probeAll", i >= 0)
        val window = app.substring(maxOf(0, i - 400), minOf(app.length, i + 80))
        assertTrue("启动探活必须在 mirrorProbeOn 判断之内", window.contains("mirrorProbeOn"))
    }

    // ===== 小工具：仓库根在单测里靠 user.dir 定位，gradle 跑单测时就是模块上层 =====
    private fun mainRoot(): java.io.File {
        var d = java.io.File(System.getProperty("user.dir"))
        repeat(4) {
            val cand = java.io.File(d, "app/src/main/kotlin/com/gh/dl")
            if (cand.isDirectory) return d
            d = d.parentFile ?: return java.io.File("")
        }
        return java.io.File("")
    }

    private fun readMain(rel: String): String {
        val f = java.io.File(java.io.File(mainRoot(), "app/src/main/kotlin/com/gh/dl"), rel)
        // 定位不到源码目录时（比如从别的目录跑测试）直接跳过，不给出假的红。
        // 用异常反而会让「测试跑不了」伪装成「默认值错了」，排查起来更费劲。
        if (!f.isFile) throw org.junit.AssumptionViolatedException("找不到 $f，跳过源码核对")
        return f.readText()
    }

    private fun assertTrue(m: String, v: Boolean) = org.junit.Assert.assertTrue(m, v)
}
