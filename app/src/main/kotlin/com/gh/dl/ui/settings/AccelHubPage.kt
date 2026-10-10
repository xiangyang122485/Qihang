package com.gh.dl.ui.settings

// 第五轮「加速拆分」：设置 → 加速 的二级导航页，下面挂两个三级页：
// 下载加速（ghproxy 风格文件前缀）与浏览加速（githubfast 风格网页镜像站）。
//
// 为什么必须拆成两套（实测，详见 GhView 类注释）：
//  - 文件前缀代理不了网页：把仓库主页交给 gh-proxy，它返回的是**它自己的首页**；
//  - 网页镜像只放行 git/curl 类 UA：浏览器 UA 一律 403。
// 所以两个开关各管各的，互不影响。
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
            "下载和浏览是两套互不相通的加速，各开各的：",
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
            "为什么不能共用一个开关（点击展开）",
            "下载用的前缀是「文件」中转，只认 /raw、/releases/download、/archive 这类地址；" +
                "把仓库主页交给它，它认不出是文件，就把它自己的首页发回来（实测 200、约 60KB，" +
                "标题写着「GitHub加速下载代理」）。\n" +
                "网页镜像站正好相反：它只放行 git / curl 这类命令行 UA，浏览器 UA 一律 403" +
                "（实测提示 To prevent abuse, only git and CLI access are allowed）。\n" +
                "两者机制不通，所以分成两档。顶栏那个闪电按所在页面生效：" +
                "解析器 / 搜索页里它切「下载加速」，浏览页里它切「浏览加速」。"
        )
    }
}
