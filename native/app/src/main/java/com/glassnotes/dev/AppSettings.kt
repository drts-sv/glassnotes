package com.glassnotes.dev

import androidx.compose.ui.unit.dp

/**
 * Настройки приложения — те же ключи, что в боевом приложении (`localStorage`/таблица `kv`),
 * поэтому значения переживают переустановку и совпадают по смыслу с веб-версией.
 *
 * Значения по умолчанию взяты из `index.html` (`DEFAULT_TIME_SETTINGS`, ползунки настроек),
 * а не подобраны на глаз.
 */
object AppSettings {
    // ---- ключи (как в боевом) ----
    const val REVIEW_TIME = "reviewTime"
    const val REVIEW_ENABLED = "reviewEnabled"
    const val REVIEW_SHOWN_FOR = "reviewShownFor"
    const val UNDO_SEC = "undoTimerSec"

    /** Удержание свайпа: сколько держать за порогом, чтобы сработало действие. */
    const val HOLD_DONE_SEC = "holdDoneSec"
    const val HOLD_DELETE_SEC = "holdDeleteSec"
    const val CAT_SHEET_SEC = "catSheetTimerSec"
    const val ASK_CATEGORY = "askCategory"
    const val VOICE_CATEGORY = "voiceCategory"
    const val FILLER = "fillerCleanup"
    const val GLASS_ALPHA = "glassAlpha"
    const val APP_VEIL = "appVeil"
    const val BG_BLUR = "bgBlur"
    const val THEME = "theme"
    const val DEFAULT_THEME = "auto"

    // ---- значения по умолчанию ----
    const val DEFAULT_REVIEW_TIME = "08:00"
    const val DEFAULT_UNDO_SEC = 3
    // Выполнение подтверждается быстрее удаления: удалить случайно дороже.
    const val DEFAULT_HOLD_DONE_SEC = 2
    const val DEFAULT_HOLD_DELETE_SEC = 3
    const val DEFAULT_CAT_SHEET_SEC = 3
    const val DEFAULT_GLASS_ALPHA = 80
    const val DEFAULT_APP_VEIL = 35
    const val DEFAULT_BG_BLUR = 14

    /** Периоды дня — `DEFAULT_TIME_SETTINGS` боевого приложения: подпись → ключ → время. */
    val timeRows: List<Triple<String, String, String>> =
        listOf(
            Triple("🌅 Утро", "morning", "09:00"),
            Triple("☀️ До обеда", "beforeNoon", "11:00"),
            Triple("🍽 Обед", "noon", "13:00"),
            Triple("🌇 После обеда", "afterNoon", "15:00"),
            Triple("🌆 Вечер", "evening", "18:00"),
            Triple("🌙 Ночь", "night", "21:00"),
            Triple("📅 Сегодня (без вр.)", "today", "12:00"),
            Triple("📅 Завтра (без вр.)", "tomorrow", "09:00"),
        )

    fun timeKey(period: String) = "time_$period"

    /** Прочитать целое из `kv` с ограничением диапазона. */
    fun int(
        store: NotesStore,
        key: String,
        fallback: Int,
        range: IntRange,
    ): Int = (store.getKv(key)?.trim()?.toIntOrNull() ?: fallback).coerceIn(range.first, range.last)

    /** Прочитать переключатель: в боевом это «1»/«0» в localStorage, в базе — тем же текстом. */
    fun flag(
        store: NotesStore,
        key: String,
        fallback: Boolean,
    ): Boolean {
        val raw = store.getKv(key)?.trim() ?: return fallback
        return when (raw.lowercase()) {
            "1", "true", "on", "yes" -> true
            "0", "false", "off", "no", "" -> false
            else -> fallback
        }
    }

    fun setFlag(
        store: NotesStore,
        key: String,
        value: Boolean,
    ) {
        store.setKv(key, if (value) "1" else "0")
    }

    /**
     * Перенести сохранённые настройки в вид приложения: плотность стекла, затемнение и размытие
     * читаются прямо из [Design], времена периодов — из [TimeDefaults]. Вызывается при запуске.
     */
    fun applyAll(store: NotesStore) {
        Design.cardAlpha = int(store, GLASS_ALPHA, DEFAULT_GLASS_ALPHA, 15..95) / 100f
        Design.veil = int(store, APP_VEIL, DEFAULT_APP_VEIL, 0..90) / 100f
        Design.backdropBlur = int(store, BG_BLUR, DEFAULT_BG_BLUR, 0..40).dp
        TimeDefaults.load(store)
    }

    /** Режим темы из базы: "auto" (за системой), "dark", "light". */
    fun themeMode(store: NotesStore): String {
        val raw = store.getKv(THEME)?.trim()?.lowercase()
        return if (raw == "light" || raw == "dark" || raw == "auto") raw else DEFAULT_THEME
    }

    /** Ключ дня для флага «ревью показано»: «2026-11-08». */
    fun todayKey(now: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))

    /**
     * Пора ли показывать утреннее ревью: включено, время из настроек уже наступило,
     * и сегодня его ещё не показывали. Как `reviewShownFor` в боевом приложении.
     */
    fun reviewDue(
        store: NotesStore,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!flag(store, REVIEW_ENABLED, true)) return false
        val text = store.getKv(REVIEW_TIME)?.trim()?.ifBlank { null } ?: DEFAULT_REVIEW_TIME
        val parts = text.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val due =
            java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(java.util.Calendar.MINUTE, minute.coerceIn(0, 59))
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        if (now < due) return false
        val shown = store.getKv(REVIEW_SHOWN_FOR)?.trim()
        return shown != todayKey(now)
    }

    /** Время периода с учётом настроек; пустое/неверное — значение по умолчанию. */
    fun periodTime(
        store: NotesStore,
        period: String,
    ): String {
        val fallback = timeRows.firstOrNull { it.second == period }?.third ?: "09:00"
        val saved = store.getKv(timeKey(period))?.trim()
        return if (saved != null && Regex("^\\d{1,2}:\\d{2}$").matches(saved)) saved else fallback
    }
}

/**
 * Текущие переключатели разбора фразы. Выставляются при запуске и из окна настроек,
 * чтобы `parseDateNlu()` без аргументов сразу работал по настройкам.
 */
object NluOptions {
    var voiceCategory: Boolean = true
    var fillerCleanup: Boolean = true
}

/**
 * Времена суток по умолчанию: их подставляет разбор фразы, когда сказано «утром», «вечером»,
 * «сегодня» без часа. Значения приходят из настроек (в боевом — `DEFAULT_TIME_SETTINGS`).
 */
object TimeDefaults {
    /** Значения по умолчанию из боевого приложения. */
    var map: Map<String, Pair<Int, Int>> =
        mapOf(
            "morning" to (9 to 0),
            "beforeNoon" to (11 to 0),
            "noon" to (13 to 0),
            "afterNoon" to (15 to 0),
            "evening" to (18 to 0),
            "night" to (21 to 0),
        )

    fun load(store: NotesStore) {
        val fresh = HashMap(map)
        for ((_, key, fallback) in AppSettings.timeRows) {
            if (key == "today" || key == "tomorrow") continue
            val text = store.getKv(AppSettings.timeKey(key))?.trim() ?: fallback
            val parts = text.split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            fresh[key] = hour.coerceIn(0, 23) to minute.coerceIn(0, 59)
        }
        map = fresh
    }
}
