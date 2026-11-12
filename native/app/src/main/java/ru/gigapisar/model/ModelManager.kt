package ru.gigapisar.model

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Скачивание и установка GigaAM v3.
 *
 * Правила, из-за которых это переписано:
 *  - докачка: недокачанный .part больше НЕ удаляется, следующая попытка продолжает
 *    запросом Range: bytes=N- (GitHub release-assets отдаёт 206);
 *  - сон: на время загрузки держим PARTIAL_WAKE_LOCK и WifiLock — иначе экран гаснет,
 *    CPU и Wi-Fi засыпают, поток рвётся по readTimeout и 224 МБ начинаются заново;
 *  - боевая модель не трогается до успеха: сначала качаем .part, проверяем размер и
 *    SHA-256, и только потом подменяем файлы и пишем .ready;
 *  - словарь маленький — его качаем целиком, без докачки.
 */
class ModelManager(
    context: Context,
) {
    private val context = context.applicationContext

    private val modelDirectory: File =
        context.getDir(ModelConfig.DIRECTORY_NAME, Context.MODE_PRIVATE)

    val modelFile: File
        get() =
            File(
                modelDirectory,
                ModelConfig.MODEL_FILE_NAME,
            )

    val vocabFile: File
        get() =
            File(
                modelDirectory,
                ModelConfig.VOCAB_FILE_NAME,
            )

    private val readyFile: File
        get() =
            File(
                modelDirectory,
                ModelConfig.READY_FILE_NAME,
            )

    private val modelPart: File
        get() =
            File(
                modelDirectory,
                "${ModelConfig.MODEL_FILE_NAME}.part",
            )

    private val vocabPart: File
        get() =
            File(
                modelDirectory,
                "${ModelConfig.VOCAB_FILE_NAME}.part",
            )

    @Volatile
    private var isInstalledCached: Boolean? = null

    fun isInstalled(): Boolean {
        isInstalledCached?.let { return it }

        val installed =
            try {
                readyFile.exists() &&
                    modelFile.exists() &&
                    vocabFile.exists() &&
                    modelFile.length() == ModelConfig.EXPECTED_MODEL_SIZE
            } catch (_: Throwable) {
                false
            }

        if (installed) {
            isInstalledCached = true
        }

        return installed
    }

    /** Сколько уже лежит в .part — для кнопки «Продолжить». */
    fun partialBytes(): Long = runCatching { modelPart.length() }.getOrDefault(0L)

    suspend fun download(onProgress: suspend (Int) -> Unit) {
        withContext(Dispatchers.IO) {
            isInstalledCached = null
            modelDirectory.mkdirs()

            val locks = Locks(context)
            locks.acquire()
            try {
                downloadFromMirrors(
                    urls = ModelConfig.MODEL_URLS,
                    destination = modelPart,
                    expectedSize = ModelConfig.EXPECTED_MODEL_SIZE,
                    expectedSha256 = ModelConfig.MODEL_SHA256,
                    onProgress = onProgress,
                )

                vocabPart.delete()
                downloadFromMirrors(
                    urls = ModelConfig.VOCAB_URLS,
                    destination = vocabPart,
                    expectedSize = -1L,
                    expectedSha256 = ModelConfig.VOCAB_SHA256,
                    onProgress = {},
                )
                validateVocabulary(vocabPart)

                // Всё проверено — только теперь трогаем боевые файлы.
                readyFile.delete()
                moveFile(modelPart, modelFile)
                moveFile(vocabPart, vocabFile)
                readyFile.writeText("ok")
                isInstalledCached = true
                Log.i(
                    TAG,
                    "модель установлена: %d б, словарь %d строк".format(modelFile.length(), ModelConfig.VOCAB_SIZE),
                )
            } catch (error: Throwable) {
                isInstalledCached = null
                // .part не удаляем: следующая попытка продолжит с этого места.
                Log.w(
                    TAG,
                    "скачивание прервано: %s (в .part %d б — продолжим отсюда)".format(
                        error.message,
                        runCatching { modelPart.length() }.getOrDefault(0L),
                    ),
                )
                throw error
            } finally {
                locks.release()
            }
        }
    }

    /**
     * Перебирает адреса по порядку. .part переиспользуется только для первого адреса:
     * у другого зеркала может лежать другой файл, поэтому перед ним всё начинается заново.
     */
    private suspend fun downloadFromMirrors(
        urls: List<String>,
        destination: File,
        expectedSize: Long,
        expectedSha256: String?,
        onProgress: suspend (Int) -> Unit,
    ) {
        var lastError: Throwable? = null
        urls.forEachIndexed { index, url ->
            // Второе зеркало пишет в отдельный .alt: .part от первого не трогаем,
            // иначе неудачная попытка обнулила бы уже скачанное.
            val target = if (index == 0) destination else File(destination.absolutePath + ".alt")
            try {
                if (index > 0) target.delete()
                downloadFileWithRetry(
                    url = url,
                    destination = target,
                    expectedSize = expectedSize,
                    onProgress = onProgress,
                )
                if (expectedSize > 0) {
                    require(target.length() == expectedSize) {
                        "Неверный размер ${target.name}: ${target.length()}, ожидалось $expectedSize"
                    }
                }
                if (expectedSha256 != null) {
                    val actualSha256 = sha256(target)
                    require(actualSha256.equals(expectedSha256, ignoreCase = true)) {
                        "SHA-256 ${target.name} не совпадает: $actualSha256"
                    }
                }
                if (target != destination) {
                    require(target.renameTo(destination)) { "Не удалось переименовать ${target.name}" }
                }
                return
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (lastError == null) lastError = error
                Log.w(TAG, "зеркало ${index + 1} не вышло: ${error.message}")
            }
        }
        throw lastError ?: IllegalStateException("Нет адреса для скачивания")
    }

    /**
     * Три попытки на адрес с паузами: сразу после старта приложения сеть ещё не готова,
     * DNS отвечает «No address associated with hostname» — это не повод бросать 90 МБ.
     */
    private suspend fun downloadFileWithRetry(
        url: String,
        destination: File,
        expectedSize: Long,
        onProgress: suspend (Int) -> Unit,
    ) {
        var lastError: Throwable? = null
        for (attempt in 1..3) {
            try {
                downloadFile(
                    url = url,
                    destination = destination,
                    expectedSize = expectedSize,
                    onProgress = onProgress,
                )
                return
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
                val reason =
                    when (error) {
                        is java.net.UnknownHostException,
                        is java.net.ConnectException,
                        is java.net.SocketTimeoutException,
                        -> "нет сети или DNS: ${error.message ?: error::class.java.simpleName}"
                        else -> error.message ?: error::class.java.simpleName
                    }
                Log.w(TAG, "попытка $attempt/3 не вышла ($reason)")
                if (attempt < 3) kotlinx.coroutines.delay(if (attempt == 1) 5_000L else 15_000L)
            }
        }
        throw lastError ?: IllegalStateException("Скачивание не удалось")
    }

    private suspend fun downloadFile(
        url: String,
        destination: File,
        expectedSize: Long,
        onProgress: suspend (Int) -> Unit,
    ) {
        var have = if (destination.exists()) destination.length() else 0L
        // .part длиннее файла — мусор от прежней версии, начинаем заново.
        if (expectedSize > 0 && have > expectedSize) {
            destination.delete()
            have = 0L
        }

        val connection =
            URL(url).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")

            connection.connect()

            val responseCode = connection.responseCode
            val append = have > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
            require(responseCode in 200..299) {
                "HTTP $responseCode при загрузке $url"
            }
            if (!append) have = 0L

            val contentLength = connection.contentLengthLong
            val target = if (expectedSize > 0) expectedSize else have + contentLength
            if (append) Log.i(TAG, "докачка ${destination.name} с %d б".format(have))

            BufferedInputStream(
                connection.inputStream,
                64 * 1024,
            ).use { input ->
                FileOutputStream(destination, append).use { output ->
                    val buffer =
                        ByteArray(64 * 1024)

                    var downloaded = have
                    var lastProgress = -1

                    while (true) {
                        currentCoroutineContext().ensureActive()

                        val count =
                            input.read(buffer)

                        if (count <= 0) {
                            break
                        }

                        output.write(
                            buffer,
                            0,
                            count,
                        )

                        downloaded += count

                        if (target > 0) {
                            val progress =
                                (
                                    downloaded * 100L /
                                        target
                                ).toInt()
                                    .coerceIn(0, 100)

                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** SHA-256 по файлу целиком: при докачке считать хеш на лету нельзя. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateVocabulary(file: File) {
        val lines =
            file
                .readLines(Charsets.UTF_8)
                .filter { it.isNotBlank() }

        require(lines.size == ModelConfig.VOCAB_SIZE) {
            "Неожиданный размер vocabulary: ${lines.size}, " +
                "ожидалось ${ModelConfig.VOCAB_SIZE}"
        }

        lines.forEachIndexed { index, line ->
            val separator =
                line.lastIndexOf(' ')

            require(separator > 0) {
                "Некорректная строка vocabulary #$index"
            }

            val token =
                line.substring(
                    0,
                    separator,
                )

            val id =
                line
                    .substring(
                        separator + 1,
                    ).trim()
                    .toIntOrNull()

            require(id == index) {
                "Некорректный ID vocabulary: " +
                    "строка=$index, id=$id"
            }

            if (index == ModelConfig.BLANK_ID) {
                require(token == "<blk>") {
                    "Последний токен должен быть <blk>, " +
                        "получен: $token"
                }
            }
        }
    }

    private fun moveFile(
        source: File,
        destination: File,
    ) {
        require(source.exists()) {
            "Файл отсутствует: ${source.absolutePath}"
        }

        if (!source.renameTo(destination)) {
            source.copyTo(
                destination,
                overwrite = true,
            )

            source.delete()
        }
    }

    /**
     * На время загрузки держим CPU и Wi-Fi. Иначе экран гаснет, устройство уходит в сон,
     * readTimeout рвёт поток — и 224 МБ начинаются заново.
     */
    private class Locks(
        private val context: Context,
    ) {
        private var wake: PowerManager.WakeLock? = null
        private var wifi: WifiManager.WifiLock? = null

        fun acquire() {
            runCatching {
                val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wake =
                    power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlasNote:model").apply {
                        setReferenceCounted(false)
                        acquire(45 * 60 * 1000L)
                    }
            }
            runCatching {
                val manager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifi =
                    manager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GlasNote:model")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
            Log.i(TAG, "блокировки на время загрузки: cpu=${wake?.isHeld == true}, wifi=${wifi?.isHeld == true}")
        }

        fun release() {
            runCatching { wifi?.takeIf { it.isHeld }?.release() }
            runCatching { wake?.takeIf { it.isHeld }?.release() }
        }
    }

    private companion object {
        const val TAG = "GlasNotesDev"
    }
}
