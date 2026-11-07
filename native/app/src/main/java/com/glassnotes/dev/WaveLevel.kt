package com.glassnotes.dev

import android.util.Log
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Громкость для полос записи, 0..1 — перенос алгоритма из WebView-сборки (2.73-giga) плюс правки
 * по замерам на живом микрофоне.
 *
 * Что было не так в первых прогонах (видно в логе телефона):
 *  1. окно фона считалось в КАДРАХ: 600 кадров на 120-герцевом экране — это 5 секунд, а не 10.
 *     Теперь окно задано в СЕКУНДАХ, а огибающая сглаживается по времени кадра (dt), поэтому результат
 *     не зависит от частоты экрана;
 *  2. в тихой комнате много отсчётов упирается в нижнюю границу шкалы (rms около 20 → ровно 0),
 *     и 10-й процентиль выбирал именно эти нули: фон становился 0,000, а «громче фона» — почти всё
 *     подряд, отчего полосы стояли полными в тишине. Процентиль поднят, и он выведен в слайдер;
 *  3. шкала была зажата до −60 дБ: тишина упиралась в ноль и превращалась в кучу одинаковых нулей,
 *     из-за которых процентиль давал фон 0,000 (полосы полные в тишине). Шкала расширена до 80 дБ —
 *     тогда тишина не зануляется, и низкий процентиль (0.12) снова работает;
 *  4. почему процентиль низкий, а не «поустойчивее»: доля тишины в окне должна быть меньше процентиля.
 *     При 0.30 нужно 30% пауз за 10 секунд — этого не хватает, и при длинной речи фон подтягивался
 *     к голосу (замер самопроверки: полосы 0,00 при речи 5 с, фон 0,649).
 *
 * Значения по умолчанию подобраны владельцем на живом микрофоне 06.11.2026 (лог: тишина rms 10–31 →
 * полоса 0,00; речь rms 186–617 → 0,64–1,00): шкала 65 дБ, процентиль 0.21, порог тишины 0.11,
 * кривая 0.54, затухание пика 0.70.
 */
class WaveLevel(
    /** Ширина шкалы сырой громкости в дБ. Шире — меньше зануления в тишине (см. правку 4 ниже). */
    var dbWindow: Float = 65f,
    /** Какой процентиль огибающей считать фоном (0.1 — «самое тихое», 0.3 — устойчивее к всплескам). */
    var floorPercent: Float = 0.21f,
    /** Мёртвая зона над фоном: колебания тишины полос не рисуют. */
    var noiseGate: Float = 0.11f,
    /** Кривая отображения: меньше 1 подтягивает слабые значения. */
    var curve: Float = 0.54f,
    /** Окно оценки фона, секунды. */
    var windowSeconds: Float = 10f,
    /** Сколько остаётся от недавнего пика за секунду. */
    var peakDecayPerSecond: Float = 0.70f,
    /** Время подъёма огибающей, секунды. */
    var attackSeconds: Float = 0.03f,
    /** Время спада огибающей, секунды. */
    var releaseSeconds: Float = 0.14f,
    /** Ниже этого значения пик не опускаем. */
    var minPeak: Float = 0.10f,
) {
    /** «Время в наносекундах, огибающая в тысячных» — время нужно для независимости от кадров. */
    private val samples = ArrayDeque<LongArray>(1024)
    private var env = 0f
    private var floorLevel = -1f
    private var peakLevel = 0f
    private var lastNs = 0L

    val floor: Float get() = max(0f, floorLevel)

    val peak: Float get() = peakLevel

    val envelope: Float get() = env

    fun reset() {
        samples.clear()
        env = 0f
        floorLevel = -1f
        peakLevel = 0f
        lastNs = 0L
    }

    /** Сырая громкость rms (0..32767) → 0..1 по шкале «0 dBFS минус dbWindow» … «0 dBFS». */
    fun rawFromRms(rms: Int): Float {
        val db = 20.0 * log10(max(rms, 1) / 32768.0)
        return ((db + dbWindow) / dbWindow).coerceIn(0.0, 1.0).toFloat()
    }

    fun value(
        input: Float,
        nowNs: Long,
    ): Float {
        val sample = input.coerceIn(0f, 1f)
        val dt = if (lastNs == 0L) 1f / 60f else ((nowNs - lastNs) / 1e9f).coerceIn(0.001f, 0.25f)
        lastNs = nowNs

        // Постоянные времени вместо коэффициентов на кадр: одинаковое поведение при 60 и 120 кадрах.
        val attack = 1f - exp(-dt / attackSeconds)
        val release = 1f - exp(-dt / releaseSeconds)
        env += (sample - env) * (if (sample > env) attack else release)

        samples.addLast(longArrayOf(nowNs, (env * 1000f).toLong()))
        val cutoff = nowNs - (windowSeconds * 1e9f).toLong()
        while (samples.isNotEmpty() && samples.first()[0] < cutoff) samples.removeFirst()

        floorLevel = percentile()
        val rel = max(0f, env - floorLevel - noiseGate)
        peakLevel = max(rel, peakLevel * peakDecayPerSecond.pow(dt))

        return if (rel > 0f) min(1f, (rel / max(peakLevel, minPeak)).pow(curve)) else 0f
    }

    private fun percentile(): Float {
        if (samples.isEmpty()) return 0f
        val sorted = samples.map { it[1] / 1000f }.sorted()
        val index = min(sorted.size - 1, (sorted.size * floorPercent).toInt())
        return sorted[index]
    }

    fun describe(): String =
        "шкала %.0f дБ, процентиль %.2f, порог %.2f, кривая %.2f, затухание пика %.2f".format(
            dbWindow,
            floorPercent,
            noiseGate,
            curve,
            peakDecayPerSecond,
        )
}

/**
 * Проверка алгоритма без микрофона: тот же синтетический сигнал, что и на телефоне в 2.73,
 * плюс случай «тихая комната без речи» — на нём и видно было ошибку с фоном 0,000.
 * Запуск: `am start -n com.glassnotes.dev/.MainActivity` с extra-полем wave_test true.
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
        var ns = 0L

        for ((name, value, count) in stages) {
            val tail = ArrayDeque<Float>()
            repeat(count) {
                frame++
                ns += 16_666_667L
                tail.addLast(wave.value(value, ns))
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

        // Тихая комната без речи: так выглядел живой микрофон, когда фон уходил в 0,000.
        val quiet = WaveLevel()
        var quietNs = 0L
        val quietTail = ArrayDeque<Float>()
        repeat(600) {
            quietNs += 16_666_667L
            val level = if (it % 37 == 0) 0.22f else 0.20f
            quietTail.addLast(quiet.value(level, quietNs))
            if (quietTail.size > 60) quietTail.removeFirst()
        }
        Log.i(
            TAG,
            "САМОПРОВЕРКА F тихая комната (0.20, речи нет): полосы %.2f (макс %.2f), фон %.3f".format(
                quietTail.average(),
                quietTail.max(),
                quiet.floor,
            ),
        )
    }
}
