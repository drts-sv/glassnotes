package com.glassnotes.dev

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление приложения с GitHub-релизов.
 *
 * Проверка — один запрос к открытому API репозитория, без токена и без аккаунта.
 * Скачивание — в `cacheDir/updates`, установка — системным установщиком через FileProvider:
 * своего кода установки нет, пользователь видит обычный диалог Android «Обновить приложение?».
 *
 * Подпись у нового APK та же, что у установленного (боевой ключ), поэтому обновление ставится
 * поверх без удаления и данные остаются на месте.
 */
object Updater {
    const val TAG = "GlasNotesDev"

    private const val API = "https://api.github.com/repos/drts-sv/glassnotes/releases/latest"
    const val PAGE = "https://github.com/drts-sv/glassnotes/releases"
    private const val PAGE_LATEST = "https://github.com/drts-sv/glassnotes/releases/latest"

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String?,
    )

    /** «1.0.2» → список чисел; нечисловые части отбрасываются. */
    private fun parts(version: String): List<Int> =
        version.trim().removePrefix("v").split(".", "-")
            .mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }

    /** Новее ли [candidate], чем [current]. Сравнение по числам, а не по строкам. */
    fun isNewer(
        candidate: String,
        current: String,
    ): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /**
     * Последний релиз репозитория.
     *
     * Три попытки: на мобильной сети DNS иногда отвечает не сразу (UnknownHostException на первом
     * запросе после выхода из сна). Пауза между попытками 900 мс. Ошибку отдаём наверх — ловит UI.
     */
    fun check(): Release {
        var last: Throwable? = null
        for (attempt in 1..3) {
            try {
                return checkOnce()
            } catch (error: Exception) {
                last = error
                Log.w(TAG, "проверка обновления: попытка $attempt не удалась — $error")
                if (attempt < 3) Thread.sleep(900L)
            }
        }
        // API недоступен (например, DNS не резолвит api.github.com) — пробуем страницу релизов.
        runCatching { return checkViaRedirect() }
            .onFailure { Log.w(TAG, "проверка обновления: releases/latest тоже не ответил — $it") }
        throw last ?: IllegalStateException("проверка обновления не удалась")
    }

    /**
     * Проверка без API: `releases/latest` отвечает редиректом на `/releases/tag/vX.Y.Z` — тег берём
     * из заголовка Location. Нужен потому, что `api.github.com` на части мобильных сетей не
     * резолвится, а `github.com` доступен (с него же качается модель распознавания).
     */
    private fun checkViaRedirect(): Release {
        val connection = URL(PAGE_LATEST).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "GlasNote")
        try {
            val code = connection.responseCode
            val location = connection.getHeaderField("Location")
            if (code !in 300..399 || location.isNullOrBlank()) {
                throw IllegalStateException("GitHub ответил кодом $code без адреса релиза")
            }
            val tag = location.substringAfterLast("/tag/", "").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("В адресе релиза нет тега: $location")
            val version = tag.removePrefix("v")
            val apk = "https://github.com/drts-sv/glassnotes/releases/download/$tag/glasnote-$version.apk"
            Log.i(TAG, "проверка обновления: через releases/latest → $tag")
            return Release(version = version, notes = "", apkUrl = apk)
        } finally {
            connection.disconnect()
        }
    }

    private fun checkOnce(): Release {
        val connection = URL(API).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "GlasNote")
        try {
            val code = connection.responseCode
            if (code != 200) {
                // Тело ответа GitHub объясняет причину (лимит запросов, 404 и т. п.) — в лог.
                val body = runCatching {
                    (connection.errorStream ?: connection.inputStream).bufferedReader().use { it.readText() }
                }.getOrDefault("")
                throw IllegalStateException("GitHub ответил кодом $code: " + body.take(160))
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val tag = json.optString("tag_name").ifBlank { json.optString("name") }
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apk = asset.optString("browser_download_url").ifBlank { null }
                        break
                    }
                }
            }
            return Release(
                version = tag.removePrefix("v"),
                notes = json.optString("body"),
                apkUrl = apk,
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Скачать APK релиза в кэш. [onProgress] — проценты, −1 если размер неизвестен. */
    fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit,
    ): File {
        val url = release.apkUrl ?: throw IllegalStateException("В релизе нет APK")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Старые загрузки не нужны: имя файла содержит версию, но кэш чистим от прочих.
        dir.listFiles()?.forEach { if (it.name != "glasnote-${release.version}.apk") it.delete() }
        val target = File(dir, "glasnote-${release.version}.apk")
        if (target.exists() && target.length() > 1024 * 1024) {
            // Раньше уже скачали — второй раз сеть не трогаем.
            onProgress(100)
            return target
        }

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "GlasNote")
        try {
            val code = connection.responseCode
            if (code != 200) throw IllegalStateException("Не удалось скачать APK (код $code)")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(if (total > 0) ((done * 100) / total).toInt() else -1)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (target.length() < 1024 * 1024) throw IllegalStateException("Файл обновления слишком мал")
        return target
    }

    /** Разрешена ли установка APK из этого приложения (Android 8+ требует отдельного согласия). */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Экран «Установка неизвестных приложений» для нашего пакета. */
    fun openInstallPermission(context: Context) {
        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Показать системный диалог установки для скачанного APK. */
    fun install(
        context: Context,
        file: File,
    ) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
        android.util.Log.i(TAG, "обновление: открыт системный установщик (%s)".format(file.name))
    }

    fun openReleases(context: Context) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
