package com.glassnotes.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.gigapisar.audio.AudioRecorder
import ru.gigapisar.model.ModelManager
import ru.gigapisar.speech.GigaAmOnnxRecognizer

/**
 * Обёртка над ядром GigaAM (MIT, см. android/THIRD-PARTY.md).
 *
 * Нужна, чтобы Java-мост SpeechBridge не знал ни про корутины, ни про устройство
 * записи: снаружи только «есть ли модель», «скачай», «пиши», «останови и распознай».
 */
/** Колбэки объявлены интерфейсами: из Java обычная лямбда не может вернуть kotlin.Unit. */
fun interface ProgressSink {
    fun onProgress(percent: Int)
}

fun interface DoneSink {
    fun onDone(ok: Boolean)
}

fun interface TextSink {
    fun onText(text: String)
}

fun interface ErrorSink {
    fun onError(code: String)
}

class SpeechEngine(context: Context) {

    private val appContext = context.applicationContext
    private val modelManager = ModelManager(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var recognizer: GigaAmOnnxRecognizer? = null
    private var recorder: AudioRecorder? = null

    @Volatile
    private var downloading = false

    fun isModelInstalled(): Boolean = modelManager.isInstalled()

    fun modelPath(): String = modelManager.modelFile.absolutePath

    fun modelSizeMb(): Long = if (modelManager.modelFile.exists()) modelManager.modelFile.length() / 1048576 else 0L

    /** Сколько всего лежит в каталоге модели, включая незавершённый .part — видно по шагам докачки. */
    fun modelDirBytes(): Long {
        val dir = modelManager.modelFile.parentFile ?: return 0L
        var total = 0L
        dir.listFiles()?.forEach { total += it.length() }
        return total
    }

    /** Докачка модели с зеркала GitHub, затем с Hugging Face (так у авторов ядра). */
    fun downloadModel(onProgress: ProgressSink, onDone: DoneSink) {
        if (downloading) {
            onDone.onDone(false)
            return
        }
        downloading = true
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        scope.launch {
            // Телефон держит и Wi-Fi, и MTS-LTE; по умолчанию Android может выбрать сотовую,
            // а MTS обрывает крупные загрузки с GitHub. Прибиваем процесс к Wi-Fi на время докачки.
            var bound = false
            try {
                val wifi = cm.allNetworks.firstOrNull { n ->
                    cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                if (wifi != null && cm.activeNetwork != wifi) {
                    bound = cm.bindProcessToNetwork(wifi)
                    Log.i(TAG, "докачка: процесс привязан к Wi-Fi = $bound (было: ${activeTransport()})")
                } else {
                    Log.i(TAG, "докачка: сеть ${activeTransport()}")
                }
                var ok = false
                var attempt = 0
                while (attempt < 3 && !ok) {
                    attempt++
                    Log.i(TAG, "докачка: попытка $attempt, на диске ${modelDirBytes() / 1048576} МБ")
                    try {
                        modelManager.download { percent ->
                            if (percent % 10 == 0) Log.i(TAG, "докачка: $percent%")
                            onProgress.onProgress(percent)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "докачка: попытка $attempt сорвалась (${modelDirBytes() / 1048576} МБ на диске)", t)
                    }
                    ok = modelManager.isInstalled()
                    if (!ok && attempt < 3) Thread.sleep(5000)
                }
                Log.i(TAG, "докачка: финиш ok=$ok, на диске ${modelDirBytes() / 1048576} МБ (нужно ${ru.gigapisar.model.ModelConfig.EXPECTED_MODEL_SIZE / 1048576} МБ)")
                onDone.onDone(ok)
            } catch (t: Throwable) {
                Log.e(TAG, "докачка модели: на диске ${modelDirBytes() / 1048576} МБ", t)
                onDone.onDone(false)
            } finally {
                if (bound) cm.bindProcessToNetwork(null)
                downloading = false
            }
        }
    }

    fun isDownloading(): Boolean = downloading

    /** Может ли приложение открыть TCP к хосту: shell ходит, а процесс — не обязательно. */
    fun probe(url: String): String {
        return try {
            val started = System.currentTimeMillis()
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.requestMethod = "HEAD"
            c.setRequestProperty("User-Agent", "GlassNotes")
            "код " + c.responseCode + " за " + (System.currentTimeMillis() - started) + " мс"
        } catch (t: Throwable) {
            (t.javaClass.simpleName + ": " + (t.message ?: "?")).take(90)
        }
    }

    /** Какую сеть видит процесс: нужно, чтобы понять, куда уходит докачка. */
    fun activeTransport(): String {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "нет"
            val kinds = mutableListOf<String>()
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) kinds.add("Wi-Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) kinds.add("сотовая")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) kinds.add("VPN")
            val metered = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "без тарифа" else "тарифицируется"
            (if (kinds.isEmpty()) "другая" else kinds.joinToString("+")) + ", " + metered
        } catch (t: Throwable) {
            "ошибка: " + (t.message ?: "?")
        }
    }

    /** Докачку в 214 МБ запускаем только по сети без тарификации. */
    fun isOnUnmeteredNetwork(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            // null = система не отдаёт приложению активную сеть; смотрим на тракт ОС (там Wi-Fi)
            caps == null || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (t: Throwable) {
            false
        }
    }

    /** Автодокачка при старте: модель нужна, сети не жалко, ничего не качается прямо сейчас. */
    fun maybeAutoDownloadModel(onProgress: ProgressSink, onDone: DoneSink): Boolean {
        if (isModelInstalled() || downloading || !isOnUnmeteredNetwork()) return false
        downloadModel(onProgress, onDone)
        return true
    }

    /** Возвращает false, если микрофон не отдался. */
    fun startCapture(): Boolean {
        return try {
            val rec = AudioRecorder()
            recorder = rec
            rec.start()
        } catch (t: Throwable) {
            Log.e(TAG, "старт записи: $t")
            recorder = null
            false
        }
    }

    /** Останавливает запись и распознаёт в отдельном потоке (ONNX не мгновенный). */
    fun stopAndTranscribe(onText: TextSink, onError: ErrorSink) {
        val rec = recorder
        recorder = null
        val pcm = try {
            rec?.stop() ?: ShortArray(0)
        } catch (t: Throwable) {
            ShortArray(0)
        }
        if (pcm.isEmpty()) {
            onError.onError("audio-capture")
            return
        }
        Thread {
            try {
                val engine = recognizer ?: GigaAmOnnxRecognizer(appContext, modelManager).also { recognizer = it }
                val started = System.currentTimeMillis()
                val text = engine.transcribe(pcm).trim()
                Log.i(TAG, "GigaAM: ${pcm.size / 16000.0}с -> ${text.length} симв. за ${System.currentTimeMillis() - started} мс")
                if (text.isEmpty()) onError.onError("no-speech") else onText.onText(text)
            } catch (t: Throwable) {
                Log.e(TAG, "распознавание: $t")
                onError.onError("unknown")
            }
        }.start()
    }

    fun cancelCapture() {
        try {
            recorder?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "отмена записи: $t")
        }
        recorder = null
    }

    fun close() {
        cancelCapture()
        try {
            recognizer?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "закрытие распознавателя: $t")
        }
        recognizer = null
    }

    companion object {
        private const val TAG = "GlassNotes"
    }
}
