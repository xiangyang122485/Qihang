package com.gh.dl.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gh.dl.data.Store
import com.gh.dl.ui.*

/**
 * 「权限」页：把 App 实际用到的 4 类权限/授权列出来，各自显示当前状态并可跳转到系统设置。
 *  - 通知（POST_NOTIFICATIONS）：Android 13+ 才需要，可在此请求；拒绝不阻断功能。
 *  - 电池优化：不强制弹，仅提供入口去「忽略电池优化」列表（Doze 下后台下载更稳）。
 *  - 安装未知应用（REQUEST_INSTALL_PACKAGES）：只在下载 APK 点「安装」时才真正检查，这里只显示状态与入口。
 *  - 存储授权（SAF）：未授权时跳到「下载」页让用户挑文件夹。
 */
@Composable
fun PermissionsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalSettingsNav.current

    val notiGranted = if (Build.VERSION.SDK_INT >= 33)
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED else true

    // 这一页四项状态原来是在组合里直接查系统的（通知 / 电池优化 / 安装未知应用 / 存储授权），
    // 两个毛病都在这轮一起处理：
    //  1) 其中「存储授权」那项内部是一次 ContentResolver.query（binder + 可能冷启动 provider），
    //     写在组合里等于每次重组一次 IPC —— 和本轮清掉的其它「组合里做 IO」是同一类问题；
    //  2) 状态只在**进入本页时**取一次。用户的真实操作是「点进去系统设置 → 授权 → 按返回回来」，
    //     回来时组合未必重跑，那一行还写着「未授权」，于是「明明授权了却还显示没授权」。
    // 现在：一次性的读取放 IO 线程，并且**每次回到前台（ON_RESUME）重读一遍**。
    val vmSettings = LocalSettingsVM.current
    var notiState by remember { mutableStateOf(notiGranted) }
    var batteryOk by remember { mutableStateOf(false) }
    var installOk by remember { mutableStateOf(false) }
    var storeName by remember { mutableStateOf(vmSettings.sharedName) }
    // 申请通知权限的回调也要改这个状态，所以声明在它之后
    val notiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notiState = it }
    // resumeTick：每次 ON_RESUME +1，用它当 LaunchedEffect 的 key 触发重读
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(resumeTick) {
        val r = withContext(Dispatchers.IO) {
            val n = runCatching {
                val pm2 = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm2.isIgnoringBatteryOptimizations(ctx.packageName)
            }.getOrDefault(false)
            val i = runCatching {
                Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()
            }.getOrDefault(false)
            val s = if (Store.isSet(ctx)) Store.displayName(ctx) else ""
            // 通知权限是本地检查（无 IPC），一并在这里读，口径和其他三项一致
            val noti = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            n to i to (s to noti)
        }
        batteryOk = r.first.first
        installOk = r.first.second
        storeName = r.second.first
        vmSettings.sharedName = r.second.first
        notiState = r.second.second
    }
    // Store.isSet 也是本地 SP 读取（无 IPC），保留它做兜底：目录被外部撤销授权时
    // displayName 会变空，但 isSet 未必同步，两种情况都算「没授权」才安全
    val storeOk = storeName.isNotBlank() && Store.isSet(ctx)

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("权限", onBack)
        PermissionRow(
            icon = Icons.Filled.Notifications,
            title = "通知",
            state = if (notiState) "已授权" else "未授权",
            ok = notiState,
            onClick = {
                if (Build.VERSION.SDK_INT >= 33 && !notiState) {
                    notiLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // 已授权或低版本：打开 App 通知设置供复核
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        })
                    }
                }
            }
        )

        PermissionRow(
            icon = Icons.Filled.BatteryChargingFull,
            title = "电池优化",
            state = if (batteryOk) "不受限制" else "受系统限制（建议忽略）",
            ok = batteryOk,
            onClick = {
                // 不强制弹：只打开系统「忽略电池优化」列表，由用户自行选择
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        )

        PermissionRow(
            icon = Icons.Filled.SystemUpdateAlt,
            title = "安装未知应用",
            state = if (installOk) "已允许" else "未允许（装 APK 前需开启）",
            ok = installOk,
            onClick = {
                // 跳到本应用的「未知来源应用安装」设置项（需 REQUEST_INSTALL_PACKAGES）
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.fromParts("package", ctx.packageName, null)
                    })
                }
            }
        )

        PermissionRow(
            icon = Icons.Filled.FolderOpen,
            title = "存储授权（SAF）",
            state = if (storeOk) "已授权：" + storeName.ifBlank { "（名称读取中…）" }
                    else "未授权（建议设置）",
            ok = storeOk,
            onClick = {
                // 跳到「下载」页让用户挑一个共享文件夹
                nav.navigate(SettingsRoute.Download.route)
            }
        )

        Spacer(Modifier.height(4.dp))
        Text(
            "本 App 只申请网络权限；写文件用系统文件授权（SAF），你亲手挑哪个文件夹它就只有那一个文件夹的权限。" +
                "通知与电池优化都可选，拒绝也能正常下载。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 权限行：图标 + 标题 + 状态（语义色）+ 点击跳转系统设置 */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    state: String,
    ok: Boolean,
    onClick: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(state, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            val dotColor: Color = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.warning
            StatusDot(dotColor)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
