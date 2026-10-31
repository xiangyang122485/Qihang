package com.gh.dl.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * 首次启动全屏引导页。
 *
 * 介绍本应用的功能与隐私保护措施。点击「开始使用」后请求通知权限
 * （[Manifest.permission.POST_NOTIFICATIONS]）：拒绝不影响任何功能，仅影响下载进度通知。
 * 存储授权与电池优化不在此处触发，分别在使用时（选择文件夹）和设置页按需引导。
 */
@Composable
fun IntroPage(onDone: () -> Unit) {
    val ctx = LocalContext.current

    // 通知权限：Android 13+ 需要；低于该版本视为已授权，不弹出请求
    val notiGranted = if (Build.VERSION.SDK_INT >= 33)
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    else true

    // 回调无需驱动界面状态更新，因此空实现
    val notiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 无论授予与否均继续，不阻断后续流程 */ }

    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "GitHub 下载器",
            fontSize = 26.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "下载 GitHub 公开资源 · 默认直连官方端点，不含隧道与代理功能",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))

        IntroRow(
            Icons.Filled.Download, "下载 Release 软件",
            "输入链接后列出 Release 资产，支持多线程下载与 SHA256 校验"
        )
        IntroRow(
            Icons.Filled.AccountTree, "整仓克隆 / 源码打包",
            "直连 GitHub 官方 22 端口，或使用 jsDelivr 公共 CDN 获取源码"
        )
        IntroRow(
            Icons.Filled.FolderOpen, "存储位置由用户指定",
            "通过系统文件授权（SAF）指定文件夹，应用仅对该目录具有写入权限"
        )
        Spacer(Modifier.height(28.dp))

        Button(
            onClick = {
                // 仅在点击「开始使用」时请求通知权限；拒绝不阻断后续流程
                // 通知权限自 Android 13 起才存在，低版本发出请求必然被拒，故显式判断版本
                if (!notiGranted && Build.VERSION.SDK_INT >= 33)
                    notiLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                onDone()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("开始使用", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "下载进度通知为可选项，拒绝授权不影响下载。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun IntroRow(icon: ImageVector, title: String, desc: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
