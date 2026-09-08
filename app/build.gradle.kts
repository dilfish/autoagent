plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.dilfish.autoagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dilfish.autoagent"
        minSdk = 24
        targetSdk = 36
        versionCode = 8
        versionName = "0.6.0"
        buildConfigField("boolean", "VERBOSE_LOG", "true")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "VERBOSE_LOG", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "VERBOSE_LOG", "false")
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
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.hierynomus:sshj:0.38.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
}

// 输出文件名带版本号，避免一堆 app-debug.apk 分不清
val apkVersionName = android.defaultConfig.versionName ?: "unknown"
val apkVersionCode = android.defaultConfig.versionCode
listOf("debug", "release").forEach { buildType ->
    val assembleName = "assemble${buildType.replaceFirstChar { c -> c.uppercase() }}"
    tasks.matching { it.name == assembleName }.configureEach {
        doLast {
            val dir = layout.buildDirectory.dir("outputs/apk/$buildType").get().asFile
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".apk") && !it.name.startsWith("AutoAgent-") }
                ?.forEach { apk ->
                    val target = dir.resolve("AutoAgent-${apkVersionName}-${apkVersionCode}-${buildType}.apk")
                    if (target.exists()) target.delete()
                    if (apk.renameTo(target)) {
                        logger.lifecycle("APK -> ${target.name}")
                    } else {
                        apk.copyTo(target, overwrite = true)
                        logger.lifecycle("APK copied -> ${target.name}")
                    }
                }
        }
    }
}
