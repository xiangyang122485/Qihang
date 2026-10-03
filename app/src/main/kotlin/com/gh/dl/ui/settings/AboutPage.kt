package com.gh.dl.ui.settings

// 阶段1 纯拆分：原 SettingsTab.kt 的「AboutPage」二级页。逻辑零改动。
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
fun AboutPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("关于与合规说明", onBack)

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("这个 App 做什么", fontWeight = FontWeight.Bold)
                Text(
                    "粘贴 GitHub 公开仓库链接 → 列出仓库的 Release 软件 → 下载；" +
                        "同时提供开源源码下载和整仓克隆。只用于下载公开开源资源。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text("它连的是什么", fontWeight = FontWeight.Bold)
                Text(
                    "① GitHub 官方 API / codeload（https 直连，不通就跳过）\n" +
                        "② 国内镜像站同步的公开 Release 产物（中科大 / 南大 / 清华 / 华为云 / 北京理工 / 上海交大）\n" +
                        "③ jsDelivr 公共 CDN 上的仓库源码\n" +
                        "④ GitHub 官方 SSH 服务（22 端口，直连官方真实 IP）\n" +
                        "⑤ 加速代理（可选，默认关闭）：ghproxy 类第三方反向代理前缀\n" +
                        "可选：你自己填的 GitHub Token / HTTP 代理。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text("它不做什么", fontWeight = FontWeight.Bold)
                Text(
                    "不内置任何翻墙、隧道能力，也不上传你的密钥。\n" +
                        "不提供网页浏览、账号登录、代码推送等能力，只用于下载公开开源资源。\n" +
                        "克隆只走 GitHub 官方 SSH 服务（22 端口），不经过任何代理或中转。\n" +
                        "加速代理只转发公开开源资源：你的 GitHub Token 只发给 GitHub 官方域名，" +
                        "绝不经过镜像 / 代理服务器；不支持私有仓库与 SSH 协议。\n" +
                        "开启加速代理后下载经第三方服务器，它可能替换文件内容——" +
                        "所以默认强制 SHA256 校验；是否使用请自行评估风险。\n" +
                        "SSH 私钥只存在手机本地应用目录，公钥需要你手动加到 GitHub。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text("文件权限", fontWeight = FontWeight.Bold)
                Text(
                    "只申请网络权限。写文件用系统文件授权（SAF）：" +
                        "你亲手挑哪个文件夹，App 就只有那一个文件夹的权限。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text("校验建议", fontWeight = FontWeight.Bold)
                Text(
                    "下载完拿 SHA256 比对；镜像通道的「校验」按钮可复制官方校验值，" +
                        "开启加速代理时 App 会自动比对并提示是否被篡改。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text("版本 1.0 · 包名 " + ctx.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
