package com.gh.dl.ui.settings

// 阶段拆分：使用文档二级页，与 AboutPage 同属「关于」分组，逻辑零依赖。
import com.gh.dl.ui.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App 内置使用文档（纯本地，无网络）。
 * 目标：让用户不看站外说明也能把主要功能用起来——覆盖三大 Tab、下载一个 Release、
 * 下载页进度含义、克隆（SSH 公钥怎么加）、网络/加速、存储授权、合规安全。
 * 文案严格对应已实装功能，不夸大、不杜撰未实现的开关。
 */
@Composable
fun DocsPage(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("使用文档", onBack)

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("这个 App 做什么", fontWeight = FontWeight.Bold)
                Text(
                    "粘贴 GitHub 公开仓库链接，列出并下载它的 Release 软件、源码压缩包，也能整仓克隆。" +
                        "只用于下载公开开源资源，不内置任何翻墙或隧道能力，也不上传你的密钥。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionHeader("一、底部四个 Tab")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("解析器", "粘贴仓库链接，解析出 Release 版本、国内镜像源、jsDelivr 源码，逐项点下载。")
                DocLine("下载", "所有下载任务的列表：看进度/速度、暂停/继续/重试/删除、复制直链/分享。")
                DocLine("搜索", "搜 GitHub 公开仓库（含国内镜像搜索），结果卡也能直接下载。")
                DocLine("设置", "网络、下载、克隆、关于与本文档的入口。")
            }
        }

        SectionHeader("二、下载一个 Release（最常见用法）")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DocStep(1, "底部切到「解析器」。")
                DocStep(2, "粘贴仓库地址（如 https://github.com/owner/repo ），或点右上角「+」手动添加链接。")
                DocStep(3, "等待解析完成，页面会列出「Release 版本」「国内镜像源」「源码（jsDelivr）」等分区。")
                DocStep(4, "点某个资产右边的「下载」，自动跳到下载页开始下载。")
                DocStep(5, "想一次下多个：右上角「批量下载」→ 勾选需要的项 → 点「下载(n)」。")
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    "提示：下载会在你用系统文件选择器挑好的文件夹里落地；若还没选过文件夹，" +
                        "首次下载会弹出系统选择器，挑一个即可（App 只拥有那一个文件夹的权限）。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionHeader("三、下载页进度怎么看")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("已完成", "进度条画满（100%），不再走动。")
                DocLine("下载中且大小已知", "显示「已下 / 总大小 · xx%」与实时速度。Release、镜像源、jsDelivr 文件都有确切大小。")
                DocLine("下载中且大小未知", "显示「总大小未知」并用走动条表示在下载中。源码 ZIP 走 codeload 分块传输，拿不到总长度，属正常情况；下完会显示 100%。")
                DocLine("失败 / 暂停", "不显示进度条，会给出原因或「已暂停」状态，可重试或继续。")
            }
        }

        SectionHeader("四、搜索")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "底部切到「搜索」，输入关键字回车即可列出 GitHub 公开仓库（含国内镜像搜索）。" +
                        "每条结果卡提供「下载 / 复制直链 / 分享」三个按钮，点「下载」会进入解析器继续。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionHeader("五、克隆整仓（可选）")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("开启", "设置 → 克隆，打开总开关；解析页会多出整仓克隆入口。")
                DocLine("SSH 方式", "设置 → 克隆 → SSH 密钥管理，复制 App 生成的公钥，到 GitHub「Settings → SSH and GPG keys → New SSH key」粘贴添加。克隆走 GitHub 官方 22 端口，不经任何代理或中转。")
                DocLine("HTTPS 方式", "可填写 GitHub Token（仅发给 GitHub 官方域名），用于带鉴权访问，不支持私有仓库/SSH 协议。")
            }
        }

        SectionHeader("六、网络与加速")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("默认通道", "优先直连 GitHub 官方 API / codeload；不通时自动回退到国内高校镜像（中科大、南大、清华、华为云等）与 jsDelivr 公共 CDN。")
                DocLine("加速代理（可选，默认关闭）", "仅转发公开开源资源。开启后默认强制 SHA256 校验，防止第三方服务器替换文件内容——是否使用请自行评估风险。GitHub Token 只发给官方域名，绝不经过镜像或代理。")
            }
        }

        SectionHeader("七、合规与安全")
        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocLine("不做什么", "不内置翻墙/隧道；不上传你的密钥；只用于下载公开开源资源。")
                DocLine("校验建议", "下载完可拿官方 SHA256 比对；镜像通道提供「校验」按钮可复制官方校验值，开启加速代理时 App 会自动比对并提示是否被篡改。")
                DocLine("存储授权", "只申请网络权限；写文件用系统文件授权（SAF），你亲手挑哪个文件夹，App 就只拥有那一个文件夹的权限。")
            }
        }

        Spacer(Modifier.padding(bottom = 4.dp))
        Text(
            "还有疑问？去「设置 → 关于」查看完整合规说明，或到仓库 Issues 反馈。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 文档小标题 + 正文一行 */
@Composable
private fun DocLine(title: String, body: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 有序步骤：① ② ③… */
@Composable
private fun DocStep(n: Int, text: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            "$n. ",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}
