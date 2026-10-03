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
        versionCode = 10
        versionName = "1.0"
        // 硬约束：只出 arm64-v8a 一个 ABI（按验收标准，APK 内仅保留 arm64-v8a 的 .so）
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // 自有生产签名：从 app/keystore/keystore.properties 读取（该文件已 gitignore，绝不入库）。
    // 缺失时回退到默认 debug 签名（仅本地侧载，勿上架）。必须放在 buildTypes 之前，
    // 否则 buildTypes.release 引用 getByName("release") 时该配置尚未创建。
    signingConfigs {
        val scs = this
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
                val d = scs.getByName("debug")
                storeFile = d.storeFile
                storePassword = d.storePassword
                keyAlias = d.keyAlias
                keyPassword = d.keyPassword
            }
        }
    }

    buildTypes {
        // 调试包：保持可调试，便于真机 adb 安装回归（沿用默认 debug 签名）
        debug {
            isDebuggable = true
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
