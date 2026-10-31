# ============================================================
# GitHub 下载器 (com.gh.dl) R8 混淆 / 资源收缩规则
# ------------------------------------------------------------
# 本项目 JSON 解析使用 Android 内置 org.json（JSONObject/JSONArray），
# 未引入 Gson、kotlinx-serialization、kotlin-reflect，因此这些库无需 keep。
# 本文件仅保留涉及「原生加载 / 运行期按需引用 / 反射」的第三方库，防止 R8 误删。
# 业务代码（com.gh.dl.**）允许被 R8 混淆与收缩；Manifest 中声明的
# MainActivity / CloneService 由 AGP 自动生成的 aapt keep 规则保护。
# ============================================================

# ---- OkDownload（本地 AAR，包名 com.liulishuo）----
-keep class com.liulishuo.** { *; }
-keep interface com.liulishuo.** { *; }
-dontwarn com.liulishuo.**

# ---- OkHttp / OkIo（Net、Lfs 以及 OkDownload 内部依赖）----
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ---- JGit（克隆功能，org.eclipse.jgit）----
-keep class org.eclipse.jgit.** { *; }
-keep interface org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**

# ---- JSch（mwiede:jsch，包名为 com.jcraft.jsch，用于 SSH 直连 GitHub）----
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ---- BouncyCastle（ed25519 等密钥算法实现）----
-keep class org.bouncycastle.** { *; }
-keep class org.bouncycastle.**$* { *; }
-dontwarn org.bouncycastle.**

# ---- SLF4J（JGit 运行期依赖，无绑定时回退至 NOP 日志，类引用必须存在）----
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# ---- WHATWG html5 解析器（nu.validator.htmlparser，「设置 → 浏览 → 先修复再解析」选项）----
# 必须 keep：代码通过 Class.forName 反射调用，R8 无法通过静态引用分析发现该依赖。
# 若未 keep，release 构建选择该选项时将抛出 ClassNotFoundException（debug 构建不混淆，无法复现）。
-keep class nu.validator.htmlparser.** { *; }
-keep class nu.validator.saxtree.** { *; }
# 该库引用的 icu4j / jchardet 在 pom 中均为 optional 依赖，本项目未引入：
# 仅「编码嗅探 / 规范化检查」路径会用到，而本项目输入始终为已解码的 String，
# 不会触及上述路径，因此仅压制告警，不补充依赖（补充将增加 10MB+ 体积）。
# XOM / iso-relax 输出后端同为 optional，本项目仅使用 SAX→字符串路径。
-dontwarn com.ibm.icu.**
-dontwarn org.mozilla.intl.chardet.**
-dontwarn nu.xom.**
-dontwarn org.iso_relax.**
-dontwarn com.thaiopensource.**

# ---- 保留泛型/签名/内部类/源码行号等属性（部分库通过反射读取类型信息）----
-keepattributes Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,*Annotation*

# ---- Kotlin 元数据 ----
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.**
