package com.glassnotes.dev

import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Громкость для полос записи, 0..1 — перенос алгоритма, проверенного на телефоне в WebView-сборке
 * (2.73-giga). Там он жил в JS поверх уровня нативного метра, здесь переносится в Kotlin целиком,
 * потому что при переезде на нативный код логика должна остаться нативной.
 *
 * Схема в два шага, и обе части нужны:
 *  1. фон комнаты — 10-й процентиль огибающей за 10 секунд. На ровном окне процентиль равен самому
 *     сигналу, поэтому на старте фон сразу верный; паузы в речи удерживают его на тишине, отчего
 *     полосы не проседают при длинном монологе (на коротком окне с высоким процентилем — проседают);
 *  2. недавний пик с забыванием за ~3–4 с: шкала растягивается под него, поэтому громкая речь идёт
 *     почти до верха, а не до трети, как при угаданном коэффициенте.
 * Плюс мёртвая зона: колебания тишины полос не рисуют.
 *
 * Числа совпадают с проверенными в 2.73: тишина → 0.00, речь → 1.00, длительная речь → 1.00,
 * возврат в тишину → 0.00 (см. WaveSelfTest — он гоняет ровно эти четыре случая).
 */
class WaveLevel {
    private val history = ArrayDeque<Float>(HISTORY + 1)
    private var env = 0f
    private var floorLevel = -1f
    private var peakLevel = 0f

    /** Фон и пик — только для логов и самопроверки. */
    val floor: Float get() = max(0f, floorLevel)

    val peak: Float get() = peakLevel

    fun reset() {
        history.clear()
        env = 0f
        floorLevel = -1f
        peakLevel = 0f
    }

    fun value(
        level: Float,
        frame: Int,
    ): Float {
        val sample = level.coerceIn(0f, 1f)

        // Огибающая: подъём быстрее спада, чтобы полосы успевали за слогами.
        val k = if (sample > env) ATTACK else RELEASE
        env += (sample - env) * k

        history.addLast(env)
        if (history.size > HISTORY) history.removeFirst()

        // Процентиль считаем не каждый кадр: сортировка 600 чисел шесть раз в секунду — это дёшево,
        // но и незачем чаще.
        if (floorLevel < 0f || frame % 10 == 0) {
            floorLevel = percentile(history, FLOOR_PERCENT)
        }

        val rel = max(0f, env - floorLevel - NOISE_GATE)
        peakLevel = max(rel, peakLevel * PEAK_DECAY)

        return if (rel > 0f) {
            min(1f, (rel / max(peakLevel, MIN_PEAK)).pow(CURVE))
        } else {
            0f
        }
    }

    private fun percentile(
        values: ArrayDeque<Float>,
        fraction: Float,
    ): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.toFloatArray()
        sorted.sort()
        val index = min(sorted.size - 1, (sorted.size * fraction).toInt())
        return sorted[index]
    }

    companion object {
        /** 10 секунд при 60 кадрах в секунду. */
        const val HISTORY = 600

        const val FLOOR_PERCENT = 0.10f
        const val NOISE_GATE = 0.05f
        const val PEAK_DECAY = 0.994f
        const val MIN_PEAK = 0.10f
        const val CURVE = 0.85f
        const val ATTACK = 0.55f
        const val RELEASE = 0.12f
    }
}

/**
 * Проверка алгоритма без микрофона: тем же синтетическим сигналом, что и на телефоне в 2.73.
 * Запуск: am start -n com.glassnotes.dev/.MainActivity --ez wave_test true, результат — в логе.
 */
object WaveSelfTest {
    private const val TAG = "GlasNotesDev"

    fun run() {
        val stages =
            listOf(
                Triple("A тишина 0.30", 0.30f, 150),
                Triple("B речь 0.65", 0.65f, 90),
                Triple("C длинная речь 0.65 (5 с)", 0.65f, 300),
                Triple("D возврат в тишину 0.30", 0.30f, 240),
                Triple("E тишина 0.30 дальше", 0.30f, 400),
            )

        val wave = WaveLevel()
        var frame = 0

        for ((name, value, count) in stages) {
            val tail = ArrayDeque<Float>()
            repeat(count) {
                frame++
                tail.addLast(wave.value(value, frame))
                if (tail.size > 25) tail.removeFirst()
            }
            Log.i(
                TAG,
                "САМОПРОВЕРКА %s: полосы %.2f (макс %.2f), фон %.3f, пик %.3f, кадров %d".format(
                    name,
                    tail.average(),
                    tail.max(),
                    wave.floor,
                    wave.peak,
                    frame,
                ),
            )
        }
    }
}
