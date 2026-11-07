package com.glassnotes.dev

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Категории: цвета и названия в одном месте — их используют и список заметок, и напоминания
 * (цвет уведомления и бейдж берутся отсюда же, чтобы всё совпадало).
 */
object Categories {
    /** Цвета те же, что в боевом приложении (DEFAULT_CATEGORIES). */
    private val palette =
        listOf(
            Triple("important", "Важное", Color(0xFFFF3B30)),
            Triple("work", "Работа", Color(0xFF54A0FF)),
            Triple("personal", "Личное", Color(0xFFFF6B81)),
            Triple("shopping", "Покупки", Color(0xFFFF9F43)),
            Triple("other", "Другое", Color(0xFF888EA8)),
            Triple("dev", "Разработка", Color(0xFF4D6BFE)),
        )

    val colors: Map<String, Color> = palette.associate { it.first to it.third }

    val names: List<Pair<String, String>> = palette.map { it.first to it.second }

    /** Цвет категории для уведомления (обычный int, без Compose). */
    fun androidColor(categoryId: String?): Int =
        colors[categoryId]?.toArgb() ?: Color(0xFF888EA8).toArgb()

    /** Буква для бейджа: первая буква названия категории, «•» — если категории нет. */
    fun letter(categoryId: String?): String {
        val name = names.firstOrNull { it.first == categoryId }?.second ?: return "•"
        return name.first().uppercase()
    }

    /** Название категории для заголовка уведомления. */
    fun title(categoryId: String?): String? = names.firstOrNull { it.first == categoryId }?.second
}
