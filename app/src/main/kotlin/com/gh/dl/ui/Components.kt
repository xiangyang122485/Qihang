package com.gh.dl.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh.dl.ui.theme.isDarkTheme

/**
 * 统一卡片：12dp 圆角、surface 表面色、outlineVariant 浅描边、轻微阴影。
 * 全项目所有卡片都用它，避免圆角/间距/描边各写各的。
 */
@Composable
fun GhCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        content = content
    )
}

/**
 * 分区小标题：左侧一条强调色竖条 + 加粗标签。
 * 解析页/设置页按来源分组时用，让层级一眼看清。
 * @param accent 强调色，默认 primary；镜像/jsDelivr 通道传 tertiary。
 */
@Composable
fun SectionHeader(text: String, accent: Color = MaterialTheme.colorScheme.primary) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 14.dp)
                .background(accent, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 状态圆点：用语义色表达 成功/警告/失败/中性（如网络结论行） */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        Modifier
            .size(8.dp)
            .then(modifier)
            .background(color, RoundedCornerShape(50))
    )
}

/** 图标 + 文字 的紧凑按钮（用于顶栏操作栏） */
@Composable
fun RowScope.IconText(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        androidx.compose.material3.Icon(
            icon, contentDescription = null,
            tint = tint, modifier = Modifier.size(18.dp)
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 13.sp)
    }
}

/**
 * 警告色（琥珀）：浅色 #9A6700 / 深色 #D29922。
 * M3 没有原生 warning 语义，这里作为 ColorScheme 的扩展属性提供，避免在 UI 里写死颜色。
 * 放在 ui 包内，所有 UI 文件同包即可直接用 MaterialTheme.colorScheme.warning。
 */
val ColorScheme.warning: Color
    @Composable
    get() = if (isDarkTheme()) Color(0xFFD29922) else Color(0xFF9A6700)

/**
 * 文字链接色：primary 的 0.7 透明度版本。所有「文字链接」（复制 / 打开 / 导航类 TextButton）
 * 统一用它，与实心按钮（primary 实色）区分，保证设置页颜色统一、不混用亮蓝。
 */
val ColorScheme.link: Color
    @Composable
    get() = primary.copy(alpha = 0.7f)

/**
 * 强调红（实心按钮用）：解析器页「解析」按钮专用，与绿色主按钮区分开。
 * 浅色 #C62828 / 深色 #EF5350 —— 深色底上纯红不够亮，提亮一档保证白字可读。
 */
val ColorScheme.accentRed: Color
    @Composable
    get() = if (isDarkTheme()) Color(0xFFEF5350) else Color(0xFFC62828)

/**
 * 强调蓝（实心按钮用）：「浏览」按钮专用，语义是「打开网页」，
 * 与绿色（下载类动作）、红色（解析动作）区分。
 * 浅色 #1565C0 / 深色 #64B5F6。
 */
val ColorScheme.accentBlue: Color
    @Composable
    get() = if (isDarkTheme()) Color(0xFF64B5F6) else Color(0xFF1565C0)
