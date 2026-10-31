package com.gh.dl.data

import com.gh.dl.ui.AppState
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 敏感功能默认值回归测试：所有敏感功能一律默认关闭。
 *
 * 这些开关的「默认关闭」状态同时约束三个位置：AppState 的初始值、
 * SettingsViewModel.initSettings 中 Settings.get 的默认值、
 * 以及 History.enabled 中的 SharedPreferences 默认值。三处中任一处偏离为 "1"，
 * 将导致应用在用户未开启的情况下自行发起第三方 DoH 查询、连接镜像站或记录历史。
 * 此类漂移难以通过代码审查发现，因此使用断言固定预期行为。
 *
 * 本测试仅覆盖「默认值」，开启后的行为由 AccelHostRoutingTest / GhViewTest 覆盖。
 */
class SensitiveDefaultsTest {

    /** 界面状态层：所有敏感开关初始状态均为关闭 */
    @Test
    fun sensitiveTogglesStartOff() {
        assertFalse("敏感开关默认必须为关闭：浏览加速", AppState.browseAccelOn)
        assertFalse("敏感开关默认必须为关闭：下载加速", AppState.accelOn)
        assertFalse("敏感开关默认必须为空：自建前缀", AppState.custom.isNotBlank())
        assertFalse("敏感开关默认必须为关闭：本地历史", AppState.historyOn)
        assertFalse("敏感开关默认必须为关闭：DoH + 真实 IP 优选", AppState.dnsPinningOn)
        assertFalse("敏感开关默认必须为关闭：启动时自动探活镜像源", AppState.mirrorProbeOn)
    }

    /**
     * data 层镜像开关同样必须为 false。
     *
     * Net.dnsPinningOn 与 AppState.dnsPinningOn 为两个独立变量（data 层无法读取 ViewModel），
     * 由 SettingsViewModel.initSettings 将界面值同步至 data 层。若此处默认值为 true，
     * 则 initSettings 执行前的任何 pinInBackground 调用将产生未授权的外部连接。
     */
    @Test
    fun dataLayerMirrorFlagAlsoStartsOff() {
        assertFalse(Net.dnsPinningOn)
    }

    /**
     * 关闭状态下 pinInBackground 必须「立即回调 false 且不创建线程」：
     * 仅断言返回值不足，还需确认未向 pinned 表写入数据（写入即表明实际发起了 DNS 查询）。
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
        assertTrue("关闭时必须同步回调", called[0])
        assertFalse("回调参数必须为 false（优选未成功）", ret[0])
        assertFalse("关闭时不应留下任何已优选的记录", Net.hasPinned("github.com"))
    }

    /**
     * 不传回调时不应抛出异常（调用方多为「附带执行一次优选」，无需等待结果）。
     * 同时约束空白 host 的行为：空白 host 在开关开启时同样应直接返回，不应创建查询线程。
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
     * SharedPreferences 路径上的默认值字符串（"0" 表示关闭）。
     * 此测试不依赖 Android 环境，直接核对源码中硬编码的默认值文本，修改时必然触发断言失败。
     *
     * 采用源码文本核对方式的原因：三处默认值分散在三个文件中，而单元测试无
     * Context，无法访问 SharedPreferences。文本核对虽显粗糙，但恰好是「三处必须一致」
     * 这一约束在 JVM 测试中唯一可落地的形式。
     */
    @Test
    fun spDefaultsAreOffStrings() {
        val vm = readMain("ui/SettingsViewModel.kt")
        for (key in listOf("browseAccelOn", "historyOn", "dnsPinningOn", "mirrorProbeOn")) {
            assertTrue(
                "initSettings 中 $key 的 SharedPreferences 默认值必须为 \"0\"",
                Regex("""Settings\.get\(ctx, *"$key", *"0"\)""").containsMatchIn(vm)
            )
        }
        val history = readMain("data/History.kt")
        assertTrue(
            "History.enabled 的 SharedPreferences 默认值必须为 \"0\"",
            Regex("""Settings\.get\(ctx, *KEY_ON, *"0"\)""").containsMatchIn(history)
        )
    }

    /** 启动时的自动探活必须受开关约束，否则「默认关闭」仅体现于界面 */
    @Test
    fun startupProbeIsGated() {
        val app = readMain("ui/GhApp.kt")
        val i = app.indexOf("probeAll")
        assertTrue("GhApp 中应存在 MirrorSources.probeAll 调用", i >= 0)
        val window = app.substring(maxOf(0, i - 400), minOf(app.length, i + 80))
        assertTrue("启动探活必须位于 mirrorProbeOn 条件判断之内", window.contains("mirrorProbeOn"))
    }

    // ===== 小工具：仓库根在单测里靠 user.dir 定位，gradle 跑单测时就是模块上层 =====
    private fun mainRoot(): java.io.File {
        var d = java.io.File(System.getProperty("user.dir") ?: return java.io.File(""))
        repeat(4) {
            val cand = java.io.File(d, "app/src/main/kotlin/com/gh/dl")
            if (cand.isDirectory) return d
            d = d.parentFile ?: return java.io.File("")
        }
        return java.io.File("")
    }

    private fun readMain(rel: String): String {
        val f = java.io.File(java.io.File(mainRoot(), "app/src/main/kotlin/com/gh/dl"), rel)
        // 无法定位源码目录时（如从其他目录运行测试）直接跳过，避免产生误报。
        // 抛出异常会将「测试无法运行」伪装成「默认值错误」，增加排查难度。
        if (!f.isFile) throw org.junit.AssumptionViolatedException("找不到 $f，跳过源码核对")
        return f.readText()
    }

    private fun assertTrue(m: String, v: Boolean) = org.junit.Assert.assertTrue(m, v)
}
