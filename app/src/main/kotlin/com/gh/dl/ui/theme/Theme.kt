package com.gh.dl.ui.theme

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.gh.dl.ui.AppState

/**
 * 窗口底色（与 res/values/colors.xml 的 gh_window_bg / gh_window_bg_dark 是同一组值）。
 * 这里再写一份是因为切深浅色时要**用代码**改窗口底色（XML 那份只在启动时生效一次），
 * 两处必须一字不差，否则 App 内切完深浅色、窗口底色却停在另一套配色上。
 * 改动任一处都要同步另一处。
 */
private const val WINDOW_BG_LIGHT = 0xFFFAFAFA.toInt()
private const val WINDOW_BG_DARK = 0xFF0F1419.toInt()

/** 底部导航条区域的颜色，与两套配色的 surface 一致（见上面两个 ColorScheme） */
private const val NAV_BG_LIGHT = 0xFFFFFFFF.toInt()
private const val NAV_BG_DARK = 0xFF1A1F26.toInt()

/**
 * 配色（浅色）。
 * 背景 #FAFAFA、表面（卡片）#FFFFFF；primary=深绿 #1B5E20（所有实心按钮统一使用），onPrimary=白；
 * primaryContainer=浅绿底、onPrimaryContainer=深绿字；
 * secondary=绿 #1F883D（辅助强调，与 primary 同色系，避免混入亮蓝）；
 * error=红 #B00020（危险操作：删除密钥 / 取消授权 / 删除前缀等）；
 * surfaceVariant / outline 走中性灰，保证亮暗两套都协调。
 */
private val GitHubLight = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8F5E9),
    onPrimaryContainer = Color(0xFF1B5E20),
    secondary = Color(0xFF1F883D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAFBE1),
    onSecondaryContainer = Color(0xFF1A472A),
    tertiary = Color(0xFF8250DF),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBEFFF),
    onTertiaryContainer = Color(0xFF3A0B60),
    error = Color(0xFFB00020),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2328),
    onSurfaceVariant = Color(0xFF57606A),
    surfaceVariant = Color(0xFFF6F8FA),
    outline = Color(0xFFD0D7DE),
    outlineVariant = Color(0xFFE5E9EE),
)

/**
 * 配色（深色）。背景 #0F1419、表面（卡片）#1A1F26，强调色整体提亮以在暗底上可读。
 * primary=#2E7D32（深绿的亮版）；error 提亮为 #CF6679。
 */
private val GitHubDark = darkColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color(0xFF0D1117),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFE8F5E9),
    secondary = Color(0xFF3FB950),
    onSecondary = Color(0xFF0D1117),
    secondaryContainer = Color(0xFF1A472A),
    onSecondaryContainer = Color(0xFFDAFBE1),
    tertiary = Color(0xFFA371F7),
    onTertiary = Color(0xFF0D1117),
    tertiaryContainer = Color(0xFF3A0B60),
    onTertiaryContainer = Color(0xFFFBEFFF),
    error = Color(0xFFCF6679),
    onError = Color(0xFF0D1117),
    errorContainer = Color(0xFFB00020),
    onErrorContainer = Color(0xFFFFCDD2),
    background = Color(0xFF0F1419),
    surface = Color(0xFF1A1F26),
    onSurface = Color(0xFFE6EDF3),
    onSurfaceVariant = Color(0xFF9198A1),
    surfaceVariant = Color(0xFF21262D),
    outline = Color(0xFF30363D),
    outlineVariant = Color(0xFF21262D),
)

/**
 * 深色外观的**唯一口径**（纯函数版，界面线程与 Activity 生命周期都能调）。
 *
 * 为什么不能只有下面那个 @Composable 版本：MainActivity 在 setContent **之前**就要决定
 * 用哪套窗口主题（这是修「进 App 一瞬间闪黑」的关键 —— 首帧就得是最终配色），
 * 那一刻还没有 Composition，拿不到 isSystemInDarkTheme()，只能自己读 Configuration。
 * 两边必须是同一个 when 分支，否则「窗口底色按 A 判断、Compose 配色按 B 判断」，
 * 又会出现一帧错色 —— 正是要修的那个问题。
 */
fun isDarkMode(mode: String, systemNight: Boolean): Boolean = when (mode) {
    "light" -> false
    "dark" -> true
    else -> systemNight
}

/** 系统当前是不是夜间模式（不含 App 内的强制浅色/深色） */
fun systemIsNight(cfg: Configuration): Boolean =
    cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/**
 * 当前是否深色外观：与 [GhTheme] 选配色用同一份判断，避免各处自己调
 * isSystemInDarkTheme() —— 那个只看系统设置，用户在「设置 → 外观」里强制
 * 选浅色/深色时就会算错（颜色跟着系统、配色跟着 App，两边不一致）。
 */
@Composable
fun isDarkTheme(): Boolean = isDarkMode(AppState.themeMode, isSystemInDarkTheme())

/**
 * 把「窗口底色 + 状态栏图标」一次到位地切到 [dark] 对应的配色。
 *
 * 这两件事必须**同时**做，而且要跟着 Compose 的新配色同一帧做：
 *  - 窗口底色是 Compose 内容**背后**那张画布。内容没铺满的地方（卡片圆角外、
 *    浏览页那个 setBackgroundColor(TRANSPARENT) 的 WebView、下拉回弹露出的边缘）
 *    显示的就是它。原来它一直是 XML 里的默认值，App 内切深浅色时根本没人改它，
 *    于是「上面已经变白、底下露出来的还是深色」—— 用户说的**切换瞬间有残影**就是这个。
 *  - 状态栏图标是窗口属性，Compose 换配色换不到它，只能显式设。
 * 原先它只在 SideEffect 里设：SideEffect 跑在这一次组合**之后**，所以图标会比正文
 * 晚一帧翻色；这里和底色一起设，两处同一帧完成。
 */
fun applyWindowColors(activity: Activity, dark: Boolean) {
    val w = activity.window
    w.setBackgroundDrawable(ColorDrawable(if (dark) WINDOW_BG_DARK else WINDOW_BG_LIGHT))
    // 系统导航条（底部那一条）同样不会跟着 Compose 换色。原来完全不设，
    // 于是 App 内切深色后底部还留一条浅色 —— 也是「残影」的一部分。
    w.navigationBarColor = if (dark) NAV_BG_DARK else NAV_BG_LIGHT
    val c = WindowCompat.getInsetsController(w, w.decorView)
    c.isAppearanceLightStatusBars = !dark
    runCatching { c.isAppearanceLightNavigationBars = !dark }
}

@Composable
fun GhTheme(content: @Composable () -> Unit) {
    // 外观模式：跟随系统 / 浅色 / 深色。AppState.themeMode 改变即触发重组，立即切换无需重启
    val dark = isDarkTheme()
    // 状态栏图标颜色：窗口属性，Compose 换配色换不到它。原来只在 values/ 写死
    // windowLightStatusBar=true，用户在 App 内选「深色」时就是深底+深图标，时间/电量看不见。
    // 这里按实际的 dark 结果设置，所以「跟随系统 / 强制浅色 / 强制深色」三种都对。
    val ctx = LocalContext.current
    SideEffect {
        val act = ctx as? Activity ?: return@SideEffect
        // 底色与状态栏图标**同一帧**跟着新配色走（缺底色就是「切深浅色有残影」的根因）
        applyWindowColors(act, dark)
    }

    MaterialTheme(
        colorScheme = if (dark) GitHubDark else GitHubLight,
        content = content
    )
}
