import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.am.music"
    compileSdk = 36

    // ---- 更新源：GitHub Releases ----
    // 更新走一个**公开**的 releases 仓库（只放 APK + 版本说明），源码仍留在私有仓库。
    // 公开仓库匿名就能读，所以正式包里**不带任何 token**；token 只在 debug 构建里注入，
    // 方便本机直接对着私有仓库调试（来源：local.properties 的 am.githubToken 或 -PAMMUSIC_GH_TOKEN）。
    val localProps = Properties().also { props ->
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { stream -> props.load(stream) }
    }
    val ghToken = (project.findProperty("AMMUSIC_GH_TOKEN") as String?)
        ?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty("am.githubToken", "").trim()

    defaultConfig {
        applicationId = "com.am.music"
        minSdk = 26
        // 故意对齐参考项目（targetSdk 34）：targetSdk 35/36 上 overlay 悬浮窗的触摸行为
        // 与 34 不同（真机上表现为「看得见但点不动」），先回到 34 验证再决定要不要升。
        targetSdk = 34
        versionCode = 88
        versionName = "0.6.75"

        // 更新源已并入主仓库：源码仓库本身就是公开的，Release 和代码同仓库同可见性，
        // 不需要再单独维护一个 releases 仓库（原先那个只剩历史包，已归档不再发新版）。
        buildConfigField("String", "UPDATE_REPO", "\"AMNB905/AM-music\"")
        // 官方签名证书 SHA-256：下载来的包必须由这把钥匙签过才允许交给系统安装器
        buildConfigField(
            "String",
            "UPDATE_SIGN_SHA256",
            "\"33a8bef95a2c25ef003e49ba27a63fd79e136671d58bb405a80c917db301df9c\"",
        )
    }

    signingConfigs {
        create("release") {
            val storePath = (project.findProperty("AMMUSIC_STORE_FILE") as String?)
                ?.takeIf { it.isNotBlank() }
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = project.findProperty("AMMUSIC_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("AMMUSIC_KEY_ALIAS") as String?
                keyPassword = project.findProperty("AMMUSIC_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            // 正式发包：R8 混淆 + 去掉无用代码 + 去掉无用资源
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            // 2026-11-14 起：Gradle 不再自动签（signingConfig 已摘掉，产物是 -unsigned.apk），
            // 统一用 `C:\toolchain\sign_release.ps1` 以外部提供的 cert/key 签名。
            // 原因见 `AMMusic-回滚恢复说明.md` 第二十三节：签名身份由用户指定，不跟构建绑死。
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 正式包永不内置 token：公开仓库不需要，内置了反而等于把私有仓库的钥匙发出去
            buildConfigField("String", "UPDATE_TOKEN", "\"\"")
        }

        debug {
            // 只有本机 debug 才能直连私有仓库（对着 AMNB905/AM-music 的 releases 试链路）
            buildConfigField("String", "UPDATE_REPO", "\"AMNB905/AM-music\"")
            buildConfigField("String", "UPDATE_TOKEN", "\"$ghToken\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 云更新要拿 VERSION_CODE / VERSION_NAME 跟远端比对
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)

    implementation(libs.coil.compose)
    implementation(libs.reorderable)


    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
}






