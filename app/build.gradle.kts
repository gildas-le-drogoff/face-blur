import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    // AGP 9 compile Kotlin lui-même : ne pas appliquer org.jetbrains.kotlin.android.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
android {
    namespace = "org.example.faceblur"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.example.faceblur"
        // 29 : MediaStore avec RELATIVE_PATH, donc aucune permission de stockage.
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // Pixel 8 Pro (Tensor G3) : arm64 uniquement.
        ndk { abiFilters += listOf("arm64-v8a") }
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
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // .so compressés dans l'APK (33 Mo -> ~12 Mo pour ORT), extraits à l'installation.
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        noCompress += "onnx"
    }

    testOptions {
        // Les tests JVM purs ne doivent pas planter si un appel android.* s'y glisse.
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
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
}
