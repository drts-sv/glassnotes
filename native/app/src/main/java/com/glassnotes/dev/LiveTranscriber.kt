package com.glassnotes.dev

import android.content.Context
import android.util.Log
import ru.gigapisar.model.ModelManager
import ru.gigapisar.speech.GigaAmOnnxRecognizer
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Распознавание «на ходу»: звук копится, VAD отмечает паузу, готовый кусок уходит в GigaAM,
 * распознанная фраза отдаётся наружу — её показывает тикер на экране записи.
 *
 * Почему по паузам, а не по словам: GigaAM — не потоковая модель, ей нужен кусок целиком. Поэтому
 * режем по паузам VAD (0,7 с тишины после минимум 0,4 с речи) и распознаём кусок. По замерам
 * распознавание идёт быстрее реального времени, так что фраза появляется примерно через секунду
 * после паузы.
 *
 * Две детали, без которых получается мусор:
 *  - куски немного ПЕРЕКРЫВАЮТСЯ (0,3 с хвоста уходит в следующий кусок): иначе на границе рвутся слова;
 *  - кусок длиннее 20 секунд распознаём принудительно: в длинном монологе паузы может не быть вовсе,
 *    а ждать нечего.
 */
class LiveTranscriber(
    context: Context,
    modelManager: ModelManager,
    private val onPhrase: (String) -> Unit,
) {
    private val recognizer = GigaAmOnnxRecognizer(context, modelManager)

    /** Одна нить и очередь: две сессии onnxruntime одновременно — лишняя память и риск гонки. */
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "LiveAsr") }

    private val buffer = ArrayList<Short>(16000 * 20)
    private var speechMs = 0
    private var silenceMs = 0
    private var overlap = ShortArray(0)

    @Volatile
    private var closed = false

    /** Куски по 20 мс из записи: пишем в буфер и смотрим вероятность речи от VAD. */
    fun onChunk(
        bytes: ByteArray,
        count: Int,
        speechProbability: Float,
    ) {
        if (closed) return

        synchronized(buffer) {
            var i = 0
            while (i + 1 < count) {
                buffer.add(((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort())
                i += 2
            }
        }

        if (speechProbability >= SPEECH_ON) {
            speechMs += CHUNK_MS
            silenceMs = 0
        } else {
            silenceMs += CHUNK_MS
        }

        val pending = synchronized(buffer) { buffer.size }
        val pauseFound = speechMs >= MIN_SPEECH_MS && silenceMs >= MIN_PAUSE_MS
        if (pauseFound || pending > MAX_SAMPLES) {
            flush()
        }
    }

    /** Конец записи: забираем остаток. */
    fun finish() = flush()

    fun close() {
        closed = true
        worker.shutdown()
        recognizer.close()
    }

    @Synchronized
    private fun flush() {
        if (closed) return

        val samples =
            synchronized(buffer) {
                if (buffer.isEmpty()) null else ShortArray(buffer.size) { buffer[it] }.also { buffer.clear() }
            } ?: return

        val hadSpeech = speechMs >= MIN_SPEECH_MS
        speechMs = 0
        silenceMs = 0

        // Короче четверти секунды — не речь (щелчок, кашель, обрывок).
        if (samples.size < 16000 / 4 || !hadSpeech) return

        val piece = if (overlap.isEmpty()) samples else overlap + samples
        overlap = samples.copyOfRange(max(0, samples.size - OVERLAP_SAMPLES), samples.size)

        worker.execute {
            try {
                val started = System.currentTimeMillis()
                val text = recognizer.transcribe(piece)
                val spent = System.currentTimeMillis() - started
                if (text.isNotBlank()) {
                    Log.i(
                        TAG,
                        "ФРАЗА (распознано %d мс, звука %.1f с): %s".format(spent, piece.size / 16000.0, text),
                    )
                    onPhrase(text)
                } else {
                    Log.i(TAG, "кусок %.1f с распознан пустым".format(piece.size / 16000.0))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "фраза не распозналась", t)
            }
        }
    }

    companion object {
        private const val TAG = "GlasNotesDev"
        private const val CHUNK_MS = 20
        private const val SPEECH_ON = 0.5f
        private const val MIN_SPEECH_MS = 400
        private const val MIN_PAUSE_MS = 700
        private const val OVERLAP_SAMPLES = 16000 * 3 / 10
        private const val MAX_SAMPLES = 16000 * 20
    }
}
