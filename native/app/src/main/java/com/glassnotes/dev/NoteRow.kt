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
) {
    val done: Boolean get() = status == "done"
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
        )
    } catch (_: Throwable) {
        null
    }
