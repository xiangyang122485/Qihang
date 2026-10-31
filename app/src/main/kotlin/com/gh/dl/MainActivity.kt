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

    /** 上一次按哪套外观配置窗口，用于判断「是否需要重新设置」 */
    private var windowDark: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须在 setContent **之前**：此时尚无 Composition，Compose 侧的配色判断
        // 无法获取数据（主题选择需等待 LaunchedEffect 中的 initSettings 注入 AppState，
        // 该过程在第一帧之后执行）。因此此处自行同步读取一次 SharedPreferences 确定窗口配色。
        // 缺少此步骤时，进入应用瞬间为深色，随后才切换为浅色。
        applyWindowTheme(currentDark())
        val link = extractLink(intent)
        setContent {
            GhTheme { GhApp(link) }
        }
    }

    /**
     * App 内当前应使用深色还是浅色，判定逻辑与 [com.gh.dl.ui.theme.isDarkTheme] 完全一致，
     * 使用 [isDarkMode] 这一共用纯函数，避免「窗口按一套、内容按另一套」。
     */
    // 「系统是否为夜间模式」使用 theme 中的共用实现，避免两套判定逻辑
    private fun currentDark(): Boolean =
        isDarkMode(Settings.themeMode(this), systemIsNight(resources.configuration))

    /**
     * 按外观选择窗口主题。
     *
     * 系统夜间模式切换时 Android 会重建 Activity（Manifest 未声明 uiMode，参见相关注释），
     * 因此此处仅负责「按 App 内的选择配置一套」，无需处理 uiMode 变更回调。
     */
    private fun applyWindowTheme(dark: Boolean) {
        if (windowDark == dark) return
        windowDark = dark
        setTheme(if (dark) R.style.Theme_GhDl_Dark else R.style.Theme_GhDl_Light)
        // 底色加状态栏图标：setTheme 仅影响**下一次**窗口获取属性，已创建的窗口
        // 需手动更改一次方可立即生效（此为「切换深浅色有残影」的另一半原因）
        applyWindowColors(this, dark)
    }

    /**
     * Manifest 已声明 configChanges（含 uiMode、orientation、density、fontScale、locale），
     * 系统不再重建 Activity。重建会销毁 WebView 与全部 ViewModel，并触发
     * DownloadStore.load 重复载入同一批任务（在途任务的进度写回旧对象，界面表现为停止更新），
     * 因此改由本回调仅更新窗口底色与状态栏图标，内容侧由 Compose 重组自行响应。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWindowTheme(currentDark())
    }

    /**
     * Activity 已在前台时，系统（分享、点击深链）默认仅回调 onNewIntent 而不再调用 onCreate。
     * 缺少此步骤，「使用时从浏览器分享仓库链接进入」将无任何响应，
     * 用户只能先终止 App 再分享。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 不调用 setIntent 时，稍后读取 intent 的入口（系统恢复、权限回调）获取的仍为旧意图
        setIntent(intent)
        extractLink(intent)?.let { AppState.pendingLink = it }
    }

    /**
     * 解析深链：仅接受 github.com / www.github.com 且路径至少两段（/owner/repo）的仓库链接，
     * 其他 VIEW 意图（单段路径、其他 host）一律忽略，不污染解析器输入框。
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
