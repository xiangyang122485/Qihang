package com.gh.dl.ui.settings

// 设置 Tab 去重重构：将原 EnginePage（下载引擎）、LocationPage（存储位置）、SharedDirPage / PrivateDirPage
// （共享 / 私有目录说明与缓存清除）合并为单一的「下载」分组页。一级页「下载」卡片点击后进入本页。
import com.gh.dl.ui.*
import com.gh.dl.data.*

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 下载分组页：下载引擎 / 存储位置（共享文件夹 SAF 授权）/ 共享与私有目录说明 / 缓存占用与清除 */
@Composable
fun DownloadPage(onBack: () -> Unit) {
    val vmSettings = LocalSettingsVM.current

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(vmSettings.sharedName) }
    var confirm by remember { mutableStateOf(false) }
    // 此二项原采用 `remember { dirSize(...) }` 与直接调用 available(ctx)：
    // remember 的 lambda 在组合过程中、UI 线程上执行，「仅计算一次」不等同于「不阻塞」。
    // dirSize 需递归遍历两个缓存目录（图片缓存可达上千个文件，耗时数百毫秒），
    // available 需 stat nativeLibraryDir 并解析一次 CA，进入页面时产生卡顿。
    // 当前已移至 IO 线程，先按「未知」渲染。
    var cacheSize by remember { mutableLongStateOf(-1L) }
    var ariaReady by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) {
            dirSize(ctx.cacheDir) + dirSize(ctx.externalCacheDir) to AriaEngine.available(ctx)
        }
        cacheSize = r.first
        ariaReady = r.second
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            Store.setTree(ctx, uri)
            name = Store.displayName(ctx)
            vmSettings.sharedName = name
            toast(ctx, "已授权：$name")
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("下载", onBack)

        // ---- 下载引擎（原 EnginePage） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("文件下载引擎", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("当前引擎", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "okdownload" to "OkDownload 多线程",
                        "aria2" to "Aria2 原生",
                        "single" to "兼容单线程"
                    ).forEach { (id, label) ->
                        val sel = vmSettings.dlEngine == id
                        TextButton(onClick = {
                            vmSettings.dlEngine = id
                            Settings.put(ctx, "dlEngine", id)
                        }) {
                            Text(
                                (if (sel) "● " else "") + label,
                                fontSize = 11.sp,
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (vmSettings.dlEngine == "aria2" && !ariaReady) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "当前设备未匹配到内置 Aria2 二进制（仅内置 arm64），本次下载回退单线程。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.warning
                    )
                }
            }
        }

        // ---- 存储位置（共享文件夹 SAF 授权，原 LocationPage） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("存储位置（共享文件夹）", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (name.isNotBlank()) "已授权：${name}/GitHub下载器/"
                    else "应用私有目录（手机存储/Android/data/com.gh.dl/files/）",
                    fontSize = 12.sp,
                    color = if (name.isNotBlank()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.warning
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (name.isNotBlank())
                        "下载的文件直接写入此目录；克隆的仓库先写入应用私有目录，完成后复制一份至该目录。"
                    else "此目录随应用卸载删除，其他应用不便访问。建议选择用户自有文件夹。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    Button(onClick = { picker.launch(null) }) {
                        Text(if (name.isBlank()) "选择文件夹" else "更换文件夹")
                    }
                    if (name.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            Store.clear(ctx)
                            name = ""
                            vmSettings.sharedName = ""
                            toast(ctx, "已取消授权")
                        }) { Text("取消授权", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        // ---- 共享目录 vs 私有目录 说明（原 SharedDirPage / PrivateDirPage 合并） ----

        // ---- 缓存占用与清除（原 SharedDirPage / PrivateDirPage 的缓存清除合并） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("缓存占用与清除", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前缓存占用：" + (if (cacheSize < 0) "统计中…" else fmtSize(cacheSize)),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { confirm = true }) { Text("清除缓存") }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("清除缓存") },
            text = {
                Text(
                    "确定仅清理应用缓存（" +
                        (if (cacheSize < 0) "统计中…" else fmtSize(cacheSize)) + "）？\n" +
                        "此操作不影响已下载完成的文件。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    // 递归删除两个缓存目录为纯磁盘操作，原在点击回调（UI 线程）中执行，
                    // 缓存较大时会导致界面卡顿；当前已移至后台线程，完成后回读占用大小
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            val n = clearAppCache(ctx)
                            n to dirSize(ctx.cacheDir) + dirSize(ctx.externalCacheDir)
                        }
                        cacheSize = r.second
                        toast(ctx, "已清除缓存 ${fmtSize(r.first)}")
                    }
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("取消") }
            }
        )
    }
}
