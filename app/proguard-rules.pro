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

# ---- 保留泛型/签名/内部类/源码行号等属性（部分库用反射读类型）----
-keepattributes Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,*Annotation*

# ---- Kotlin 元数据 ----
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.**
