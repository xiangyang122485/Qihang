package com.gh.dl.ui.settings

// 第九轮新增：开源许可清单页。
//
// 为什么这一页是法律要求而不是礼貌性致谢：APK 里带着 GNU wget（GPL-3+，静态链 OpenSSL+zlib+musl）
// 与 aria2c（GPL-2+）两份 copyleft 二进制。GPL 允许分发二进制，但必须同时做到三件事：
// ① 随附许可证全文（→ assets/licenses/，本页按需读出）；
// ② 如实声明每个组件的许可证与保留条款（→ 下面 LIST）；
// ③ 告知接收方从哪里拿对应源码（→ srcHint；自编译的 wget 把 configure 参数与校验和写进
//    仓库 docs/BUILD-INTERNALS.md，「源码即仓库」加上那份构建记录构成 GPL-3 s.6 说的源码提供）。
// 注意 App 自身不受 GPL 传染：wget / aria2c 都是 exec 出去的**独立程序**（命令行边界），
// 不是链接进 App 代码的库，所以工程本身用 MIT（见仓库根 LICENSE）。libgit2 是
// 「GPLv2 + 链接例外」，例外明文允许与非 GPL 程序组合分发，本工程正是靠它才敢 dlopen。
import com.gh.dl.data.Fetcher
import com.gh.dl.ui.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一条许可记录。[textFile] 是 assets/licenses 下的全文文件名。
 * [srcHint] 告诉接收方从哪里拿这份组件的对应源码（GPL 的源码要约）。
 */
private data class Lic(
    val name: String,
    val license: String,
    val inApk: String,
    val textFile: String,
    val srcHint: String,
)

// 版本号口径：内置 curl/wget 取 Fetcher 常量（唯一真值源）；
// aria2 / libgit2 这两个数字是本轮从 APK 内二进制 strings 实测的（上游没有 Kotlin 侧常量），
// 其余 JVM 依赖照 build.gradle.kts 的 dependencies 块逐条列。
private val LIST = listOf(
    Lic("GNU wget " + Fetcher.BUNDLED_WGET_VERSION, "GPL-3.0-or-later",
        "APK 内置（libwget.so，全静态：wget + OpenSSL + zlib + musl libc）", "GPL-3.0.txt",
        "上游 https://ftp.gnu.org/gnu/wget/ ；本 APK 所用构建的完整 configure 参数、源码与二进制 SHA256 见源码仓库 docs/BUILD-INTERNALS.md"),
    Lic("OpenSSL 3.5.9（静态链入 libwget.so）", "Apache-2.0",
        "已并入 libwget.so", "Apache-2.0.txt",
        "https://openssl.org/source/ ；第九轮从 1.1.1w 换成 3.5.9——旧 OpenSSL 许可证与 GPL 不兼容（FSF license-list 明载），且 1.1.1 已停止安全维护。取 Alpine v3.22 官方 openssl-libs-static 预编译静态库，校验和见 docs/BUILD-INTERNALS.md"),
    Lic("OpenSSL 1.1.1t（静态链入 libcurl.so）", "OpenSSL License + SSLeay License（双证）",
        "已并入 libcurl.so（上游 static-curl 自带，未改动）", "OPENSSL-OLD.txt",
        "https://www.openssl.org/source/old/1.1.1/ ；curl 本身是 MIT 式许可证，与旧 OpenSSL 证组合合法，按其要求保留下文致谢与全文"),
    Lic("curl " + Fetcher.BUNDLED_CURL_VERSION, "curl（MIT 式）",
        "APK 内置（libcurl.so）", "MIT.txt",
        "https://github.com/moparisthebest/static-curl （官方 sha256sum 逐字节核对后引入）"),
    Lic("aria2 1.37.0", "GPL-2.0-or-later（含 OpenSSL 链接例外）",
        "APK 内置（libaria2c.so，上游 arm64 静态构建，未修改；内嵌 OpenSSL 1.1.1w）", "GPL-2.0.txt",
        "https://github.com/aria2/aria2/releases ；例外原文摘录见 LINKING-EXCEPTIONS.txt"),
    Lic("libgit2 1.8.1", "GPL-2.0-only WITH libgit2 Linking Exception",
        "APK 内置（libgit2.so + libgit2jni.so；TLS 走 mbedTLS，未链接 OpenSSL）", "GPL-2.0.txt",
        "https://github.com/libgit2/libgit2 ；链接例外明文允许与任何程序组合分发，原文摘录见 LINKING-EXCEPTIONS.txt"),
    Lic("zlib 1.3.2（静态链入 libwget.so）", "zlib license", "已并入 libwget.so", "ZLIB.txt",
        "https://zlib.net/ ；同样取 Alpine v3.22 官方 zlib-static 预编译产物"),
    Lic("musl libc（libwget.so 的静态 C 库）", "MIT", "已并入 libwget.so", "MIT.txt",
        "https://musl.libc.org/"),
    Lic("OkHttp 4.12.0 / AndroidX / Compose / navigation", "Apache-2.0",
        "classes.dex", "Apache-2.0.txt",
        "https://github.com/square/okhttp 与 https://developer.android.com/jetpack/androidx"),
    Lic("Jsoup 1.17.2", "MIT", "classes.dex", "MIT.txt",
        "https://jsoup.org/"),
    Lic("htmlparser 1.4（nu.validator）", "MIT 与 BSD-3 双许可（按源文件头声明）", "classes.dex", "BSD-3.txt",
        "https://about.validator.nu/htmlparser/ ；上游 sources jar 里绝大多数文件头是 Mozilla Foundation 的 MIT，" +
            "rewindable 包那两文件是 Thai Open Source Software Center 的 BSD-3，署名按各自文件头为准"),
    Lic("JGit 4.5.0", "Eclipse Distribution License 1.0（BSD 式）", "classes.dex", "EDL-1.0.txt",
        "https://www.eclipse.org/jgit/"),
    Lic("jsch 0.2.20（mwiede fork）", "Revised BSD（BSD-3 变体，含「不得使用作者名义背书」条款）",
        "classes.dex（jar 内自带三份 LICENSE.txt：主体、jzlib、jBCrypt）", "JSCH-BSD.txt",
        "https://github.com/mwiede/jsch ；jar 内嵌的 jBCrypt 是 ISC 式许可，全文见 ISC.txt"),
    Lic("BouncyCastle bcprov / bcutil 1.77", "Bouncy Castle Licence（MIT 式）", "classes.dex", "BOUNCY-CASTLE.txt",
        "https://www.bouncycastle.org/"),
    Lic("slf4j 1.7.36", "MIT", "classes.dex", "MIT.txt", "https://www.slf4j.org/"),
    Lic("OkDownload 1.0.7", "Apache-2.0",
        "app/libs/okdownload-1.0.7.aar（aar 内未附 LICENSE 文件，按上游仓库声明列出）", "Apache-2.0.txt",
        "上游 https://github.com/liulishuo/okdownload ，坐标 com.liulishuo.okdownload:okdownload:1.0.7；本 aar 是第一轮按你给的 Maven 地址下载并核对过 classes.jar 内容后放入仓库的"),
)

