// Нативный путь GlasNote, этап 2: экран записи на Compose.
// Отдельный проект рядом с текущей сборкой на WebView: пакет com.glassnotes.dev,
// ставится на телефон ВМЕСТЕ с боевым GlasNote (com.glassnotes.app) и его не трогает.
// На этапе 5 пакет станет com.glassnotes.app, а оболочка WebView уйдёт.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GlasNoteNative"
include(":app")
