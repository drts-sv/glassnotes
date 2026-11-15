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
        // Версии держим честные: прошлые правки версии молча не применились (файл стоял на 0.42-dev),
        // поэтому все отчёты «0.4x-dev» были неверны — сборка на телефоне была одной и той же.
        versionCode = 126
        versionName = "0.126-dev"

        // Телефону нужна одна архитектура: библиотека распознавания тянет четыре (arm64, arm32, x86,
        // x86_64) и это ~50 МБ лишнего веса в APK. arm64-v8a хватает для нашего телефона.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Подпись ТЕМ ЖЕ ключом, что у боевого приложения (он лежит в репозитории).
    // Без этого обновление боевого пакета невозможно: Android откажет с UPDATE_INCOMPATIBLE,
    // а единственным путём останется переустановка — то есть потеря всех настоящих заметок.
    signingConfigs {
        create("shipping") {
            storeFile = file("../../android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // R8: без сжатия APK почти в полтора раза больше нужного — в нём остаются
            // неиспользуемые классы библиотек (dex был 17 МБ против 4-6 МБ после R8).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Для проверок подписываем ключом боевого приложения — чтобы подпись совпадала.
            signingConfig = signingConfigs.getByName("shipping")
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
        // Нужно, чтобы приложение показывало свою версию (BuildConfig.VERSION_NAME).
        buildConfig = true
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
