// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    // AGP 9 compiles Kotlin itself: do not apply org.jetbrains.kotlin.android.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
android {
    namespace = "fr.gildasledrogoff.faceblur"
    compileSdk = 37

    defaultConfig {
        applicationId = "fr.gildasledrogoff.faceblur"
        // 29: MediaStore with RELATIVE_PATH, so no storage permission needed.
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile").removePrefix("../"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // keystore.properties present (locally or recreated by CI): sign with the
            // upload key; otherwise leave the APK unsigned — F-Droid builds without
            // keystore.properties and signs the result with its own key.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        // dex and .so compressed in the APK (uncompressed by default starting at minSdk 28):
        // 1.8 MB of dex -> ~0.8 MB to download, decompressed on install.
        dex { useLegacyPackaging = true }
        jniLibs {
            useLegacyPackaging = true
            // libandroidx.graphics.path.so (pulled in by Compose) is stripped by AGP
            // only when an NDK is installed: stripping would make the APK bytes depend
            // on the build machine (F-Droid's server) and break reproducibility.
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
        }
        // Metadata reserved for kotlin-reflect and coroutine debug probes.
        resources.excludes += listOf("kotlin/**", "META-INF/*.version", "DebugProbesKt.bin")
    }

    androidResources {
        // AndroidX libraries bundle dozens of languages: keep only the app's languages.
        // EN (default in values/) serves as the fallback.
        localeFilters += listOf("en", "fr", "de", "it", "es")
    }

    // Encrypted dependency block for Google Play: unnecessary, and rejected by F-Droid.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        // Pure JVM tests must not crash if an android.* call slips in.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
}
