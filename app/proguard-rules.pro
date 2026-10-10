# ============================================================
# GitHub 下载器 (com.gh.dl) R8 混淆 / 资源收缩规则
# ------------------------------------------------------------
# 说明：本项目 JSON 解析全部使用 Android 内置 org.json（JSONObject/JSONArray），
#       未引入 Gson、kotlinx-serialization、kotlin-reflect。因此这些库无需 keep。
#       本文件只保留含「原生加载 / 运行期按需引用 / 反射」的第三方库，避免 R8 误删。
#       业务代码（com.gh.dl.**）允许被 R8 混淆与收缩；Manifest 中声明的
#       MainActivity / CloneService 由 AGP 自动生成的 aapt keep 规则保护。
# ============================================================

# ---- OkDownload（本地 aar，包名 com.liulishuo）----
-keep class com.liulishuo.** { *; }
-keep interface com.liulishuo.** { *; }
-dontwarn com.liulishuo.**

# ---- OkHttp / OkIo（Net、Lfs，以及 OkDownload 内部依赖）----
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ---- JGit（克隆功能，org.eclipse.jgit）----
-keep class org.eclipse.jgit.** { *; }
-keep interface org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**

# ---- JSch（mwiede:jsch，包名仍是 com.jcraft.jsch，SSH 直连 GitHub）----
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ---- BouncyCastle（ed25519 等密钥算法）----
-keep class org.bouncycastle.** { *; }
-keep class org.bouncycastle.**$* { *; }
-dontwarn org.bouncycastle.**

# ---- SLF4J（JGit 运行期需要，无绑定 -> NOP 日志，类引用必须存在）----
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# ---- WHATWG html5 解析器（nu.validator.htmlparser，「设置 → 浏览 → 先修复再解析」档）----
# 必须 keep：代码里是 Class.forName 反射调用它，R8 看不到静态引用边，
# 不 keep 的话 release 包一选这一档就 ClassNotFoundException（debug 包不混淆所以看不出来）。
-keep class nu.validator.htmlparser.** { *; }
-keep class nu.validator.saxtree.** { *; }
# 它引用的 icu4j / jchardet 在 pom 里都是 optional，我们不打进来：
# 只有「编码嗅探 / 规范化检查」那两条路径会用到，我们的输入永远是已解码的 String，
# 走不到那里，所以只压掉告警，不补依赖（补了要多 10MB+）。
# 同理，XOM / iso-relax 输出后端也是 optional，我们只用 SAX→字符串这一条路。
-dontwarn com.ibm.icu.**
-dontwarn org.mozilla.intl.chardet.**
-dontwarn nu.xom.**
-dontwarn org.iso_relax.**
-dontwarn com.thaiopensource.**

# ---- 保留泛型/签名/内部类/源码行号等属性（部分库用反射读类型）----
-keepattributes Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,*Annotation*

# ---- Kotlin 元数据 ----
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.**
