package com.gh.dl.ui.settings

// 设置「外观」二级页：跟随系统 / 浅色 / 深色 三选一，写入 SharedPreferences(theme_mode) 并实时切换配色。
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
 * 选中即写入 [com.gh.dl.data.Settings] 的 theme_mode，并同步 [com.gh.dl.ui.AppState.themeMode]，
 * [com.gh.dl.ui.theme.GhTheme] 实时读取该状态重绘配色，立即生效、无需重启 App。
 */
@Composable
fun AppearancePage(onBack: () -> Unit) {
    val ctx = LocalContext.current

    val options = listOf(
        "system" to "跟随系统",
        "light" to "浅色模式",
        "dark" to "深色模式"
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
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                AppState.themeMode = id
                                Settings.put(ctx, "theme_mode", id)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = sel, onClick = {
                            AppState.themeMode = id
                            Settings.put(ctx, "theme_mode", id)
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
                            label, fontSize = 14.sp,
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "切换后立即在整个 App 生效，已打开的页面也会跟着变，不需要重启。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
