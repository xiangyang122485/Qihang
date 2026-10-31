package com.gh.dl.ui.settings

// 设置「外观」二级页：跟随系统 / 浅色 / 深色三选一，写入 SharedPreferences(theme_mode) 并实时切换配色。
import com.gh.dl.ui.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh.dl.data.Settings

/**
 * 外观设置：三选一（跟随系统 / 浅色 / 深色）。
 * 选中后写入 [com.gh.dl.data.Settings] 的 theme_mode，并同步 [com.gh.dl.ui.AppState.themeMode]，
 * [com.gh.dl.ui.theme.GhTheme] 实时读取该状态重绘配色，立即生效，无需重启应用。
 */
@Composable
fun AppearancePage(onBack: () -> Unit) {
    val ctx = LocalContext.current

    // 「浅色」为出厂默认主题，
    // 界面上予以标注，避免用户误以为默认为「跟随系统」
    val options = listOf(
        "light" to "浅色模式",
        "dark" to "深色模式",
        "system" to "跟随系统"
    )
    val cur = AppState.themeMode

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("外观", onBack)

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("主题", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                options.forEach { (id, label) ->
                    val sel = cur == id
                    val isDefault = id == Settings.THEME_DEFAULT
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                AppState.themeMode = id
                                Settings.put(ctx, Settings.KEY_THEME_MODE, id)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = sel, onClick = {
                            AppState.themeMode = id
                            Settings.put(ctx, Settings.KEY_THEME_MODE, id)
                        })
                        Spacer(Modifier.width(8.dp))
                        val icon: ImageVector = when (id) {
                            "light" -> Icons.Filled.LightMode
                            "dark" -> Icons.Filled.DarkMode
                            else -> Icons.Filled.Settings
                        }
                        Icon(
                            icon, contentDescription = null,
                            tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            label + if (isDefault) "（默认）" else "", fontSize = 14.sp,
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        CollapsibleNote(
            "主题与启动配色",
            "切换后立即全应用生效，已打开页面同步更新，无需重启。\n" +
                "· 底色、状态栏图标、底部导航条在同一帧内更新；\n" +
                "· 冷启动先读取本设置再渲染首帧，不出现先深后浅的过渡；\n" +
                "· 出厂默认为浅色，如需随系统切换请选择「跟随系统」；\n" +
                "· 「跟随系统」下系统切换深浅色时本页重建（Android 常规行为），重建后的颜色以切换后的设置为准。"
        )
    }
}
