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
 * 「权限」页：列出应用实际使用的 4 类权限/授权，各自显示当前状态并可跳转至系统设置。
 *  - 通知（POST_NOTIFICATIONS）：仅 Android 13+ 需要，可在此请求；拒绝不阻断功能。
 *  - 电池优化：不强制弹窗，仅提供入口跳转至「忽略电池优化」列表（Doze 模式下后台下载更稳定）。
 *  - 安装未知应用（REQUEST_INSTALL_PACKAGES）：仅在下载 APK 后点击「安装」时检查，此处仅显示状态与入口。
 *  - 存储授权（SAF）：未授权时跳转至「下载」页引导用户选择文件夹。
 */
@Composable
fun PermissionsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalSettingsNav.current

    val notiGranted = if (Build.VERSION.SDK_INT >= 33)
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED else true

    // 本页四项状态原在组合中直接查询系统（通知 / 电池优化 / 安装未知应用 / 存储授权），
    // 存在以下问题并一并处理：
    //  1) 其中「存储授权」项内部为一次 ContentResolver.query（binder + 可能冷启动 provider），
    //     写在组合中等于每次重组执行一次 IPC，与已修复的其他「组合中执行 IO」属同一类问题；
    //  2) 状态仅在进入本页时获取一次。用户实际操作为「跳转至系统设置 → 授权 → 返回」，
    //     返回时组合未必重新执行，状态行仍显示「未授权」，导致「已授权却显示未授权」。
    // 当前实现：一次性读取移至 IO 线程，并且每次回到前台（ON_RESUME）重新读取。
    val vmSettings = LocalSettingsVM.current
    var notiState by remember { mutableStateOf(notiGranted) }
    var batteryOk by remember { mutableStateOf(false) }
    var installOk by remember { mutableStateOf(false) }
    var storeName by remember { mutableStateOf(vmSettings.sharedName) }
    // 申请通知权限的回调亦需修改此状态，因此声明在其后
    val notiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notiState = it }
    // resumeTick：每次 ON_RESUME 加 1，用作 LaunchedEffect 的 key 触发重新读取
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
            val i = runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
            val s = if (Store.isSet(ctx)) Store.displayName(ctx) else ""
            // 通知权限为本地检查（无 IPC），一并在此读取，与其他三项一致
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
    // Store.isSet 亦为本地 SP 读取（无 IPC），保留作为补充判断：目录被外部撤销授权时
    // displayName 会变空，但 isSet 未必同步，两种情况均判定为「未授权」以确保安全
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
                    // 已授权或低版本：打开应用通知设置供复核
                    launchSettings(
                        ctx,
                        Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                        pkgUri = false,
                        failHint = "该系统未提供应用通知设置页，请在系统设置中查找"
                    )
                }
            }
        )

        PermissionRow(
            icon = Icons.Filled.BatteryChargingFull,
            title = "电池优化",
            state = if (batteryOk) "不受限制" else "受系统限制（建议忽略）",
            ok = batteryOk,
            onClick = {
                // 不强制弹窗：仅打开系统「忽略电池优化」列表，由用户自行选择
                launchSettings(
                    ctx,
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                    pkgUri = false,
                    failHint = "该系统未提供电池优化设置页，可在系统设置的电池项中查找"
                )
            }
        )

        PermissionRow(
            icon = Icons.Filled.SystemUpdateAlt,
            title = "安装未知应用",
            state = if (installOk) "已允许" else "未允许（装 APK 前需开启）",
            ok = installOk,
            onClick = {
                // 跳转至本应用的「未知来源应用安装」设置项（需 REQUEST_INSTALL_PACKAGES）
                launchSettings(
                    ctx,
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    pkgUri = true,
                    failHint = "该系统未提供未知来源安装设置页，可在系统设置的权限项中查找"
                )
            }
        )

        PermissionRow(
            icon = Icons.Filled.FolderOpen,
            title = "存储授权（SAF）",
            state = if (storeOk) "已授权：" + storeName.ifBlank { "（名称读取中…）" }
                    else "未授权（建议设置）",
            ok = storeOk,
            onClick = {
                // 跳转至「下载」页引导用户选择共享文件夹
                nav.navigate(SettingsRoute.Download.route)
            }
        )

        Spacer(Modifier.height(4.dp))
        Text(
            "应用仅申请网络权限。文件写入使用系统文件授权框架（SAF），授权范围限于所选文件夹。" +
                "通知与电池优化均为可选项，拒绝不影响下载。",
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
