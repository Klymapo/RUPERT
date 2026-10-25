plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.klymapo.rupert"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.klymapo.rupert"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.11.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // Llave de firma solo para instalar a mano en la tablet (sideload).
    // Es fija para que cada APK nuevo actualice al anterior sin desinstalar.
    // No es una llave de Play Store.
    signingConfigs {
        create("sideload") {
            storeFile = file("rupert-sideload.keystore")
            storePassword = "rupertsideload"
            keyAlias = "rupert"
            keyPassword = "rupertsideload"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sideload")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
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
    }
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
