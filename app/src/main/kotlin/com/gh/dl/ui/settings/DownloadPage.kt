package com.gh.dl.ui.settings

// 阶段 4「设置 Tab 去重重构」：把原 EnginePage（下载引擎）+ LocationPage（存储位置）+ SharedDirPage / PrivateDirPage
// （共享/私有目录说明 + 缓存清除）合并为单一的「下载」分组页。一级页「下载」卡片点进来就是这里。
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

/** 下载分组页：下载引擎 / 存储位置（共享文件夹 SAF 授权）/ 共享·私有目录说明 / 缓存占用与清除 */
@Composable
fun DownloadPage(onBack: () -> Unit) {
    val vmSettings = LocalSettingsVM.current

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(vmSettings.sharedName) }
    var confirm by remember { mutableStateOf(false) }
    // 这两项原来是 `remember { dirSize(...) }` 和直接调 available(ctx)：
    // remember 的 lambda 是在**组合过程中、UI 线程上**跑的，「只算一次」不等于「不卡」。
    // dirSize 要递归遍历两个缓存目录（图片缓存一多就是上千个文件，几百毫秒起），
    // available 也要 stat nativeLibraryDir 并解一次 CA —— 一进这页就顿一下，
    // 是用户说的「上下滑动 / 切页卡卡的」里的一处。挪到 IO 线程，先按「未知」渲染。
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
                CollapsibleNote(
                    "引擎说明（点击展开）",
                    "内置引擎把文件切块多线程拉，对支持 Range 的源（GitHub Release、jsDelivr、高校镜像）提速明显。" +
                        "若某个引擎在你的设备上不好用，解析页每个文件行都有「复制/分享直链」图标，" +
                        "把最终直链发给你的第三方下载器即可。\n" +
                        "OkDownload：纯 JVM 多线程分片，最稳。\n" +
                        "Aria2：原生 aria2c（已内置 arm64 二进制；其它 CPU 架构会自动回退单线程）。\n" +
                        "兼容单线程：OkHttp 直拉，最稳但不提速。"
                )
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
                        "提示：当前设备未匹配到内置的 Aria2 二进制（仅 arm64 已内置），本次下载会自动用单线程。",
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
                    else "App 私有目录（手机存储/Android/data/com.gh.dl/files/）",
                    fontSize = 12.sp,
                    color = if (name.isNotBlank()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.warning
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (name.isNotBlank())
                        "下载的软件直接写进这个目录；克隆的仓库会先落在 App 私有目录，完成后自动复制一份过去。"
                    else "这个目录卸载 App 会一起删掉，也不方便用别的软件打开。建议选一个自己的文件夹。",
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
                Spacer(Modifier.height(8.dp))
                CollapsibleNote(
                    "这个授权是怎么工作的（点击展开）",
                    "用系统标准的文件授权（SAF）：你挑哪个文件夹，App 就只有那一个文件夹的读写权，" +
                        "不需要任何存储权限，也不会去翻别的地方。建议新建一个专门的文件夹，比如「GitHub下载」。" +
                        "选手机存储根目录也行，App 会在里面建一个 GitHub下载器 子目录。"
                )
            }
        }

        // ---- 共享目录 vs 私有目录 说明（原 SharedDirPage / PrivateDirPage 合并） ----
        CollapsibleNote(
            "共享目录 与 私有目录 有什么区别（点击展开）",
            "共享目录 = 你用系统文件选择器授权的文件夹（写入 GitHub下载器/ 子目录），文件用手机文件管理器就能找到；" +
                "没设共享目录时，所有文件落在私有目录。\n" +
                "私有目录 = App 自身的 files 目录（Android/data/com.gh.dl/files/），卸载 App 会一起没，但不用任何授权。\n" +
                "两者都只存已经下载完成的文件；清除缓存不会动它们。"
        )

        // ---- 缓存占用与清除（原 SharedDirPage / PrivateDirPage 的缓存清除合并） ----
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("缓存占用与清除", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前缓存占用：" + (if (cacheSize < 0) "统计中…" else fmtSize(cacheSize)),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "只清理 App 缓存数据（cache 目录），不会删除已经下载完成的文件。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    "确定只清理 App 缓存（" +
                        (if (cacheSize < 0) "统计中…" else fmtSize(cacheSize)) + "）吗？\n" +
                        "这不会影响你已经下载完成的文件。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    // 递归删两个缓存目录是纯磁盘活，原来直接在点击回调（UI 线程）里做，
                    // 缓存大的时候点一下能卡住界面；现在放后台，删完把占用读回来
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
