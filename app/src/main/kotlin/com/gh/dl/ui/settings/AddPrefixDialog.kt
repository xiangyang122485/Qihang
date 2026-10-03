package com.gh.dl.ui.settings

// 阶段1 纯拆分：原 SettingsTab.kt 的「AddPrefixDialog」二级页。逻辑零改动。
import com.gh.dl.ui.*
import com.gh.dl.data.*

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AddPrefixDialog(onClose: () -> Unit) {
    val vmSettings = LocalSettingsVM.current

    val ctx = LocalContext.current
    var manual by remember { mutableStateOf("") }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = try {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
            } catch (e: Exception) { "" }
            val found = vmSettings.parsePrefixes(text)
            if (found.isEmpty()) {
                toast(ctx, "这个文件里没找到 http(s) 前缀")
            } else {
                vmSettings.addAccelPrefixes(ctx, found)
                if (vmSettings.custom.isBlank()) vmSettings.custom = found.first()
                toast(ctx, "已添加 ${found.size} 个前缀")
                onClose()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("添加加速前缀") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择添加方式（前缀需以 http:// 或 https:// 开头）：", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    fileLauncher.launch(arrayOf("text/*", "application/json", "*/*"))
                }) { Text("本地文件导入（txt / json / md）") }
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val t = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    val found = vmSettings.parsePrefixes(t)
                    if (found.isEmpty()) {
                        toast(ctx, "剪贴板里没有 http(s) 前缀")
                    } else {
                        vmSettings.addAccelPrefixes(ctx, found)
                        if (vmSettings.custom.isBlank()) vmSettings.custom = found.first()
                        toast(ctx, "已添加 ${found.size} 个前缀")
                        onClose()
                    }
                }) { Text("从剪贴板导入") }
                Text("手动输入：", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://gh-proxy.com（可多行，每行一个）", fontSize = 12.sp) },
                    minLines = 3
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val found = vmSettings.parsePrefixes(manual)
                if (found.isEmpty()) {
                    toast(ctx, "请输入以 http:// 或 https:// 开头的前缀")
                    return@Button
                }
                vmSettings.addAccelPrefixes(ctx, found)
                if (vmSettings.custom.isBlank()) vmSettings.custom = found.first()
                toast(ctx, "已添加 ${found.size} 个前缀")
                onClose()
            }) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("取消") }
        }
    )
}
