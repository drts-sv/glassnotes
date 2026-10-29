package com.glassnotes.app

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
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
        Log.i(TAG, "запись: ${pcm.size} сэмплов, ${pcmLevel(pcm)}")
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

    /**
     * Самотест на устройстве: телефон сам произносит фразу своим TTS, пишет её микрофоном
     * и распознаёт — проверка всего тракта без человека. Результат уходит в JS как onSelfTest.
     */
    fun selfTest(phrase: String, onText: TextSink, onError: ErrorSink) {
        Thread {
            var tts: TextToSpeech? = null
            var player: MediaPlayer? = null
            try {
                val wav = java.io.File(appContext.cacheDir, "self_test.wav")
                if (wav.exists()) wav.delete()
                var ready = false
                tts = TextToSpeech(appContext) { status -> ready = status == TextToSpeech.SUCCESS }
                tts.language = java.util.Locale("ru", "RU")
                for (i in 0 until 60) {
                    if (ready) break
                    Thread.sleep(100)
                }
                Log.i(TAG, "самотест: TTS готов = $ready")
                tts.synthesizeToFile(phrase, null, wav, "self_test")
                var last = -1L
                for (i in 0 until 100) {
                    Thread.sleep(100)
                    val size = wav.length()
                    if (size > 1000L && size == last) break
                    last = size
                }
                if (wav.length() < 1000L) {
                    onError.onError("tts: файл не создан (${wav.length()} байт)")
                    return@Thread
                }
                Log.i(TAG, "самотест: речь ${wav.length()} байт, включаю запись")
                if (!startCapture()) {
                    onError.onError("audio-capture")
                    return@Thread
                }
                val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                player = MediaPlayer()
                player.setDataSource(wav.absolutePath)
                player.prepare()
                player.start()
                val waitMs = player.duration.coerceAtLeast(800) + 1200L
                Thread.sleep(waitMs)
                player.release()
                player = null
                Log.i(TAG, "самотест: проиграл ${waitMs} мс, распознаю")
                stopAndTranscribe(onText, onError)
            } catch (t: Throwable) {
                Log.e(TAG, "самотест сорвался", t)
                onError.onError((t.javaClass.simpleName + ": " + (t.message ?: "?")).take(80))
            } finally {
                try {
                    player?.release()
                    tts?.shutdown()
                } catch (ignored: Throwable) {
                }
            }
        }.start()
    }

    /** RMS и пик записи: ноль означает, что микрофон не слышал ничего. */
    fun pcmLevel(pcm: ShortArray): String {
        if (pcm.isEmpty()) return "пусто"
        var sum = 0.0
        var peak = 0
        for (v in pcm) {
            val a = kotlin.math.abs(v.toInt())
            sum += a.toDouble() * a
            if (a > peak) peak = a
        }
        val rms = kotlin.math.sqrt(sum / pcm.size)
        return "rms=${rms.toInt()} пик=$peak"
    }

    /**
     * Прямой тест движка, без микрофона: TTS пишет фразу в файл, файл разбирается в PCM 16 кГц
     * и уходит прямо в распознаватель. Так проверяется модель, словарь и декодер отдельно от акустики.
     */
    fun selfTestDirect(phrase: String, onText: TextSink, onError: ErrorSink) {
        Thread {
            var tts: TextToSpeech? = null
            try {
                val wav = java.io.File(appContext.cacheDir, "self_test.wav")
                if (wav.exists()) wav.delete()
                var ready = false
                tts = TextToSpeech(appContext) { status -> ready = status == TextToSpeech.SUCCESS }
                tts.language = java.util.Locale("ru", "RU")
                for (i in 0 until 60) {
                    if (ready) break
                    Thread.sleep(100)
                }
                tts.synthesizeToFile(phrase, null, wav, "self_test")
                var last = -1L
                for (i in 0 until 100) {
                    Thread.sleep(100)
                    val size = wav.length()
                    if (size > 1000L && size == last) break
                    last = size
                }
                if (wav.length() < 1000L) {
                    onError.onError("tts: файл не создан")
                    return@Thread
                }
                val pcm = wavToPcm16k(wav)
                Log.i(TAG, "прямой тест: файл ${wav.length()} байт -> ${pcm.size} сэмплов 16кГц, ${pcmLevel(pcm)}")
                val engine = recognizer ?: GigaAmOnnxRecognizer(appContext, modelManager).also { recognizer = it }
                val started = System.currentTimeMillis()
                val text = engine.transcribe(pcm).trim()
                Log.i(TAG, "прямой тест: '${text}' за ${System.currentTimeMillis() - started} мс")
                if (text.isEmpty()) onError.onError("no-speech") else onText.onText(text)
            } catch (t: Throwable) {
                Log.e(TAG, "прямой тест сорвался", t)
                onError.onError((t.javaClass.simpleName + ": " + (t.message ?: "?")).take(80))
            } finally {
                try {
                    tts?.shutdown()
                } catch (ignored: Throwable) {
                }
            }
        }.start()
    }

    /** Разбор WAV от TTS в моно PCM 16 кГц (распознаватель ждёт именно такое). */
    private fun wavToPcm16k(file: java.io.File): ShortArray {
        val b = file.readBytes()
        fun le16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun le32(o: Int) = le16(o) or (le16(o + 2) shl 16)
        var pos = 12
        var channels = 1
        var rate = 16000
        var bits = 16
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = le32(pos + 4)
            if (id == "fmt ") {
                channels = le16(pos + 10)
                rate = le32(pos + 12)
                bits = le16(pos + 22)
            } else if (id == "data") {
                dataOff = pos + 8
                dataLen = minOf(size, b.size - dataOff)
                break
            }
            pos += 8 + size + (size and 1)
        }
        if (dataOff < 0 || bits != 16) throw IllegalStateException("неожиданный WAV: bits=$bits offset=$dataOff")
        val frames = dataLen / 2 / channels
        val mono = ShortArray(frames)
        for (i in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += le16(dataOff + (i * channels + c) * 2).toShort().toInt()
            mono[i] = (acc / channels).toShort()
        }
        if (rate == 16000) return mono
        // линейная передискретизация в 16 кГц
        val out = ShortArray((frames.toLong() * 16000L / rate).toInt())
        for (i in out.indices) {
            val src = i.toDouble() * rate / 16000.0
            val i0 = src.toInt().coerceIn(0, frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val frac = src - i0
            out[i] = (mono[i0] * (1 - frac) + mono[i1] * frac).toInt().toShort()
        }
        Log.i(TAG, "прямой тест: передискретизация $rate -> 16000 Гц, каналов $channels")
        return out
    }

    /** Проверка микрофона без распознавания: пишем N секунд и возвращаем уровень сигнала. */
    fun micCheck(seconds: Int, onDone: TextSink) {
        Thread {
            val result = try {
                if (!startCapture()) {
                    "нет записи"
                } else {
                    Thread.sleep(seconds.coerceIn(1, 10) * 1000L)
                    val pcm = recorder?.stop() ?: ShortArray(0)
                    recorder = null
                    "${pcm.size} сэмплов (${pcm.size / 16000.0} с), ${pcmLevel(pcm)}"
                }
            } catch (t: Throwable) {
                (t.javaClass.simpleName + ": " + (t.message ?: "?")).take(80)
            }
            Log.i(TAG, "проверка микрофона: $result")
            onDone.onText(result)
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
