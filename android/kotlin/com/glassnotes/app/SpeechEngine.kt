package com.glassnotes.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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

fun interface Trigger {
    fun run()
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

    /** Определитель речи для авто-стопа: держим один экземпляр, модель мелкая (~2 МБ). */
    @Volatile
    private var vad: SileroVad? = null

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

    /**
     * Прогрев: сессия ONNX создаётся лениво, при первом распознавании, и это стоит 10-15 секунд.
     * Создаём её заранее и прогоняем секунду тишины — тогда первая же фраза распознаётся за ~2 с.
     */
    @Volatile
    private var warmingUp = false

    fun warmUp() {
        if (recognizer != null || warmingUp || !modelManager.isInstalled()) return
        warmingUp = true
        Thread {
            try {
                val started = System.currentTimeMillis()
                val engine = GigaAmOnnxRecognizer(appContext, modelManager)
                engine.transcribe(ShortArray(16000))
                recognizer = engine
                Log.i(TAG, "прогрев модели: " + (System.currentTimeMillis() - started) + " мс")
            } catch (t: Throwable) {
                Log.w(TAG, "прогрев не удался: $t")
            } finally {
                warmingUp = false
            }
        }.apply { isDaemon = true }.start()
    }

    /** Строка для диалога «О приложении»: чем и на каком движке распознаём. */
    fun modelInfo(): String {
        if (!modelManager.isInstalled()) return "системное (Google)"
        return "GigaAM v3 e2e · ${modelSizeMb()} МБ · офлайн"
    }

    /**
     * Перекачать модель заново. Их ModelManager при неудаче сносит и старую модель,
     * поэтому рабочую копию откладываем в .bak и возвращаем, если докачка сорвалась.
     */
    fun redownloadModel(onProgress: ProgressSink, onDone: DoneSink) {
        if (downloading) {
            onDone.onDone(false)
            return
        }
        downloading = true
        scope.launch {
            val dir = modelManager.modelFile.parentFile
            val modelBak = java.io.File(dir, modelManager.modelFile.name + ".bak")
            val vocabBak = java.io.File(dir, modelManager.vocabFile.name + ".bak")
            val ready = java.io.File(dir, ".ready")
            val hadModel = modelManager.isInstalled()
            if (hadModel) {
                modelBak.delete()
                vocabBak.delete()
                modelManager.modelFile.renameTo(modelBak)
                modelManager.vocabFile.renameTo(vocabBak)
                Log.i(TAG, "перекачка: прежняя модель отложена в .bak")
            }
            var ok = false
            try {
                var attempt = 0
                while (attempt < 3 && !ok) {
                    attempt++
                    try {
                        modelManager.download { percent -> onProgress.onProgress(percent) }
                    } catch (t: Throwable) {
                        Log.e(TAG, "перекачка: попытка $attempt сорвалась", t)
                    }
                    ok = modelManager.isInstalled()
                    if (!ok && attempt < 3) Thread.sleep(5000)
                }
            } finally {
                if (ok || !hadModel) {
                    modelBak.delete()
                    vocabBak.delete()
                } else {
                    // вернуть прежнюю модель, чтобы приложение не осталось без распознавания
                    modelBak.renameTo(modelManager.modelFile)
                    vocabBak.renameTo(modelManager.vocabFile)
                    ready.writeText("ok")
                    resetInstalledCache()
                    Log.i(TAG, "перекачка: не удалась, прежняя модель возвращена")
                }
                downloading = false
            }
            onDone.onDone(ok)
        }
    }

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

    /**
     * Авто-стоп по тишине. Включает только рабочий путь (в самотестах не нужен: там своя логика).
     * Без него фраза не заканчивается сама — приходится жать кнопку.
     */
    @Volatile
    private var autoStopEnabled = false

    private var autoStopCallback: Trigger? = null

    /** Включить авто-стоп: onStop вызовется, когда после речи наступит тишина. */
    fun enableAutoStop(onStop: Trigger) {
        autoStopCallback = onStop
        autoStopEnabled = true
    }

    fun disableAutoStop() {
        autoStopEnabled = false
        autoStopCallback = null
    }

    /** VAD для авто-стопа: грузим один раз, при неудаче авто-стоп остаётся по уровню. */
    private fun obtainVad(): SileroVad {
        val existing = vad
        if (existing != null && existing.available) return existing
        val created = SileroVad(appContext)
        vad = created
        return created
    }

    private var capturing = false

    /**
     * Отклик на начало и конец записи: короткая вибрация + сигнал.
     * Пользователь должен слышать/чувствовать, что запись пошла и что закончилась.
     */
    fun feedback(start: Boolean) {
        try {
            if (vibrator != null) {
                val ms = if (start) 35L else 60L
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator!!.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator!!.vibrate(ms)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "вибрация: $t")
        }
        try {
            val generator = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 80).also { tone = it }
            generator.startTone(if (start) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2, 90)
        } catch (t: Throwable) {
            Log.w(TAG, "сигнал: $t")
        }
    }

    private var tone: ToneGenerator? = null

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Возвращает false, если микрофон не отдался. */
    fun startCapture(): Boolean {
        return try {
            val rec = AudioRecorder()
            rec.detector = obtainVad()
            recorder = rec
            val started = rec.start()
            capturing = started
            if (started && autoStopEnabled) startSilenceWatcher(rec)
            started
        } catch (t: Throwable) {
            Log.e(TAG, "старт записи: $t")
            recorder = null
            capturing = false
            false
        }
    }

    /**
     * Следит за абсолютной громкостью записи: нашли речь -> ждём тишину -> останавливаем сами.
     * Работаем по rms (не путать с адаптивным `level`: тот в тихой комнате равен единице).
     */
    private fun startSilenceWatcher(rec: AudioRecorder) {
        val thread = Thread {
            val startedAt = System.currentTimeMillis()
            var lastSpeechAt = 0L
            var peak = 0
            var lastLogAt = 0L
            // Речь ищем нейросетью: порог по громкости в шуме не работает (в шумной комнате
            // «тишина» не наступает никогда, и запись не останавливается сама).
            val byVad = rec.detector?.available == true
            Log.i(
                TAG,
                if (byVad) {
                    "авто-стоп: по VAD (тишина ${SILENCE_MS} мс, минимальная речь ${MIN_SPEECH_MS} мс)"
                } else {
                    "авто-стоп: VAD недоступен, считаю по уровню (rms >= $SPEECH_RMS)"
                },
            )
            while (capturing && recorder === rec) {
                val now = System.currentTimeMillis()
                val rms = rec.rms
                if (rms > peak) peak = rms
                val speech = if (byVad) rec.speechProb >= VAD_SPEECH_PROB else rms >= SPEECH_RMS
                if (speech) lastSpeechAt = now
                if (now - lastLogAt >= 1000) {
                    lastLogAt = now
                    Log.i(
                        TAG,
                        "уровень записи: rms=$rms (пик $peak, адаптивный %.2f, речь %.2f)".format(
                            rec.level,
                            rec.speechProb,
                        ),
                    )
                }
                val silentFor = if (lastSpeechAt > 0L) now - lastSpeechAt else 0L
                val speechMs = if (lastSpeechAt > 0L) lastSpeechAt - startedAt else 0L
                if (lastSpeechAt > 0L && silentFor >= SILENCE_MS && speechMs >= MIN_SPEECH_MS) {
                    Log.i(TAG, "авто-стоп: тишина " + silentFor + " мс после " + speechMs + " мс речи, пик rms=$peak")
                    break
                }
                if (now - startedAt >= MAX_CAPTURE_MS) {
                    Log.i(TAG, "авто-стоп: предел " + (MAX_CAPTURE_MS / 1000) + " с, пик rms=$peak")
                    break
                }
                try {
                    Thread.sleep(60)
                } catch (ie: InterruptedException) {
                    return@Thread
                }
            }
            if (capturing && recorder === rec) {
                autoStopCallback?.run()
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    /** Останавливает запись и распознаёт в отдельном потоке (ONNX не мгновенный). */
    fun stopAndTranscribe(onText: TextSink, onError: ErrorSink) {
        val rec = recorder
        recorder = null
        capturing = false
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
     * Самотест авто-стопа: телефон сам произносит фразу в динамик, пишет её микрофоном и
     * должен остановиться САМ по тишине — проверка без человека. Отличие от [selfTest]:
     * включён авто-стоп, то есть путь ровно такой же, как при обычной записи (в selfTest
     * авто-стоп выключен, поэтому этот путь там не проверяется).
     * Результат — в JS: текст распознанного или «авто-стоп не сработал».
     */
    fun selfTestAutoStop(phrase: String, onText: TextSink, onError: ErrorSink) {
        Thread {
            var tts: TextToSpeech? = null
            var player: MediaPlayer? = null
            try {
                val wav = java.io.File(appContext.cacheDir, "auto_stop_test.wav")
                if (wav.exists()) wav.delete()
                var ready = false
                tts = TextToSpeech(appContext) { status -> ready = status == TextToSpeech.SUCCESS }
                tts.language = java.util.Locale("ru", "RU")
                for (i in 0 until 60) {
                    if (ready) break
                    Thread.sleep(100)
                }
                tts.synthesizeToFile(phrase, null, wav, "auto_stop_test")
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

                val stopped = java.util.concurrent.CountDownLatch(1)
                var stoppedAt = 0L
                val startedAt = System.currentTimeMillis()
                enableAutoStop {
                    stoppedAt = System.currentTimeMillis()
                    stopped.countDown()
                }
                if (!startCapture()) {
                    disableAutoStop()
                    onError.onError("audio-capture")
                    return@Thread
                }
                Log.i(TAG, "тест авто-стопа: запись пошла, говорю фразу")

                val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                player = MediaPlayer()
                player.setDataSource(wav.absolutePath)
                player.prepare()
                player.start()
                val speechMs = player.duration
                Log.i(TAG, "тест авто-стопа: фраза ${speechMs} мс")

                val fired = stopped.await(
                    speechMs + 15_000L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
                try {
                    player.release()
                } catch (ignored: Throwable) {
                }
                player = null

                disableAutoStop()
                if (!fired) {
                    Log.w(TAG, "тест авто-стопа: НЕ сработал (фраза кончилась, записи не прекратил)")
                    stopAndTranscribe({ _ -> onError.onError("авто-стоп не сработал") }, onError)
                    return@Thread
                }
                Log.i(
                    TAG,
                    "тест авто-стопа: остановился сам через ${stoppedAt - startedAt} мс " +
                        "(фраза кончилась на ${speechMs} мс, ждал тишины ${SILENCE_MS} мс)",
                )
                stopAndTranscribe(onText, onError)
            } catch (t: Throwable) {
                Log.e(TAG, "тест авто-стопа сорвался", t)
                onError.onError((t.javaClass.simpleName + ": " + (t.message ?: "?")).take(80))
            } finally {
                disableAutoStop()
                try {
                    player?.release()
                    tts?.shutdown()
                } catch (ignored: Throwable) {
                }
            }
        }.start()
    }

    /**
     * Самотест на устройстве: телефон сам произносит фразу своим TTS, пишет её микрофоном
     * и распознаёт — проверка всего тракта без человека. Результат уходит в JS как onSelfTest.
     */
    fun selfTest(phrase: String, onText: TextSink, onError: ErrorSink) {
        disableAutoStop()
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
        disableAutoStop()
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

    /**
     * Произнести фразу вслух и закончить. Нужно для проверки авто-стопа без человека:
     * приложение говорит само, микрофон это слышит, запись должна закрыться по тишине.
     */
    fun speakOnly(phrase: String, onDone: TextSink) {
        Thread {
            var tts: TextToSpeech? = null
            var player: MediaPlayer? = null
            try {
                val wav = java.io.File(appContext.cacheDir, "speak_only.wav")
                if (wav.exists()) wav.delete()
                var ready = false
                tts = TextToSpeech(appContext) { status -> ready = status == TextToSpeech.SUCCESS }
                tts.language = java.util.Locale("ru", "RU")
                for (i in 0 until 60) {
                    if (ready) break
                    Thread.sleep(100)
                }
                tts.synthesizeToFile(phrase, null, wav, "speak_only")
                var last = -1L
                for (i in 0 until 100) {
                    Thread.sleep(100)
                    val size = wav.length()
                    if (size > 1000L && size == last) break
                    last = size
                }
                if (wav.length() < 1000L) {
                    onDone.onText("tts: файл не создан")
                    return@Thread
                }
                val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                player = MediaPlayer()
                player.setDataSource(wav.absolutePath)
                player.prepare()
                player.start()
                val waitMs = player.duration.coerceAtLeast(800)
                Thread.sleep(waitMs + 200L)
                Log.i(TAG, "произнёс фразу: " + waitMs + " мс")
                onDone.onText("сыграно " + waitMs + " мс")
            } catch (t: Throwable) {
                Log.e(TAG, "произнести не удалось", t)
                onDone.onText("ошибка: " + (t.message ?: "?"))
            } finally {
                try {
                    player?.release()
                    tts?.shutdown()
                } catch (ignored: Throwable) {
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun cancelCapture() {
        capturing = false
        try {
            recorder?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "отмена записи: $t")
        }
        recorder = null
    }

    /**
     * ModelManager кэширует признак готовности и после сбоя пишет туда false.
     * Публичного сброса у него нет, а после возврата .bak модель снова на месте — сбрасываем сами.
     */
    private fun resetInstalledCache() {
        try {
            val field = modelManager.javaClass.getDeclaredField("isInstalledCached")
            field.isAccessible = true
            field.set(modelManager, null)
        } catch (t: Throwable) {
            Log.w(TAG, "не удалось сбросить кэш готовности модели: $t")
        }
    }

    fun close() {
        cancelCapture()
        try {
            tone?.release()
        } catch (ignored: Throwable) {
        }
        tone = null
        try {
            recognizer?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "закрытие распознавателя: $t")
        }
        recognizer = null
    }

    companion object {
        private const val TAG = "GlassNotes"

        /** rms, с которого считаем, что человек говорит (тихая комната даёт ~150, речь — тысячи). */
        private const val SPEECH_RMS = 400

        /** Сколько речи должно накопиться, прежде чем разрешим авто-стоп (щелчок не в счёт). */
        private const val MIN_SPEECH_MS = 250L

        /**
         * Сколько тишины после речи = конец фразы. Для диктовки берём 1.8 с: человек,
         * проговаривая мысль, делает паузы до 1.5 с, и на 1.2 с фраза рвалась посередине.
         */
        private const val SILENCE_MS = 1800L

        /** С какого значения вероятности VAD считаем, что человек говорит. */
        private const val VAD_SPEECH_PROB = 0.5f

        /** Страховка: дольше этого не пишем ни при каких условиях. */
        private const val MAX_CAPTURE_MS = 90_000L
    }
}
