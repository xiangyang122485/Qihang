package com.gh.dl.ui.settings

// 自原 SettingsTab.kt 拆分而来的「关于」二级页。业务逻辑未作改动。
import com.gh.dl.ui.*
import com.gh.dl.data.*

import android.content.ClipboardManager
import android.content.Context
import com.gh.dl.BuildConfig
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
fun AboutPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("关于与合规说明", onBack)

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("功能概述", fontWeight = FontWeight.Bold)
                Text(
                    "粘贴 GitHub 公开仓库链接，列出其 Release 产物供下载，并支持源码包下载与整仓克隆。" +
                        "仅用于下载公开开源资源。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text("网络连接目标", fontWeight = FontWeight.Bold)
                Text(
                    "① GitHub 官方 API / codeload（HTTPS 直连，不可达时自动跳过）\n" +
                        "② 国内镜像站同步的公开 Release 产物（中科大 / 南大 / 清华 / 华为云 / 北京理工 / 上海交大）\n" +
                        "③ jsDelivr 公共 CDN 上的仓库源码\n" +
                        "④ GitHub 官方 SSH 服务（22 端口，直连官方真实 IP）\n" +
                        "⑤ 加速代理（可选，默认关闭）：ghproxy 类第三方反向代理前缀\n" +
                        "可选配置：用户自行填写的 GitHub Token / HTTP 代理。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text("功能边界与安全", fontWeight = FontWeight.Bold)
                Text(
                    "不含隧道、代理或第三方中转功能；默认仅访问 GitHub 官方端点，不上传 Token 与 SSH 私钥。\n" +
                        "不提供网页浏览、账号登录、代码推送功能，仅用于下载公开开源资源。\n" +
                        "克隆经 GitHub 官方 SSH 服务（22 端口）直连，不经代理或中转；不支持私有仓库。\n" +
                        "加速代理仅转发公开开源资源，GitHub Token 不经镜像站或代理服务器。\n" +
                        "开启加速代理后下载流量经第三方中转，该服务器可能替换文件内容，因此默认启用 SHA256 校验。\n" +
                        "SSH 私钥仅存储于本机应用目录，公钥需手动添加至 GitHub。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text("文件权限", fontWeight = FontWeight.Bold)
                Text(
                    "仅申请网络权限。文件写入采用系统文件授权框架（SAF）：" +
                        "用户选择授权文件夹后，应用仅获得该文件夹的读写权限。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text("完整性校验", fontWeight = FontWeight.Bold)
                Text(
                    "下载完成后可比对 SHA256：镜像通道的「校验」按钮复制官方校验值，" +
                        "开启加速代理时应用自动比对并提示内容是否被替换。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        // 版本号读取自 BuildConfig（唯一真值源位于 app/build.gradle.kts），
        // 不再使用硬编码字面量。
        Text(
            "版本 " + BuildConfig.VERSION_NAME + "（构建 " + BuildConfig.VERSION_CODE + "）" +
                (if (BuildConfig.DEBUG) " · 验证包" else "") +
                " · 包名 " + ctx.packageName,
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
