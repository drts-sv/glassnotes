package com.glassnotes.dev

import org.json.JSONArray
import org.json.JSONObject

/** Одна заметка для списка: то, что нужно показать, а не весь JSON. */
data class NoteRow(
    val id: Long,
    val text: String,
    val time: String,
    val status: String,
    val categoryIds: List<String>,
    /** Время напоминания (мс) — нужно отборам по дате. */
    val datetime: Long? = null,
    val allDay: Boolean = false,
) {
    val done: Boolean get() = status == "done"
}

/**
 * Разбор даты заметки. Формат как в боевом приложении: ISO-подобная строка, но со местным временем
 * (хвостовая Z — наследие формата), поэтому разбираем её как местное время.
 */
private val NOTE_DATE = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)

private fun parseIsoDate(value: String): Long? =
    if (value.isBlank() || value == "null") null else runCatching { NOTE_DATE.parse(value)?.time }.getOrNull()

/**
 * Момент из поля `datetime`. Боевое приложение и создание заметки пишут строку ISO, а правка
 * даты из листа раньше писала число миллисекунд — читаем оба вида, иначе заметка с датой
 * выглядела как «Без даты» (и не попадала в напоминания и ревью).
 */
fun jsonDatetime(o: JSONObject): Long? {
    when (val raw = o.opt("datetime")) {
        null, JSONObject.NULL -> return null
        is Number -> return raw.toLong()
        else -> Unit
    }
    val text = o.optString("datetime").trim()
    if (text.isEmpty() || text == "null") return null
    return parseIsoDate(text) ?: text.toLongOrNull()
}

/** Заметка с датой — то, что нужно планировщику напоминаний. */
data class DueNote(
    val id: Long,
    val text: String,
    val datetime: Long?,
    val allDay: Boolean,
    val categoryId: String?,
    val notified: Boolean,
)

/** Сводка дел для утреннего ревью. */
data class ReviewCounts(
    val overdue: Int,
    val today: Int,
    val noDate: Int,
) {
    val total: Int get() = overdue + today + noDate
}

/** Разбор JSON заметки в строку списка. Формат тот же, что пишет боевое приложение. */
fun parseNote(json: String): NoteRow? =
    try {
        val o = JSONObject(json)
        val categories = ArrayList<String>()
        o.optJSONArray("categoryIds")?.let { array: JSONArray ->
            for (i in 0 until array.length()) categories.add(array.optString(i))
        }
        NoteRow(
            id = o.optLong("id"),
            text = o.optString("text"),
            time = o.optString("time"),
            status = o.optString("status", "active"),
            categoryIds = categories,
            datetime = jsonDatetime(o),
            allDay = o.optBoolean("allDay"),
        )
    } catch (_: Throwable) {
        null
    }
