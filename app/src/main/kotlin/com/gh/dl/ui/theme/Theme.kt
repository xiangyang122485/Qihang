package com.gh.dl.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.gh.dl.ui.AppState

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

@Composable
fun GhTheme(content: @Composable () -> Unit) {
    // 外观模式：跟随系统 / 浅色 / 深色。AppState.themeMode 改变即触发重组，立即切换无需重启
    val dark = when (AppState.themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) GitHubDark else GitHubLight,
        content = content
    )
}
