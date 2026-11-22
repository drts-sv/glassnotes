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
            // Теги проектов: заметка-баг по конкретному проекту попадает в свой список,
            // а «сбор багов» раскладывает их по проектам, а не в одну кучу.
            Triple("p_glasnote", "GlasNote", Color(0xFF3D7BFF)),
            Triple("p_callscribe", "CallScribe", Color(0xFF00B894)),
            Triple("p_analytics", "Аналитика", Color(0xFFF0932B)),
            Triple("p_airalert", "AirAlert", Color(0xFFEB4D4B)),
            Triple("p_usbterm", "USB-терминал", Color(0xFF6C5CE7)),
            Triple("p_pricetag", "Ценник", Color(0xFFE84393)),
            Triple("p_routerapi", "Баланс AI", Color(0xFF00CEC9)),
            Triple("p_homeassistant", "Умный дом", Color(0xFFF9CA24)),
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

    /**
     * Значок категории для крупной иконки уведомления (раньше рисовалась первая буква названия).
     * Цвет остаётся за категорией — по нему и различаются уведомления в шторке.
     */
    fun glyph(categoryId: String?): String =
        when (categoryId) {
            "important" -> "❗"
            "work" -> "💼"
            "personal" -> "👤"
            "shopping" -> "🛒"
            "dev" -> "💻"
            "other" -> "📌"
            "p_glasnote" -> "📝"
            "p_callscribe" -> "📞"
            "p_analytics" -> "📊"
            "p_airalert" -> "🚨"
            "p_usbterm" -> "🔌"
            "p_pricetag" -> "🏷"
            "p_routerapi" -> "💳"
            "p_homeassistant" -> "🏠"
            else -> "🔔"
        }

    /** Название категории для заголовка уведомления. */
    fun title(categoryId: String?): String? = names.firstOrNull { it.first == categoryId }?.second
}
