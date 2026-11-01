package com.gh.dl.ui.theme

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.gh.dl.R
import com.gh.dl.ui.AppState

/**
 * 窗口底色（与 res/values/colors.xml 中的 gh_window_bg / gh_window_bg_dark 为同一组值）。
 * 此处重复定义的原因：切换深浅色时需由代码修改窗口底色，XML 定义仅在启动时生效一次。
 * 两处取值必须完全一致，否则界面配色与窗口底色会不一致。
 * 修改任一处需同步另一处。
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
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFCFD),
    surfaceContainer = Color(0xFFF6F8FA),
    surfaceContainerHigh = Color(0xFFEFF2F5),
    surfaceContainerHighest = Color(0xFFE8ECF0),
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
    surfaceContainerLowest = Color(0xFF0B0F14),
    surfaceContainerLow = Color(0xFF12161C),
    surfaceContainer = Color(0xFF1A1F26),
    surfaceContainerHigh = Color(0xFF222831),
    surfaceContainerHighest = Color(0xFF2B323B),
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
 * 排版（Typography）：在离线构建、不依赖外部字体（Google 字体资源访问不到）的前提下，
 * 仅通过字号与字重建立更清晰的层级——标题更紧凑、正文行距更舒展、标签统一中等字重，
 * 整体观感更接近 Material 3 规范。
 */
private val OutfitRegular = Font(R.font.outfit_regular)
private val OutfitMedium = Font(R.font.outfit_medium, FontWeight.Medium)
private val OutfitBold = Font(R.font.outfit_bold, FontWeight.Bold)

/** 拉丁字符用 Outfit（Google 字体，离线随包内嵌），中文自动回落系统字体。 */
private val GhFontFamily = FontFamily(OutfitRegular, OutfitMedium, OutfitBold)

private val GhTypography = Typography(
    displayLarge = TextStyle(fontFamily = GhFontFamily, fontSize = 52.sp, fontWeight = FontWeight.Normal, lineHeight = 58.sp),
    displayMedium = TextStyle(fontFamily = GhFontFamily, fontSize = 44.sp, fontWeight = FontWeight.Normal, lineHeight = 50.sp),
    displaySmall = TextStyle(fontFamily = GhFontFamily, fontSize = 36.sp, fontWeight = FontWeight.Normal, lineHeight = 42.sp),
    headlineLarge = TextStyle(fontFamily = GhFontFamily, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = GhFontFamily, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = GhFontFamily, fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = GhFontFamily, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = GhFontFamily, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = GhFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = GhFontFamily, fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = GhFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = GhFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = GhFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = GhFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = GhFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp),
)

/**
 * 形状（Shapes）：统一圆角阶梯，卡片走 medium、大容器走 large，
 * 让全 App 圆角尺寸有一致层级，而非各处散落的硬编码值。
 */
private val GhShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 深色外观判定的唯一实现（纯函数，界面线程与 Activity 生命周期均可调用）。
 *
 * 除下方的 @Composable 版本外仍需本函数的原因：MainActivity 在 setContent 之前
 * 就要确定窗口主题，此时尚无 Composition，无法调用 isSystemInDarkTheme()，
 * 只能读取 Configuration。首帧即需使用最终配色，否则会先渲染一帧错误配色。
 * 两个判定入口必须采用同一分支逻辑，避免窗口底色与 Compose 配色依据不同而错色。
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
 * 当前是否为深色外观：与 [GhTheme] 采用同一判定逻辑。
 * 不应在各处直接调用 isSystemInDarkTheme()：该函数仅反映系统设置，
 * 当用户在「设置 → 外观」中强制选择浅色或深色时，其结果与实际配色不一致。
 */
@Composable
fun isDarkTheme(): Boolean = isDarkMode(AppState.themeMode, isSystemInDarkTheme())

/**
 * 将窗口底色与状态栏图标一并切换为 [dark] 对应的配色。
 *
 * 两项设置必须同时执行，且与 Compose 的新配色处于同一帧：
 *  - 窗口底色位于 Compose 内容之下，内容未覆盖的区域（卡片圆角外、
 *    浏览页中 setBackgroundColor(TRANSPARENT) 的 WebView、下拉回弹露出的边缘）
 *    显示该颜色。该值不随 Compose 配色变化，App 内切换深浅色时若不更新，
 *    导致切换后上下区域颜色不一致，即界面残影的成因。
 *  - 状态栏图标颜色属于窗口属性，不随 Compose 配色变化，需显式设置。
 * 需与底色在同一调用中设置：SideEffect 在本次组合之后执行，图标会比正文晚一帧变色。
 */
@Suppress("DEPRECATION")
fun applyWindowColors(activity: Activity, dark: Boolean) {
    val w = activity.window
    w.setBackgroundDrawable(ColorDrawable(if (dark) WINDOW_BG_DARK else WINDOW_BG_LIGHT))
    // 系统导航条颜色同样不随 Compose 配色变化，需一并设置，
    // 否则 App 内切换深色后导航条仍为浅色。
    // Android 15（API 35）起强制边到边并忽略该属性，仅在低版本设置，避免无效调用。
    if (Build.VERSION.SDK_INT < 35) {
        w.navigationBarColor = if (dark) NAV_BG_DARK else NAV_BG_LIGHT
    }
    val c = WindowCompat.getInsetsController(w, w.decorView)
    c.isAppearanceLightStatusBars = !dark
    runCatching { c.isAppearanceLightNavigationBars = !dark }
}

@Composable
fun GhTheme(content: @Composable () -> Unit) {
    // 外观模式：跟随系统 / 浅色 / 深色。AppState.themeMode 变化即触发重组，切换即时生效，无需重启
    val dark = isDarkTheme()
    // 状态栏图标颜色属于窗口属性，不随 Compose 配色变化：仅在 values/ 中固定
    // windowLightStatusBar=true，用户在 App 内选择「深色」时会出现深色背景配深色图标，
    // 故按实际的 dark 结果设置，三种外观模式均正确。
    val ctx = LocalContext.current
    SideEffect {
        val act = ctx as? Activity ?: return@SideEffect
        // 底色与状态栏图标在同一帧随新配色更新；未更新底色是切换深浅色出现残影的直接原因
        applyWindowColors(act, dark)
    }

    MaterialTheme(
        colorScheme = if (dark) GitHubDark else GitHubLight,
        typography = GhTypography,
        shapes = GhShapes,
        content = content
    )
}
