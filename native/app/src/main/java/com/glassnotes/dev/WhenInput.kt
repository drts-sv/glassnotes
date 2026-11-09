package com.glassnotes.dev

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Поле «Когда» в листе заметки: разбор того, что человек ввёл руками, и показ обратно.
 *
 * Зачем отдельным объектом: раньше в поле был один формат `dd.MM.yyyy HH:mm`, и дата без времени
 * либо не разбиралась (заметка молча уходила в «Без даты»), либо получала полночь вместо утра.
 * Правила одинаковые с голосовым разбором (`DateNlu`): нет названного часа — 09:00 и «весь день».
 */
object WhenInput {
    private val US = Locale.US
    private val DATE_TIME = SimpleDateFormat("dd.MM.yyyy HH:mm", US).apply { isLenient = false }
    private val DATE_FULL = SimpleDateFormat("dd.MM.yyyy", US).apply { isLenient = false }
    private val DATE_SHORT = SimpleDateFormat("dd.MM", US).apply { isLenient = false }
    private val TIME_ONLY = SimpleDateFormat("HH:mm", US).apply { isLenient = false }

    /** Час «весь день» — тот же, что у голосового разбора («утро» в боевых настройках). */
    const val ALL_DAY_HOUR = 9

    /** Что во введённом тексте: момент (мс) и признак «дело на весь день». */
    data class When(val ms: Long, val allDay: Boolean)

    /**
     * Разбор введённого текста:
     * - `10.11.2026 18:30` — дата и время;
     * - `10.11.2026`, `10.11` — только дата: 09:00 и «весь день» (год текущий, прошедшая дата — следующий);
     * - `18:30` — только время: сегодня, а если уже прошло — завтра;
     * - остальное — разбор естественной речи через [parseDateNlu].
     */
    fun parse(raw: String, now: Long = System.currentTimeMillis()): When? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        parseAt(DATE_TIME, text)?.let { return When(at(it), allDay = false) }

        parseAt(DATE_FULL, text)?.let { cal ->
            cal.set(Calendar.HOUR_OF_DAY, ALL_DAY_HOUR)
            cal.set(Calendar.MINUTE, 0)
            return When(at(cal), allDay = true)
        }

        parseAt(DATE_SHORT, text)?.let { cal ->
            // Год не назван: текущий, а если день уже прошёл — следующий.
            val today = Calendar.getInstance().apply { timeInMillis = now }
            cal.set(Calendar.YEAR, today.get(Calendar.YEAR))
            cal.set(Calendar.HOUR_OF_DAY, ALL_DAY_HOUR)
            cal.set(Calendar.MINUTE, 0)
            if (cal.timeInMillis < startOfDay(now)) cal.add(Calendar.YEAR, 1)
            return When(at(cal), allDay = true)
        }

        parseAt(TIME_ONLY, text)?.let { cal ->
            val today = Calendar.getInstance().apply { timeInMillis = now }
            cal.set(Calendar.YEAR, today.get(Calendar.YEAR))
            cal.set(Calendar.MONTH, today.get(Calendar.MONTH))
            cal.set(Calendar.DAY_OF_MONTH, today.get(Calendar.DAY_OF_MONTH))
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
            return When(at(cal), allDay = false)
        }

        // «завтра в 8», «в понедельник», «через час» — как при диктовке.
        val nlu = parseDateNlu(text)
        val ms = nlu.datetime ?: return null
        return When(ms, nlu.allDay)
    }

    /** Как показать дату в поле: «весь день» — без часа, иначе с часом. */
    fun display(ms: Long?, allDay: Boolean): String =
        when {
            ms == null -> ""
            allDay -> DATE_FULL.format(Date(ms))
            else -> DATE_TIME.format(Date(ms))
        }

    /** Таблица для проверки на телефоне: `adb shell am start ... --ez when_test true`. */
    fun selfTest(now: Long): String {
        val cases =
            listOf(
                "10.11.2026 18:30",
                "10.11.2026",
                "10.11",
                "18:30",
                "8:00",
                "завтра в 8",
                "в понедельник",
                "через час",
                "абракадабра",
            )
        return cases.joinToString(separator = "\n") { raw ->
            val w = parse(raw, now)
            val mark = if (w != null && w.allDay) " (весь день)" else ""
            if (w == null) "$raw -> нет" else "$raw -> ${display(w.ms, w.allDay)}$mark"
        }
    }

    private fun startOfDay(now: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun parseAt(format: SimpleDateFormat, text: String): Calendar? {
        // Строка должна разобраться ЦЕЛИКОМ: иначе «10.11.2026 18:30 мусор» тихо станет датой.
        val pos = java.text.ParsePosition(0)
        val parsed = runCatching { format.parse(text, pos) }.getOrNull() ?: return null
        if (pos.index != text.length) return null
        val cal = Calendar.getInstance()
        cal.time = parsed
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }

    private fun at(cal: Calendar): Long {
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
