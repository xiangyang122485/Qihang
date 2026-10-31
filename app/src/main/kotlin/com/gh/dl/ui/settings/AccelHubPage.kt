package com.gh.dl.ui.settings

// 「加速拆分」：设置 → 加速的二级导航页，下辖两个三级页：
// 下载加速（ghproxy 风格文件前缀）与浏览加速（githubfast 风格网页镜像站）。
//
// 二者必须拆分为两套（详见 GhView 类注释）：
//  - 文件前缀无法代理网页：将仓库主页交由 gh-proxy 会返回其自身首页；
//  - 网页镜像仅放行 git/curl 类 UA：浏览器 UA 返回 403。
// 因此两个开关各自独立，互不影响。
import com.gh.dl.ui.*
import com.gh.dl.data.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController

@Composable
fun AccelHubPage(nav: NavHostController, onBack: () -> Unit) {
    val vm = LocalSettingsVM.current

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("加速", onBack)

        Text(
            "下载与浏览使用两套独立的加速机制，分别控制：",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        NavRow(
            icon = Icons.Filled.Download,
            title = "下载加速",
            subtitle = (if (vm.accelOn) "已开启" else "未开启") +
                " · " + (vm.custom.ifBlank { "未设置前缀" }) +
                " · ${vm.accelPrefixes.size} 个已存前缀",
            onClick = { nav.navigate(SettingsRoute.AccelDownload.route) }
        )
        NavRow(
            icon = Icons.Filled.Language,
            title = "浏览加速",
            subtitle = (if (vm.browseAccelOn) "已开启" else "未开启") +
                " · 网页镜像站 " + GhView.pageMirrors.joinToString("、") { it.removePrefix("https://") },
            onClick = { nav.navigate(SettingsRoute.AccelBrowse.route) }
        )

        CollapsibleNote(
            "两档机制差异",
            "下载前缀是文件中转，仅识别 /raw、/releases/download、/archive 类地址，" +
                "处理仓库主页时返回其自身首页。\n" +
                "网页镜像站仅放行 git / curl 类命令行 UA，浏览器 UA 返回 403。\n" +
                "两者不可互换，故分为两档。顶栏闪电图标按所在页面生效：" +
                "解析器与搜索页切换「下载加速」，浏览页切换「浏览加速」。"
        )
    }
}
