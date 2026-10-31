package com.gh.dl.ui.settings

// 开源许可清单页。
//
// 本页满足以下法律要求的合规义务：APK 内含 GNU wget（GPL-3+，静态链接 OpenSSL、zlib、musl）
// 与 aria2c（GPL-2+）两份 copyleft 二进制。GPL 允许分发二进制，但须同时满足三项条件：
// ① 随附许可证全文（存放于 assets/licenses/，本页按需读取）；
// ② 逐项声明每个组件的许可证与保留条款（见下方 LIST）；
// ③ 告知接收方获取对应源码的途径（见 srcHint；自编译的 wget 将 configure 参数与校验和记录于
//    仓库 docs/BUILD-INTERNALS.md，该记录构成 GPL-3 s.6 所要求的源码提供）。
// App 自身不受 GPL 传染：wget 与 aria2c 均通过 exec 调用，属于独立程序（命令行边界），
// 而非链接进 App 代码的库，因此工程本身采用 MIT（见仓库根 LICENSE）。libgit2 采用
// 「GPLv2 + 链接例外」，该例外明文允许与非 GPL 程序组合分发，本工程据此使用 dlopen。
import com.gh.dl.data.Fetcher
import com.gh.dl.data.Threads
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

// 版本号来源：内置 curl 与 wget 取 Fetcher 常量（唯一真值源）；
// aria2 与 libgit2 的版本号从 APK 内二进制 strings 实测获得（上游未提供 Kotlin 侧常量），
// 其余 JVM 依赖按 build.gradle.kts 的 dependencies 块逐条列出。
private val LIST = listOf(
    Lic("GNU wget " + Fetcher.BUNDLED_WGET_VERSION, "GPL-3.0-or-later",
        "APK 内置（libwget.so，全静态：wget + OpenSSL + zlib + musl libc）", "GPL-3.0.txt",
        "上游 https://ftp.gnu.org/gnu/wget/ ；本 APK 所用构建的完整 configure 参数、源码与二进制 SHA256 见源码仓库 docs/BUILD-INTERNALS.md"),
    Lic("OpenSSL 3.5.9（静态链入 libwget.so）", "Apache-2.0",
        "已并入 libwget.so", "Apache-2.0.txt",
        "https://openssl.org/source/ ；取 Alpine v3.22 官方 openssl-libs-static 预编译静态库，校验和见 docs/BUILD-INTERNALS.md"),
    Lic("OpenSSL 1.1.1t（静态链入 libcurl.so）", "OpenSSL License + SSLeay License（双证）",
        "已并入 libcurl.so（上游 static-curl 自带，未改动）", "OPENSSL-OLD.txt",
        "https://www.openssl.org/source/old/1.1.1/ ；curl 本身是 MIT 式许可证，与旧 OpenSSL 证组合合法，按其要求保留下文致谢与全文"),
    Lic("curl " + Fetcher.BUNDLED_CURL_VERSION, "curl（MIT 式）",
        "APK 内置（libcurl.so）", "MIT.txt",
        "https://github.com/moparisthebest/static-curl ；经上游官方 sha256sum 核对"),
    Lic("aria2 1.37.0", "GPL-2.0-or-later（含 OpenSSL 链接例外）",
        "APK 内置（libaria2c.so，上游 arm64 静态构建，未修改；内嵌 OpenSSL 1.1.1w）", "GPL-2.0.txt",
        "https://github.com/aria2/aria2/releases ；例外原文摘录见 LINKING-EXCEPTIONS.txt"),
    Lic("libgit2 1.8.1", "GPL-2.0-only WITH libgit2 Linking Exception",
        "APK 内置（libgit2.so + libgit2jni.so；TLS 走 mbedTLS，未链接 OpenSSL）", "GPL-2.0.txt",
        "https://github.com/libgit2/libgit2 ；链接例外明文允许与任何程序组合分发，原文摘录见 LINKING-EXCEPTIONS.txt"),
    Lic("mbedTLS 3.5.2（静态链入 libgit2.so）", "Apache-2.0",
        "已并入 libgit2.so", "Apache-2.0.txt",
        "https://github.com/Mbed-TLS/mbedtls ；libgit2 的 TLS 后端，Apache-2.0 单证（亦提供 GPL-2.0 双证，本分发按 Apache-2.0）"),
    Lic("libssh2 1.11.1（静态链入 libgit2.so）", "BSD 风格许可",
        "已并入 libgit2.so", "BSD-3.txt",
        "https://www.libssh2.org/ ；上游 COPYING 为 BSD 风格条款，此处附 3 条款模板全文，署名以各源文件头为准"),
    Lic("llhttp（静态链入 libgit2.so）", "MIT",
        "已并入 libgit2.so", "MIT.txt",
        "https://github.com/nodejs/llhttp ；libgit2 的 HTTP 解析器"),
    Lic("zlib 1.3.2（静态链入 libwget.so / libgit2.so）", "zlib license", "已并入 libwget.so 与 libgit2.so", "ZLIB.txt",
        "https://zlib.net/ ；wget 取 Alpine v3.22 官方 zlib-static 预编译产物，libgit2 自行交叉编译时链入"),
    Lic("musl libc（libwget.so 的静态 C 库）", "MIT", "已并入 libwget.so", "MIT.txt",
        "https://musl.libc.org/"),
    Lic("OkHttp 4.12.0 / AndroidX / Compose / navigation", "Apache-2.0",
        "classes.dex", "Apache-2.0.txt",
        "https://github.com/square/okhttp 与 https://developer.android.com/jetpack/androidx"),
    Lic("Jsoup 1.17.2", "MIT", "classes.dex", "MIT.txt",
        "https://jsoup.org/"),
    Lic("htmlparser 1.4（nu.validator）", "MIT 与 BSD-3 双许可（按源文件头声明）", "classes.dex", "BSD-3.txt",
        "https://about.validator.nu/htmlparser/ ；上游 sources jar 中多数文件头为 Mozilla Foundation MIT，" +
            "rewindable 包两个文件为 Thai Open Source Software Center BSD-3，署名以各自文件头为准"),
    Lic("JGit 4.5.0", "Eclipse Distribution License 1.0（BSD 式）", "classes.dex", "EDL-1.0.txt",
        "https://www.eclipse.org/jgit/"),
    Lic("jsch 0.2.20（mwiede fork）", "Revised BSD（BSD-3 变体，含「不得使用作者名义背书」条款）",
        "classes.dex（jar 内自带三份 LICENSE.txt：主体、jzlib、jBCrypt）", "JSCH-BSD.txt",
        "https://github.com/mwiede/jsch ；jar 内嵌 jBCrypt 为 ISC 式许可，全文见 ISC.txt"),
    Lic("BouncyCastle bcprov / bcutil 1.77", "Bouncy Castle Licence（MIT 式）", "classes.dex", "BOUNCY-CASTLE.txt",
        "https://www.bouncycastle.org/"),
    Lic("slf4j 1.7.36", "MIT", "classes.dex", "MIT.txt", "https://www.slf4j.org/"),
    Lic("OkDownload 1.0.7", "Apache-2.0",
        "app/libs/okdownload-1.0.7.aar", "Apache-2.0.txt",
        "上游 https://github.com/liulishuo/okdownload ，坐标 com.liulishuo.okdownload:okdownload:1.0.7；" +
            "aar 内未附 LICENSE 文件，按上游仓库声明列出"),
)

