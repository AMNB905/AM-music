plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.am.music"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.am.music"
        minSdk = 26
        // 故意对齐参考项目（targetSdk 34）：targetSdk 35/36 上 overlay 悬浮窗的触摸行为
        // 与 34 不同（真机上表现为「看得见但点不动」），先回到 34 验证再决定要不要升。
        targetSdk = 34
        versionCode = 73
        versionName = "0.6.60"
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