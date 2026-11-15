package com.glassnotes.dev

import org.json.JSONArray
import org.json.JSONObject

/** Контакт, прикреплённый к заметке (#84): id в телефонной книге, имя и номер. */
data class NoteContact(
    val id: Long,
    val name: String,
    val number: String,
) {
    /** «Иван Иванов» → «ИИ» — для кружка, если нет фото. */
    val initials: String
        get() =
            name.split(' ')
                .filter { it.isNotBlank() }
                .take(2)
                .map { it.first().uppercaseChar() }
                .joinToString("")
                .ifEmpty { "?" }
}

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
    /** Прикреплённый контакт — рисуется кружком в правом нижнем углу карточки. */
    val contact: NoteContact? = null,
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
            contact = parseContact(o),
        )
    } catch (_: Throwable) {
        null
    }

/** Контакт из JSON заметки. Нет поля — нет кружка: заметка живёт как раньше. */
fun parseContact(o: JSONObject): NoteContact? {
    val raw = o.optJSONObject("contact") ?: return null
    val name = raw.optString("name").trim()
    val number = raw.optString("number").trim()
    if (name.isEmpty() || number.isEmpty()) return null
    return NoteContact(id = raw.optLong("id"), name = name, number = number)
}
