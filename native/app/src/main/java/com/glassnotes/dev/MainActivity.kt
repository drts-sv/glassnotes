package com.glassnotes.dev

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import ru.gigapisar.model.ModelManager
import ru.gigapisar.speech.GigaAmOnnxRecognizer
import java.io.File
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Точка входа dev-сборки нативного пути.
 *
 * Диагностические входы (без пальца и без микрофона):
 *  - `wave_test` — прогон алгоритма полос синтетическим сигналом (WaveSelfTest);
 *  - `asr_file` — распознать файл PCM16 16 кГц целиком и напечатать текст;
 *  - `asr_replay` — проиграть файл ЧЕРЕЗ ту же цепочку, что живую речь: куски по 20 мс →
 *    LiveTranscriber → нарезка по паузам → распознавание. Так проверяется распознавание «на ходу»
 *    без участия владельца. Вероятность речи в этом режиме берём по энергии: живого VAD тут нет;
 *  - `record` — начать запись сразу при запуске.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)

        if (intent?.getBooleanExtra("wave_test", false) == true) {
            Log.i(TAG, "=== самопроверка полос запущена из интента ===")
            WaveSelfTest.run()
            Log.i(TAG, "=== самопроверка полос закончена ===")
        }

        intent?.getIntExtra("threads", 0)?.let { count ->
            if (count > 0) {
                GigaAmOnnxRecognizer.intraOpThreads = count
                Log.i(TAG, "потоки распознавания заданы из интента: %d".format(count))
            }
        }

        intent?.getStringExtra("asr_file")?.let { name -> thread(name = "AsrFile") { recognizeWhole(name) } }
        intent?.getStringExtra("asr_replay")?.let { name -> thread(name = "AsrReplay") { replayThroughLive(name) } }

        if (intent?.getBooleanExtra("notes_dump", false) == true) {
            val store = NotesStore(applicationContext)
            val total = store.count()
            Log.i(TAG, "=== заметок в базе: %d ===".format(total))
            store.recent(5).forEach { json -> Log.i(TAG, "заметка: $json") }
        }

        setContent {
            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Color(0xFF8AB4F8),
                        surface = Color(0xFF17181C),
                        background = Color(0xFF101114),
                    ),
            ) {
                RecordingScreen(autoStart = intent?.getBooleanExtra("record", false) == true)
            }
        }
    }

    /** Распознать файл целиком: проверка самого движка. */
    private fun recognizeWhole(name: String) {
        try {
            val manager = ModelManager(applicationContext)
            val file = File(getDir("gigaam", MODE_PRIVATE), name)
            Log.i(
                TAG,
                "модель: %s (размер %d, установлена: %s), файл: %s (%d байт)".format(
                    manager.modelFile.absolutePath,
                    manager.modelFile.length(),
                    manager.isInstalled(),
                    file.absolutePath,
                    file.length(),
                ),
            )
            val pcm = pcmOf(file.readBytes())
            val started = System.currentTimeMillis()
            val recognizer = GigaAmOnnxRecognizer(applicationContext, manager)
            val text = recognizer.transcribe(pcm)
            recognizer.close()
            Log.i(
                TAG,
                "РАСПОЗНАНО за %d мс (%.1f с звука): %s".format(
                    System.currentTimeMillis() - started,
                    pcm.size / 16000.0,
                    text,
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "распознавание не удалось", t)
        }
    }

    /** Проиграть файл через распознавание «на ходу» — как живую речь, в реальном времени. */
    private fun replayThroughLive(name: String) {
        try {
            val manager = ModelManager(applicationContext)
            val file = File(getDir("gigaam", MODE_PRIVATE), name)
            val bytes = file.readBytes()
            Log.i(TAG, "=== воспроизведение %s (%.1f с) через распознавание на ходу ===".format(name, bytes.size / 32000.0))

            val phrases = Collections.synchronizedList(ArrayList<String>())
            val store = NotesStore(applicationContext)
            val transcriber =
                LiveTranscriber(applicationContext, manager) { text ->
                    phrases.add(text)
                    // Тот же путь записи, что на экране: проверяем и распознавание, и сохранение.
                    val id = store.add(text)
                    Log.i(TAG, "заметка сохранена (воспроизведение): id=%d, всего %d".format(id, store.count()))
                }

            val chunkBytes = 16000 / 50 * 2 // 20 мс
            var offset = 0
            var speechChunks = 0
            while (offset < bytes.size) {
                val take = minOf(chunkBytes, bytes.size - offset)
                val part = bytes.copyOfRange(offset, offset + take)
                val rms = rmsOf(part)
                // Энергетический признак речи вместо нейросетевого VAD: в воспроизведении VAD не участвует.
                val probability = if (rms > SPEECH_RMS) 0.9f else 0.05f
                if (rms > SPEECH_RMS) speechChunks++
                transcriber.onChunk(part, take, probability)
                offset += take
                Thread.sleep(20) // реальное время, как при живой записи
            }
            transcriber.finish()
            Thread.sleep(10000) // дать очереди дораспознать остаток

            Log.i(
                TAG,
                "ИТОГ ВОСПРОИЗВЕДЕНИЯ: фраз %d, речи во фрагментах %d: %s".format(
                    phrases.size,
                    speechChunks,
                    phrases.joinToString(" | "),
                ),
            )
            transcriber.close()
        } catch (t: Throwable) {
            Log.e(TAG, "воспроизведение не удалось", t)
        }
    }

    private fun pcmOf(bytes: ByteArray): ShortArray =
        ShortArray(bytes.size / 2) { i ->
            ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }

    private fun rmsOf(bytes: ByteArray): Int {
        val samples = bytes.size / 2
        if (samples == 0) return 0
        var sum = 0.0
        for (i in 0 until samples) {
            val sample = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            sum += sample.toDouble() * sample
        }
        return sqrt(sum / samples).toInt()
    }

    private companion object {
        const val TAG = "GlasNotesDev"
        const val SPEECH_RMS = 300
    }
}
