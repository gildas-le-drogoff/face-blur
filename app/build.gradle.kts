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
            // keystore.properties présent (local ou recréé par la CI) : signer avec la
            // clé d'upload ; sinon repli sur la clé de debug pour rester buildable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        // dex et .so compressés dans l'APK (non compressés par défaut dès minSdk 28) :
        // 1,8 Mo de dex -> ~0,8 Mo à télécharger, décompressé à l'installation.
        dex { useLegacyPackaging = true }
        jniLibs { useLegacyPackaging = true }
        // Métadonnées réservées à kotlin-reflect et aux sondes de débogage des coroutines.
        resources.excludes += listOf("kotlin/**", "META-INF/*.version", "DebugProbesKt.bin")
    }

    androidResources {
        // Les bibliothèques AndroidX embarquent des dizaines de langues : ne garder
        // que les langues de l'app. EN (défaut dans values/) sert de repli.
        localeFilters += listOf("en", "fr", "de", "it", "es")
    }

    // Bloc de dépendances chiffré pour Google Play : inutile, et refusé par F-Droid.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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

    testImplementation(libs.junit)
}
