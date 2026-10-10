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
 * 只介绍「这一个 App 能干什么 + 它怎么保护你」，点「开始使用」后才去要通知权限
 * （[Manifest.permission.POST_NOTIFICATIONS]）：拒绝也不阻断任何功能，仅影响下载进度通知。
 * 不在这里强制弹存储授权 / 电池优化 —— 那两件事分别在使用时（选文件夹）和设置页里按需触发。
 */
@Composable
fun IntroPage(onDone: () -> Unit) {
    val ctx = LocalContext.current

    // 通知权限：Android 13+ 才需要；低于该版本直接视为已授权，不再弹
    val notiGranted = if (Build.VERSION.SDK_INT >= 33)
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    else true

    // 回调里没有需要驱动界面的状态（原本那个 askedNoti 只写不读），所以直接空实现
    val notiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 无论授予与否都继续，不阻断 */ }

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
            "下载 GitHub 公开开源资源 · 全程不翻墙",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))

        IntroRow(
            Icons.Filled.Download, "下载 Release 软件",
            "粘贴链接 → 列出资产 → 多线程拉取，并默认校验 SHA256"
        )
        IntroRow(
            Icons.Filled.AccountTree, "整仓克隆 / 源码打包",
            "GitHub 官方 22 端口直连，或 jsDelivr 公共 CDN 源码"
        )
        IntroRow(
            Icons.Filled.FolderOpen, "你掌控存储位置",
            "用系统文件授权（SAF）挑一个文件夹，App 只那一处可写"
        )
        Spacer(Modifier.height(28.dp))

        Button(
            onClick = {
                // 仅在点「开始使用」时尝试要通知权限；拒绝也不阻断
                if (!notiGranted) notiLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                onDone()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("开始使用", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "下载进度通知可选；拒绝也能正常下载，只是收不到通知。",
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
