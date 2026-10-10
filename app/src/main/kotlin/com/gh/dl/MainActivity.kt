package com.gh.dl

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gh.dl.data.Settings
import com.gh.dl.ui.AppState
import com.gh.dl.ui.GhApp
import com.gh.dl.ui.theme.GhTheme
import com.gh.dl.ui.theme.applyWindowColors
import com.gh.dl.ui.theme.isDarkMode
import com.gh.dl.ui.theme.systemIsNight

class MainActivity : ComponentActivity() {

    /** 上一次按哪套外观配过窗口，用来判断「到底要不要重设」 */
    private var windowDark: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须在 setContent **之前**：这一刻还没有 Composition，Compose 侧的配色判断
        // 拿不到数据（主题选择要等 LaunchedEffect 里的 initSettings 才灌进 AppState，
        // 那是第一帧之后的事）。所以这里自己同步读一次 SharedPreferences 定窗口配色。
        // 少这一步就是用户反馈的「进 App 一瞬间是深色，然后才切回浅色」。
        applyWindowTheme(currentDark())
        val link = extractLink(intent)
        setContent {
            GhTheme { GhApp(link) }
        }
    }

    /**
     * App 内当前该用深色还是浅色 —— 口径与 [com.gh.dl.ui.theme.isDarkTheme] 完全一致，
     * 走 [isDarkMode] 这个共用纯函数，避免「窗口按一套、内容按另一套」。
     */
    // 「系统是不是夜间」用 theme 里那份共用实现，免得两套口径
    private fun currentDark(): Boolean =
        isDarkMode(Settings.themeMode(this), systemIsNight(resources.configuration))

    /**
     * 按外观选窗口主题。
     *
     * 系统夜间切换时 Android 会重建 Activity（Manifest 没声明 uiMode，见那边注释），
     * 所以这里只管「按 App 内的选择挑一套」，不需要处理 uiMode 变更回调。
     */
    private fun applyWindowTheme(dark: Boolean) {
        if (windowDark == dark) return
        windowDark = dark
        setTheme(if (dark) R.style.Theme_GhDl_Dark else R.style.Theme_GhDl_Light)
        // 底色 + 状态栏图标：setTheme 只影响**下一次**窗口取属性，已经创建好的窗口
        // 得手动改一次才立刻生效（这正是「切深浅色有残影」的另一半原因）
        applyWindowColors(this, dark)
    }

    /**
     * 活动已在前台时，系统（分享、点深链）默认只会回调 onNewIntent 而不再 onCreate。
     * 少了这一步，「正在用时从浏览器分享仓库链接进来」就完全没有反应，
     * 用户只能先杀掉 App 再分享。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 不 setIntent 的话，稍后读 intent 的入口（系统恢复、权限回调）拿到的还是旧意图
        setIntent(intent)
        extractLink(intent)?.let { AppState.pendingLink = it }
    }

    /**
     * 解析深链：仅接受 github.com / www.github.com 且路径至少两段（/owner/repo）的仓库链接，
     * 其余 VIEW 意图（单段路径、其它 host）一律忽略，不污染解析器输入框。
     */
    private fun extractLink(i: Intent?): String? = when (i?.action) {
        Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_VIEW -> i.data?.let { uri ->
            val h = uri.host?.lowercase()
            if ((h == "github.com" || h == "www.github.com") &&
                uri.pathSegments.size >= 2
            ) uri.toString() else null
        }
        else -> null
    }
}
