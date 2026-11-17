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
        // Пакет задаётся сборкой: dev для теста, боевой — -PappId=com.glassnotes.app
        val targetAppId = (project.findProperty("appId") as String?) ?: "com.glassnotes.app"
        applicationId = targetAppId
        // Имя на иконке: боевой пакет — «GlasNote», тестовый — с пометкой.
        // Классы при этом остаются в пакете com.glassnotes.dev (он прописан в манифесте),
        // поэтому один и тот же APK работает и как боевой, и как dev.
        resValue("string", "app_name", if (targetAppId == "com.glassnotes.app") "GlasNote" else "GlasNote (dev)")
        minSdk = 26
        targetSdk = 35
        // Версии держим честные: прошлые правки версии молча не применились (файл стоял на 0.42-dev),
        // поэтому все отчёты «0.4x-dev» были неверны — сборка на телефоне была одной и той же.
        versionCode = 1014
        versionName = "1.0.14"

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
            // -Pdbg=true — только для служебной установки: без debuggable нельзя залить
            // базу через run-as. Данные при обратной установке обычного release сохраняются.
            isDebuggable = project.hasProperty("dbg")
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
    // 1.22.0 — первая версия, где libonnxruntime4j_jni.so выровнена на 16 КБ страницы памяти.
    // В 1.20.0/1.21.0 сегменты были на 4 КБ: Android 15+ при установке ругается
    // «приложение не поддерживает страницы 16 КБ» (проверено разбором ELF p_align).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
