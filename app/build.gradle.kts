import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gh.dl"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gh.dl"
        minSdk = 26
        targetSdk = 35
        // 第八轮：版本号 1.2.1（用户指定）。versionCode 必须单调递增，
        // 与 versionName 不必同格式：10 → 121 留出行内小改的空间（1.2.2 = 122）。
        versionCode = 121
        versionName = "1.2.1"
        // 硬约束：只出 arm64-v8a 一个 ABI（按验收标准，APK 内仅保留 arm64-v8a 的 .so）
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // 自有生产签名：从 app/keystore/keystore.properties 读取（该文件已 gitignore，绝不入库）。
    // **缺失时直接报错**，绝不回退 debug 签名：误发 debug 签名的包任何人都能用公开
    // debug 私钥重打包同名应用，且用户无法覆盖安装旧正式版。必须放在 buildTypes 之前，
    // 否则 buildTypes.release 引用 getByName("release") 时该配置尚未创建。
    signingConfigs {
        create("release") {
            val propsFile = file("keystore/keystore.properties")
            if (propsFile.exists()) {
                val props = Properties().apply {
                    propsFile.inputStream().use { load(it) }
                }
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            } else {
                throw GradleException(
                    "缺少 app/keystore/keystore.properties：release 构建必须使用自有签名，禁止回退 debug 签名。请把 keystore.properties 与 release.keystore 放到 app/keystore/（已被 .gitignore 排除，不会入库）。"
                )
            }
        }
    }

    buildTypes {
        // 调试包：保持可调试，便于真机 adb 安装回归（沿用默认 debug 签名）
        debug {
            isDebuggable = true
            // 验证包与正式版并存：包名加 .debug 后缀（com.gh.dl → com.gh.dl.debug）。
            // 包名不同 = Android 眼里的两个不同应用，所以不会覆盖安装、不会卸载正式版，
            // 两者的数据目录/通知渠道/SAF 授权/KeyStore 也各自独立，互不干扰。
            // 注意：只改 applicationId，namespace 仍是 com.gh.dl —— 所以 R 类、
            // Kotlin 包名、以及 libgit2jni.so 的 JNI 符号名都不受影响，不用改一行代码。
            applicationIdSuffix = ".debug"
        }
        // 发布包：关闭 debuggable + 开启 R8 混淆与资源收缩
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            // 使用 app/keystore/keystore.properties 中定义的自有生产签名（缺失时回退默认 debug）。
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 离线沙箱无法拉取 lint 工具链（com.android.tools.lint:lint-gradle 无缓存），
    // 关闭 release 的 vital lint 以免阻断打包；APK 正确性不受影响，联网后可用 `gradle lint` 单独检查。
    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        // AGP 8 默认关掉 BuildConfig 生成。这里必须打开：
        // 「设置 → 关于」那一行的版本号原先是写死在 Kotlin 里的字面量（"版本 1.0"），
        // 于是改 build.gradle.kts 的 versionName 时界面不会跟着变，两处必然漂移。
        // 打开后界面读 BuildConfig.VERSION_NAME / VERSION_CODE，只有 build.gradle 一个真值源。
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // extractNativeLibs=true（manifest）需要配套的 legacy 打包，否则部分 Android 版本
        // 不会把 libaria2c.so 解压到 nativeLibraryDir，导致 Aria2 找不到二进制
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        excludes += "/META-INF/DEPENDENCIES"
        excludes += "/META-INF/LICENSE"
        excludes += "/META-INF/LICENSE.txt"
        excludes += "/META-INF/NOTICE"
        excludes += "/META-INF/NOTICE.txt"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 「浏览」的 HTML 解析与净化（纯 JVM 库，无 Android 依赖）。
    // 用它而不是正则切 HTML：一是解析可靠，二是 README 属于不可信第三方内容，
    // 要用 Safelist 白名单净化后才能塞进本地页面，否则等于给自己开 XSS。
    implementation("org.jsoup:jsoup:1.17.2")

    // 「设置 → 浏览 → 解析引擎 → 先修复再解析」这一档用的 WHATWG html5 规范解析器
    // （html5lib 的 JVM 对等物）。它只在用户选那一档时经反射调用，但依赖必须是 compileOnly
    // 还是 implementation？——必须 implementation：反射只是让**代码**不硬依赖它，
    // 运行时类得真在 APK 里。它只有 300KB 且无传递依赖（icu4j/xom 都是 optional）。
    implementation("nu.validator.htmlparser:htmlparser:1.4")

    // 多线程分片下载引擎（OkDownload，纯 JVM，用于提速；用的是本地 aar，离线可编）
    implementation(files("libs/okdownload-1.0.7.aar"))

    // 纯 Java 的 git 实现（4.5 是最后一个不依赖 java.nio.file 的稳定版，Android 可用）
    implementation("org.eclipse.jgit:org.eclipse.jgit:4.5.0.201609210915-r") {
        // 它自带的 jsch 太老（0.1.53，不支持 ed25519），换成下面新的那个
        exclude(group = "com.jcraft", module = "jsch")
    }
    // 纯 Java 的 SSH 客户端，支持 ed25519，用来直连 GitHub 22 端口
    implementation("com.github.mwiede:jsch:0.2.20")
    // ed25519 密钥要用到 BouncyCastle 的算法实现
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.bouncycastle:bcutil-jdk18on:1.77")
    implementation("org.slf4j:slf4j-api:1.7.36")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 本地 JVM 单测（搜索解析）。Android 单测的 android.jar 把 org.json 做成 stub（"not mocked"），
    // 这里显式引入 org.json 真实实现，让 JSONObject/JSONArray 在单测里真正可用。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
