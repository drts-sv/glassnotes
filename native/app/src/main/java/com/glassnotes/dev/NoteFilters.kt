package com.glassnotes.dev

import java.util.Calendar
import java.util.Locale

/**
 * Отбор заметок для главного экрана — правила те же, что в боевом приложении:
 * вкладка (активные/выполненные), отбор по дате, по категории и поиск по тексту.
 *
 * Логика вынесена отдельно от интерфейса: её проверяют тестом (`--es list_filter`, `--es list_query`),
 * а не глазами — интерфейс на телефоне снаружи не прочитать.
 */
object NoteFilters {
    private const val DAY = 24L * 60 * 60 * 1000

    /** Названия отборов — дословно из боевого приложения. */
    val dateOptions: List<Pair<String, String>> =
        listOf(
            "important" to "🔥 Важные",
            "past" to "Просроченные",
            "today" to "Сегодня",
            "tomorrow" to "Завтра",
            "week" to "Ближайшие 7 дней",
            "later" to "Больше чем через неделю",
            "no-date" to "Без срока",
        )

    /** Порядок разделов в списке — как в боевом приложении. */
    val sectionOrder: List<String> = dateOptions.map { it.first }

    fun sectionName(key: String): String = dateOptions.firstOrNull { it.first == key }?.second.orEmpty()

    /**
     * В какой раздел попадает заметка. Важные идут первым разделом, отдельно от своих дат —
     * как в боевом приложении.
     */
    fun bucket(
        note: NoteRow,
        now: Long = System.currentTimeMillis(),
    ): String {
        if (IMPORTANT in note.categoryIds) return "important"
        val at = note.datetime ?: return "no-date"
        val dayStart = startOfDay(now)
        val day = if (note.allDay) startOfDay(at) else at
        return when {
            note.allDay && day < dayStart -> "past"
            !note.allDay && at < dayStart -> "past"
            day < dayStart + DAY -> "today"
            day < dayStart + 2 * DAY -> "tomorrow"
            day < dayStart + 7 * DAY -> "week"
            else -> "later"
        }
    }

    fun startOfDay(now: Long): Long {
        val cal = Calendar.getInstance(Locale("ru"))
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun apply(
        notes: List<NoteRow>,
        tab: String,
        dateFilter: String?,
        categoryFilter: String?,
        query: String,
        now: Long = System.currentTimeMillis(),
    ): List<NoteRow> {
        val dayStart = startOfDay(now)
        val dayEnd = dayStart + DAY
        val weekEnd = dayStart + 7 * DAY
        val text = query.trim().lowercase(Locale("ru"))

        return notes.filter { note ->
            if (tab == "done" && !note.done) return@filter false
            if (tab != "done" && note.done) return@filter false
            if (categoryFilter != null && categoryFilter !in note.categoryIds) return@filter false
            if (text.isNotEmpty() && !note.text.lowercase(Locale("ru")).contains(text)) return@filter false

            when (dateFilter) {
                null -> true
                "important" -> NoteFilters.IMPORTANT in note.categoryIds
                "no-date" -> note.datetime == null
                else -> {
                    val at = note.datetime ?: return@filter false
                    // Дело «на весь день» относится к своему дню: в «Просроченные» попадает
                    // только со следующего дня — как в боевом приложении.
                    val day = if (note.allDay) startOfDay(at) else at
                    when (dateFilter) {
                        "past" -> if (note.allDay) day < dayStart else at < now && at < dayStart
                        "today" -> day in dayStart until dayEnd
                        "tomorrow" -> day in dayEnd until dayEnd + DAY
                        "week" -> day in dayEnd until weekEnd
                        "later" -> day >= weekEnd
                        else -> true
                    }
                }
            }
        }
    }

    const val IMPORTANT = "important"

    /** Категория «Разработка» — у неё отдельный раздел в списке. */
    const val DEVELOPMENT = "dev"
}
