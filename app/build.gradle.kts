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
        // 应用 ID 默认保持 com.gh.dl。需要与官方版并行安装（定制/修改版）时追加
        // -PappIdSuffix=.mod → com.gh.dl.mod：包名不同即视为两个独立应用，数据目录、
        // 通知渠道、SAF 授权与 AndroidKeyStore 各自独立；namespace 仍为 com.gh.dl，
        // 因此 R 类、Kotlin 包名与 libgit2jni.so 的 JNI 符号名均不受影响。
        applicationId = "com.gh.dl" + ((project.findProperty("appIdSuffix") as String?) ?: "")
        minSdk = 26
        targetSdk = 35
        // 当前版本号 1.4.5。versionCode 必须单调递增，
        // 与 versionName 不必同格式：… → 143 → 144 → 145。
        versionCode = 145
        versionName = "1.4.5"
        // 仅打包 arm64-v8a：内置的 curl / wget / aria2 / libgit2 为预编译原生库，
        // 仓库内无对应 C 源码，追加 x86_64 需先交叉编译出该 ABI 的四个可执行文件并放入
        // app/src/main/jniLibs/x86_64（编译方式见 docs/BUILD-INTERNALS.md「原生库」一节）。
        // 未打包的 ABI 上应用仍可安装运行：aria2 与 libgit2 在使用前探测并自动回退，
        // 抓取与下载走 OkHttp；仅 x86_64 独占设备（无 ARM 转译的旧 ChromeOS、部分模拟器）
        // 会因无匹配 ABI 无法安装。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // 自有生产签名：从 app/keystore/keystore.properties 读取（该文件已 gitignore，不纳入版本管理）。
    // 缺失时构建失败，不回退至 debug 签名。必须放在 buildTypes 之前，
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
        // 调试包：保持可调试状态，使用默认 debug 签名
        debug {
            isDebuggable = true
            // 验证包与正式版并存：包名加 .debug 后缀（com.gh.dl → com.gh.dl.debug）。
            // 包名不同意味着 Android 视为两个独立应用，不会覆盖安装或卸载正式版，
            // 两者的数据目录、通知渠道、SAF 授权、KeyStore 各自独立。
            // 注意：仅修改 applicationId，namespace 仍为 com.gh.dl，因此 R 类、
            // Kotlin 包名以及 libgit2jni.so 的 JNI 符号名均不受影响。
            applicationIdSuffix = ".debug"
        }
        // 发布包：关闭 debuggable + 开启 R8 混淆与资源收缩
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            // 使用 app/keystore/keystore.properties 中定义的自有生产签名（缺失时构建失败）。
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 离线环境无法拉取 lint 工具链（com.android.tools.lint:lint-gradle 无缓存），
    // 关闭 release 构建的 lint 检查以避免阻断打包。APK 正确性不受影响，联网后可使用 `gradle lint` 单独检查。
    lint {
        checkReleaseBuilds = false
        // 与上方 abiFilters 的取舍一致：x86_64 原生库需重新编译第三方工具链，无法在纯 Gradle 侧解决
        disable += "ChromeOsAbiSupport"
    }

    buildFeatures {
        // AGP 8 默认关闭 BuildConfig 生成。此处必须开启：
        // 「设置 → 关于」中的版本号原先为 Kotlin 字面量（"版本 1.0"），
        // 修改 build.gradle.kts 的 versionName 时界面不会同步更新，两处必然漂移。
        // 开启后界面读取 BuildConfig.VERSION_NAME / VERSION_CODE，build.gradle 成为唯一真值源。
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
        // extractNativeLibs=true（manifest）需要配套的 legacy 打包方式，否则部分 Android 版本
        // 不会将 libaria2c.so 解压到 nativeLibraryDir，导致 Aria2 无法找到二进制文件
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

    // 「浏览」功能的 HTML 解析与净化（纯 JVM 库，无 Android 依赖）。
    // 使用此库而非正则表达式切割 HTML：解析更可靠，且 README 属于不可信第三方内容，
    // 需使用 Safelist 白名单净化后才能嵌入本地页面，否则存在 XSS 风险。
    implementation("org.jsoup:jsoup:1.17.2")

    // 「设置 → 浏览 → 解析引擎 → 先修复再解析」选项使用的 WHATWG html5 规范解析器
    // （html5lib 的 JVM 对等物）。该依赖仅在用户选择该选项时经反射调用，但必须以
    // implementation 方式引入：反射仅使代码不硬依赖该类，运行时类仍需存在于 APK 中。
    // 该库体积约 300KB 且无传递依赖（icu4j/xom 均为 optional）。
    implementation("nu.validator.htmlparser:htmlparser:1.4")

    // 多线程分片下载引擎（OkDownload，纯 JVM 库；使用本地 AAR，支持离线编译）
    implementation(files("libs/okdownload-1.0.7.aar"))

    // 纯 Java 实现的 Git 库（4.5 为最后一个不依赖 java.nio.file 的稳定版本，适用于 Android）
    implementation("org.eclipse.jgit:org.eclipse.jgit:4.5.0.201609210915-r") {
        // 排除自带的 jsch（0.1.53 版本不支持 ed25519），替换为下方新版本
        exclude(group = "com.jcraft", module = "jsch")
    }
    // 纯 Java 的 SSH 客户端，支持 ed25519，用来直连 GitHub 22 端口
    implementation("com.github.mwiede:jsch:0.2.20")
    // ed25519 密钥要用到 BouncyCastle 的算法实现
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.bouncycastle:bcutil-jdk18on:1.77")
    implementation("org.slf4j:slf4j-api:1.7.36")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 本地 JVM 单元测试（搜索解析功能）。Android 单元测试的 android.jar 将 org.json 实现为 stub（"not mocked"），
    // 此处显式引入 org.json 真实实现，使 JSONObject/JSONArray 在单元测试中可用。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
