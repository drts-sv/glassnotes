package com.glassnotes.dev

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

private const val TAG = "GlasNoteTts"

/**
 * Озвучка заметок системным TTS (заметка #82). Голос русский, очередь сбрасывается: нажали
 * озвучку второй заметки — первая замолкает. Состояние `speaking` реактивное, чтобы кнопка
 * показывала «⏹» во время речи.
 */
class Speaker(context: Context) {
    private val tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    var ready by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS не поднялся, код $status")
            return
        }
        val result = tts.setLanguage(Locale("ru", "RU"))
        ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking = true
                }

                override fun onDone(utteranceId: String?) {
                    speaking = false
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    speaking = false
                }
            },
        )
        Log.i(TAG, "TTS готов: русский голос %s".format(if (ready) "есть" else "НЕ найден"))
    }

    /** Читает текст с начала; повторное нажатие на другую заметку перебивает предыдущую. */
    fun speak(text: String) {
        if (!ready) {
            Log.w(TAG, "TTS ещё не готов — озвучка пропущена")
            return
        }
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        speaking = true
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "note-%d".format(System.currentTimeMillis()))
        Log.i(TAG, "озвучиваю %d символов".format(clean.length))
    }

    fun stop() {
        tts.stop()
        speaking = false
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        ready = false
        speaking = false
    }
}

/** Текст заметки для озвучки: дата/время — словами, затем сам текст. */
fun speakableText(
    text: String,
    whenText: String,
): String = whenText.trim().let { if (it.isEmpty()) text else "$it. $text" }