/** 旧 OpenSSL 双证要求的广告式致谢，单独成段写在这里（全文见 OPENSSL-OLD.txt） */
private const val OPENSSL_ACK =
    "此产品包含由 OpenSSL 项目（https://www.openssl.org/）开发的软件，" +
        "以及由 Eric A. Young 与 Tim J. Hudson 开发的 SSLeay 软件。"

@Composable
fun LicensesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    // 许可证全文只在点开时读 assets。读盘是 IO：放后台线程，**结果回主线程再写**
    // （项目约定：界面状态只从主线程写，见 AppState.mirrorProbe 的注释）。
    var shown by remember { mutableStateOf<Pair<Lic, String>?>(null) }
    var loadingName by remember { mutableStateOf("") }
    var readErr by remember { mutableStateOf("") }

    fun load(lic: Lic) {
        readErr = ""; shown = null; loadingName = lic.name
        Thread {
            val result = runCatching {
                ctx.assets.open("licenses/" + lic.textFile).reader(Charsets.UTF_8).use { it.readText() }
            }
            MAIN.post {
                loadingName = ""
                if (result.isSuccess) {
                    shown = lic to result.getOrDefault("")
                } else {
                    readErr = "许可证全文读取失败：${result.exceptionOrNull()?.message}"
                }
            }
        }.start()
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("开源许可", onBack)

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("这一页在履行什么义务", fontWeight = FontWeight.Bold)
                Text(
                    "APK 里有 GNU wget 与 aria2c 两份 GPL 程序（都是 exec 调用的独立程序，" +
                        "不是链接进本应用代码的库——所以本应用自身用 MIT 发布，见仓库根 LICENSE）。" +
                        "分发这些二进制要求：随附许可证全文、逐项声明许可证、告知对应源码从哪拿。" +
                        "下面每一条都可点开看全文；自编译组件（wget）的构建配方与校验和写在源码仓库 " +
                        "docs/BUILD-INTERNALS.md，源码仓库本身就是 GPL 意义上的「源码要约」。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(OPENSSL_ACK, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            }
        }

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("本轮改掉的一处许可证缺陷，以及还没改的那处", fontWeight = FontWeight.Bold)
                Text(
                    "改掉了：第八轮打进 APK 的 wget 静态链接的是 OpenSSL 1.1.1w。旧 OpenSSL 的" +
                        "OpenSSL/SSLeay 双许可证被 FSF 明确列为与 GPL 不兼容，把它静态链进 GPL-3 程序再分发" +
                        "是实打实的违规（aria2 有一份明文链接例外、curl 本身是 MIT 式许可，那两个不受影响）。" +
                        "第九轮重编为静态链 OpenSSL 3.5.9（Apache-2.0，与 GPL-3 兼容），顺带解决 1.1.1 已停止" +
                        "安全维护的问题；构建配方与全部校验和见源码仓库 docs/BUILD-INTERNALS.md。" +
                        "代价是这颗二进制从 3.4 MB 涨到 5.9 MB。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "还没改：libcurl.so 内嵌 OpenSSL 1.1.1t、libaria2c.so 内嵌 OpenSSL 1.1.1w。" +
                        "两处都不涉及许可证问题（curl 是 MIT 式许可，aria2 带 OpenSSL 链接例外），" +
                        "但 1.1.1 分支已停止安全维护，属于遗留的安全债。换掉意味着要重做上游静态构建，" +
                        "本轮没动它，如实写在这里，不做「已全部升级」的暗示。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LIST.forEach { lic ->
            GhCard(Modifier.fillMaxWidth().clickable { load(lic) }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(lic.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("许可证：" + lic.license, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary)
                    Text(lic.inApk, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("对应源码：" + lic.srcHint, fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (loadingName.isNotBlank()) {
            Text("正在读取「$loadingName」的全文…", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (readErr.isNotBlank()) {
            Text(readErr, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
        shown?.let { (lic, txt) ->
            GhCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(lic.name + " · " + lic.license,
                            fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            modifier = Modifier.weight(1f))
                        Text("收起", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { shown = null })
                    }
                    // GPL-3 全文 3.5 万字符，等宽小字保住原排版
                    Text(txt, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "以上清单随版本 " + com.gh.dl.BuildConfig.VERSION_NAME + " 核对；升级依赖或重编内置二进制时，请同步更新本页与 assets/licenses/。",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.outline
        )
    }
}
