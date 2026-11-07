plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.glassnotes.dev"
    compileSdk = 35

    defaultConfig {
        // Временно свой пакет: dev-сборка живёт рядом с боевым GlasNote (com.glassnotes.app)
        // и ничего в нём не трогает. На этапе 5 пакет станет боевым, а оболочка WebView уйдёт.
        applicationId = "com.glassnotes.dev"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "0.29-dev"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")

    // Распознавание речи: тот же движок, что в боевом приложении (GigaAM v3 через onnxruntime).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
