package com.glassnotes.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import ru.gigapisar.audio.SpeechDetector
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Нейросетевой определитель речи — Silero VAD (MIT, ~2.2 МБ, лежит в assets/vad).
 *
 * Зачем: порог по громкости в шуме не работает. В шумной комнате уровень не опускается
 * ниже порога никогда, «тишина» не наступает, и запись не останавливается сама (баг 1);
 * переключение Bluetooth-маршрута даёт всплеск, который порог принимает за речь (баг 3).
 * Сеть отличает голос от шума по спектру, а не по амплитуде, и на это не покупается.
 *
 * Интерфейс модели (16 кГц): входы `input` [1,512], `state` [2,1,128], `sr` [1];
 * выходы `output` [1,1] — вероятность речи, `stateN` [2,1,128] — состояние в следующий кадр.
 * Если у модели другой интерфейс, детектор честно скажет `available = false`, и авто-стоп
 * останется на пороге по уровню (лучше так, чем молча не работать).
 */
class SileroVad(
    context: Context,
    private val frameSize: Int = SpeechDetector.FRAME_SAMPLES_16K,
) : SpeechDetector {

    private val environment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var stateNames: Pair<String, String>? = null
    private var state = FloatArray(2 * STATE_SIZE)
    private var calls = 0
    private var outputNames: List<String> = emptyList()

    /**
     * Хвост предыдущего кадра: модель ждёт на входе 64 его сэмпла плюс 512 новых. Без этого
     * окно неполное и сеть отвечает «не речь» на живую громкую речь (проверено: rms 25508,
     * вероятность 0,00 — вот из-за чего авто-стоп не срабатывал).
     */
    private var context = FloatArray(CONTEXT_SIZE)

    override val available: Boolean
        get() = session != null && stateNames != null

    init {
        try {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(1)
            val created = environment.createSession(bytes, options)
            options.close()
            session = created
            val names = created.inputNames
            val shapes = names.joinToString(", ") { name ->
                val info = created.inputInfo[name]?.info
                val shape = (info as? ai.onnxruntime.TensorInfo)
                    ?.shape
                    ?.joinToString("x", "[", "]") { it.toString() }
                    ?: "?"
                name + shape
            }
            outputNames = created.outputNames.toList()
            Log.i(TAG, "VAD: модель загружена, кадр $frameSize; входы: $shapes; выходы: $outputNames")
            stateNames = if (names.contains("state")) "state" to "stateN" else null
            if (stateNames == null) {
                Log.w(TAG, "VAD: неизвестный интерфейс модели — авто-стоп останется по уровню")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "VAD: модель не загрузилась, авто-стоп будет по уровню: $t")
            session = null
        }
    }

    @Synchronized
    override fun probability(frame: FloatArray): Float {
        val current = session ?: return 0f
        val names = stateNames ?: return 0f
        if (frame.size != frameSize) return 0f

        val window = FloatArray(context.size + frameSize)
        System.arraycopy(context, 0, window, 0, context.size)
        System.arraycopy(frame, 0, window, context.size, frameSize)
        context = window.copyOfRange(window.size - CONTEXT_SIZE, window.size)

        val input = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(window),
            longArrayOf(1L, window.size.toLong()),
        )
        val stateTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(state),
            longArrayOf(2L, 1L, STATE_SIZE.toLong()),
        )
        val rateTensor = OnnxTensor.createTensor(
            environment,
            java.lang.Long.valueOf(SAMPLE_RATE),
        )

        try {
            current.run(
                mapOf(
                    "input" to input,
                    names.first to stateTensor,
                    "sr" to rateTensor,
                ),
            ).use { result ->
                @Suppress("UNCHECKED_CAST")
                val speechTensor = (result.get("output").orElse(null) as? OnnxTensor) ?: result[0]
                @Suppress("UNCHECKED_CAST")
                val stateTensorNext = (result.get("stateN").orElse(null) as? OnnxTensor) ?: result[1]
                @Suppress("UNCHECKED_CAST")
                val speech = (speechTensor.value as Array<FloatArray>)[0][0]
                @Suppress("UNCHECKED_CAST")
                val next = stateTensorNext.value as Array<Array<FloatArray>>
                if (calls < 3) {
                    calls++
                    Log.i(TAG, "VAD: кадр $calls -> речь $speech (выходы: $outputNames)")
                }
                var index = 0
                for (layer in 0 until 2) {
                    for (batch in 0 until 1) {
                        for (n in 0 until STATE_SIZE) {
                            state[index++] = next[layer][batch][n]
                        }
                    }
                }
                return speech
            }
        } catch (t: Throwable) {
            Log.e(TAG, "VAD: сбой вывода: $t")
            return 0f
        } finally {
            input.close()
            stateTensor.close()
            rateTensor.close()
        }
    }

    override fun reset() {
        state = FloatArray(2 * STATE_SIZE)
        context = FloatArray(CONTEXT_SIZE)
    }

    override fun close() {
        try {
            session?.close()
        } catch (ignored: Throwable) {
        }
        session = null
    }

    private companion object {
        const val TAG = "GlassNotes"
        const val ASSET = "vad/silero_vad.onnx"
        const val SAMPLE_RATE = 16_000L
        const val STATE_SIZE = 128

        /** 64 сэмпла контекста при 16 кГц, 32 при 8 кГц (см. обёртку Silero). */
        const val CONTEXT_SIZE = 64
    }
}