/** 旧 OpenSSL 双证要求的广告式致谢，单独成段写在这里（全文见 OPENSSL-OLD.txt） */
private const val OPENSSL_ACK =
    "此产品包含由 OpenSSL 项目（https://www.openssl.org/）开发的软件，" +
        "以及由 Eric A. Young 与 Tim J. Hudson 开发的 SSLeay 软件。"

@Composable
fun LicensesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    // 许可证全文仅在用户点击时从 assets 读取。文件读取属于 IO 操作，在后台线程执行，
    // 结果通过 MAIN.post 回到主线程写入界面状态
    // （项目约定：界面状态仅从主线程写入，见 AppState.mirrorProbe 的注释）。
    var shown by remember { mutableStateOf<Pair<Lic, String>?>(null) }
    var loadingName by remember { mutableStateOf("") }
    var readErr by remember { mutableStateOf("") }

    fun load(lic: Lic) {
        readErr = ""; shown = null; loadingName = lic.name
        Threads.start("读取许可证") {
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
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubTitle("开源许可", onBack)

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("合规说明", fontWeight = FontWeight.Bold)
                Text(
                    "APK 内含 GNU wget 与 aria2c 两份 GPL 程序，均以 exec 调用独立进程，未链接进本应用代码，" +
                        "因此本应用以 MIT 发布（见仓库根 LICENSE）。分发上述二进制须随附许可证全文、" +
                        "逐项声明许可证、告知对应源码获取途径。点击下方条目查看全文；" +
                        "自编译组件（wget）的构建配方与校验和记录于源码仓库 docs/BUILD-INTERNALS.md，" +
                        "该记录即 GPL 意义上的源码要约。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(OPENSSL_ACK, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            }
        }

        GhCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("许可证变更与遗留事项", fontWeight = FontWeight.Bold)
                Text(
                    "wget 原静态链接 OpenSSL 1.1.1w。OpenSSL/SSLeay 双许可证被 FSF 列为与 GPL-3 不兼容，" +
                        "静态链入 GPL-3 程序后分发构成违规（aria2 具有明文链接例外，curl 采用 MIT 式许可证，不受影响）。" +
                        "现已重编为静态链接 OpenSSL 3.5.9（Apache-2.0，与 GPL-3 兼容），" +
                        "构建配方与校验和见 docs/BUILD-INTERNALS.md；二进制体积由 3.4 MB 增至 5.9 MB。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "libcurl.so 内嵌 OpenSSL 1.1.1t，libaria2c.so 内嵌 OpenSSL 1.1.1w。" +
                        "两者不涉及许可证问题，但 1.1.1 分支已停止安全维护，属遗留安全风险；" +
                        "替换需重做上游静态构建，当前版本未处理。",
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
            Text("正在读取 $loadingName 许可证全文…", fontSize = 11.sp,
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
                    // GPL-3 全文约 3.5 万字符，采用等宽小字号以保留原始排版
                    Text(txt, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "清单核对至版本 " + com.gh.dl.BuildConfig.VERSION_NAME + "。升级依赖或重编内置二进制时，须同步更新本页与 assets/licenses/。",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.outline
        )
    }
}
