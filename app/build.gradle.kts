import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.colorclicker"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.colorclicker"
        minSdk = 30
        targetSdk = 34
        versionCode = 44
        versionName = "0.43"
    }
    // 固定签名：signing.properties（本机）或 CI secret 解出的同名文件提供密码，
    // keystore 在 ~/workspace/keys/colorclicker-release.keystore（不进仓库）。
    // debug 包也用它签，否则每次 CI 换一台新机器就换一个 debug 签名，手机无法直接覆盖安装。
    val signingPropsFile = rootProject.file("signing.properties")
    val signingProps = signingPropsFile.takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("keystore.path", "release.keystore"))
                storePassword = signingProps.getProperty("keystore.password")
                keyAlias = signingProps.getProperty("key.alias")
                keyPassword = signingProps.getProperty("key.password")
            }
        }
    }
    buildTypes {
        release {
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.opencv:opencv:4.9.0")
}
