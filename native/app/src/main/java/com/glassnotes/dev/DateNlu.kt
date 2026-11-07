package com.glassnotes.dev

import java.util.Calendar
import java.util.Locale

/**
 * Разбор даты/времени/категории из текста заметки — нативный перенос NLU боевого
 * WebView-приложения (`index.html`, блок `=== NLU START ===`). Логика один-в-один, включая
 * порядок правил и «хитрости», которые там описаны комментариями.
 *
 * Зависимостей нет: только Kotlin и java.util (Calendar/Locale). Никакого Android API и JSON.
 */
data class ParsedNote(
    val text: String, // текст заметки без распознанных даты/времени/категории
    val datetime: Long?, // момент напоминания, null если даты нет
    val allDay: Boolean, // дата без названного часа = дело на весь день
    val categoryId: String?, // категория, если распознана словом
    val dateNamed: Boolean, // день назван словами («сегодня», «завтра», «4 ноября»)
)

/** Локаль для русских названий месяцев/дней. */
private val RU = Locale("ru")

/** Категории боевого приложения (DEFAULT_CATEGORIES из index.html): id → имя. */
private val DEFAULT_CATEGORIES: List<Pair<String, String>> =
    listOf(
        "important" to "Важно",
        "work" to "Работа",
        "personal" to "Личное",
        "shopping" to "Покупки",
        "other" to "Другое",
        "dev" to "Разработка",
    )

/**
 * Времена суток по умолчанию (DEFAULT_TIME_SETTINGS из index.html). В боевом приложении их
 * задаёт пользователь в настройках; здесь взяты штатные значения.
 */
private val PERIOD_TIMES: Map<String, Pair<Int, Int>> =
    mapOf(
        "morning" to (9 to 0),
        "beforeNoon" to (11 to 0),
        "noon" to (13 to 0),
        "afterNoon" to (15 to 0),
        "evening" to (18 to 0),
        "night" to (21 to 0),
    )

/** Регистронезависимый регекс с юникод-фолдингом: для кириллицы нужен (?u), иначе /i бесполезен. */
private fun ci(source: String): Regex = Regex("(?iu)$source")

private val WS = Regex("\\s+")

// === ЧИСТКА ДИКТОВКИ ===

/**
 * «напомни …», «запиши …», «создай заметку …» — это команда, а не текст заметки.
 * Границу слова задаём явно: \b по кириллице в regex не работает.
 */
private val CMD_WORDS =
    ci("^[ ]*(?:напомни(?:ть)?|напоминание|запиши|записать|создай|добавь|заметка|отметь)(?![а-яё])[ ,:.-]*")

/** Мычание: убираем всегда. */
private val FILLER_SOUNDS =
    arrayOf(
        "э-э-э", "э-э", "эээ", "ээ", "эм", "эмм", "м-м-м", "ммм", "м-м", "мм",
        "а-а-а", "а-а", "ааа", "н-н", "ннн", "у-у", "уу", "ы-ы", "гм", "кхм", "э-хе",
    )

/** Слова-паразиты: убираем только как отдельные слова. Порядок — как в оригинале. */
// Слова-паразиты. ВАЖНО: здесь только то, что почти всегда мусор. «это», «слушай», «знаешь»,
// «видишь» из списка убраны осознанно: в диктовке они чаще смысл, чем паразит — на живой фразе
// «Привет. Это проверка распознавания» чистка съедала «Это» и портила заметку (07.11.2026).
private val FILLER_WORDS =
    arrayOf(
        "как бы", "как-то так", "типа того", "короче говоря", "собственно говоря",
        "на самом деле", "в общем-то", "вообще-то", "в принципе", "так сказать",
        "так вот", "вот это вот", "это самое", "и так далее", "так далее", "то есть",
        "ну вот", "ну это", "как его", "как её", "собственно", "понимаешь", "знаешь",
        "типа", "короче", "значит", "вообще", "в общем", "кстати",
        "походу", "прикинь", "блин", "в натуре", "ну", "вот", "хм",
    )

private fun fillerRe(words: Array<String>): Regex =
    ci("(?<![а-яё])(" + words.joinToString("|") + ")(?![а-яё])")

/** Повторы от диктовки: «и и», «что что». */
private val REPEAT_RE = ci("(?<![а-яё])([а-яё]{2,})\\s+\\1(?![а-яё])")

/** Убирает из текста диктовки команду, мычание, слова-паразиты и повторы слов. */
internal fun stripCommandWords(text: String): String = CMD_WORDS.replace(text, "").trim()

internal fun cleanDictation(
    text: String,
    fillerCleanup: Boolean = true,
): String {
    if (!fillerCleanup) return text
    var out = text
    // 1. затянутые буквы: в русском три одинаковых подряд не встречаются («нууу» → «ну»)
    out = Regex("([а-яёА-ЯЁ])\\1{2,}").replace(out) { it.groupValues[1] }
    // 2. мычание («э-э-э», «эмм», «а-а-а») — убираем всегда
    out = fillerRe(FILLER_SOUNDS).replace(out, " ")
    // 3. слова-паразиты — только как отдельные слова
    out = fillerRe(FILLER_WORDS).replace(out, " ")
    // 4. повторы от диктовки: «и и», «что что»
    out = REPEAT_RE.replace(out) { it.groupValues[1] }
    // 5. осиротевшие запятые и лишние пробелы
    out = Regex("\\s*,\\s*(?=[,;.!?])").replace(out, "")
    out = Regex("^[\\s,;:.!?-]+").replace(out, "")
    out = WS.replace(out, " ")
    return out.trim()
}

// === ОРДИНАЛЫ ===

/**
 * Ядро распознавания нормализует цифры в слова («4 ноября» → «четвёртого ноября»), а правило
 * месяца понимает только цифры — поэтому дату приходится возвращать к числу.
 */
private val ORDINALS =
    mapOf(
        "первое" to 1, "первого" to 1, "второе" to 2, "второго" to 2, "третье" to 3, "третьего" to 3,
        "четвёртое" to 4, "четвёртого" to 4, "четвертое" to 4, "четвертого" to 4, "пятое" to 5, "пятого" to 5,
        "шестое" to 6, "шестого" to 6, "седьмое" to 7, "седьмого" to 7, "восьмое" to 8, "восьмого" to 8,
        "девятое" to 9, "девятого" to 9, "десятое" to 10, "десятого" to 10,
        "одиннадцатое" to 11, "одиннадцатого" to 11, "двенадцатое" to 12, "двенадцатого" to 12,
        "тринадцатое" to 13, "тринадцатого" to 13, "четырнадцатое" to 14, "четырнадцатого" to 14,
        "пятнадцатое" to 15, "пятнадцатого" to 15, "шестнадцатое" to 16, "шестнадцатого" to 16,
        "семнадцатое" to 17, "семнадцатого" to 17, "восемнадцатое" to 18, "восемнадцатого" to 18,
        "девятнадцатое" to 19, "девятнадцатого" to 19, "двадцатое" to 20, "двадцатого" to 20,
        "двадцать первое" to 21, "двадцать первого" to 21, "двадцать второе" to 22, "двадцать второго" to 22,
        "двадцать третье" to 23, "двадцать третьего" to 23, "двадцать четвёртое" to 24, "двадцать четвёртого" to 24,
        "двадцать пятое" to 25, "двадцать пятого" to 25, "двадцать шестое" to 26, "двадцать шестого" to 26,
        "двадцать седьмое" to 27, "двадцать седьмого" to 27, "двадцать восьмое" to 28, "двадцать восьмого" to 28,
        "двадцать девятое" to 29, "двадцать девятого" to 29, "тридцатое" to 30, "тридцатого" to 30,
        "тридцать первое" to 31, "тридцать первого" to 31,
    )

private const val MONTH_STEM = "январ|феврал|март|апрел|ма[йя]|июн|июл|август|сентябр|октябр|ноябр|декабр"

private val ORDINAL_RE =
    ci(
        "(?<![а-яё])(" +
            ORDINALS.keys.sortedByDescending { it.length }.joinToString("|") +
            ")\\s+(?=" + MONTH_STEM + ")",
    )

/** «четвёртого ноября» и «4-го ноября» → «4 ноября». */
private fun normalizeOrdinalDates(text: String): String {
    var out = ci("(\\d{1,2})\\s*[- ]?\\s*го\\s+(?=" + MONTH_STEM + ")").replace(text) { it.groupValues[1] + " " }
    out = ORDINAL_RE.replace(out) { m -> ORDINALS[m.groupValues[1].lowercase(RU)].toString() }
    return out
}

// === ОТНОСИТЕЛЬНОЕ ВРЕМЯ ===

/**
 * «через 30 минут», «через полчаса», «через час», «через полтора часа». Порядок важен:
 * сначала точные единицы, «час» — в конце (иначе «через час» поймает не то правило).
 */
private val RELATIVE_UNITS: List<Pair<String, Long>> =
    listOf(
        "(\\d+(?:[.,]\\d+)?)\\s*(?:минут[а-яё]*|мин)(?![а-яё])" to 60_000L,
        "(\\d+(?:[.,]\\d+)?)\\s*час[а-яё]*(?![а-яё])" to 3_600_000L,
        "(\\d+)\\s*(?:дн[а-яё]*|день|сут[а-яё]*)(?![а-яё])" to 86_400_000L,
        "(\\d+)\\s*недел[а-яё]*(?![а-яё])" to 604_800_000L,
        "(?:пол|половину)\\s*час[а-яё]*(?![а-яё])" to 1_800_000L,
        "полчаса(?![а-яё])" to 1_800_000L,
        "полтора\\s*час[а-яё]*(?![а-яё])" to 5_400_000L,
        "час(?![а-яё])" to 3_600_000L,
        "минуту(?![а-яё])" to 60_000L,
    )

private fun parseRelativeTime(lower: String): Pair<Long, String>? {
    if (!lower.contains("через")) return null
    for ((src, ms) in RELATIVE_UNITS) {
        val m = Regex("через\\s+" + src).find(lower) ?: continue
        // «через 30 минут» — число из фразы × размер единицы;
        // «через час» / «через полчаса» числа не имеют, это уже готовое значение.
        val g1 = m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
        val count = g1?.replace(',', '.')?.toDoubleOrNull() ?: 1.0
        return (ms * count).toLong() to m.value
    }
    return null
}

// === АБСОЛЮТНАЯ ДАТА ===

private val MONTH_NAMES =
    mapOf(
        "января" to 0, "февраля" to 1, "марта" to 2, "апреля" to 3, "мая" to 4, "июня" to 5,
        "июля" to 6, "августа" to 7, "сентября" to 8, "октября" to 9, "ноября" to 10, "декабря" to 11,
    )

private val DATE_TEXT_MONTH =
    Regex(
        "(\\d{1,2})\\s*(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)" +
            "\\s*(\\d{4}|\\d{2})?",
    )
private val DATE_DMY = Regex("(\\d{1,2})[/.](\\d{1,2})[/.](\\d{4}|\\d{2})")
private val DATE_YMD = Regex("(\\d{4})[-.](\\d{1,2})[-.](\\d{1,2})")

private typealias DateParser = (MatchResult, Calendar) -> Calendar?

/** Лояльный календарь как `new Date(y, mo, d)` — лишний день перекатывается на следующий месяц. */
private fun calOf(
    y: Int,
    mo: Int,
    d: Int,
): Calendar {
    val c = Calendar.getInstance(RU)
    c.isLenient = true
    c.clear()
    c.set(y, mo, d, 0, 0, 0)
    return c
}

private val DATE_PATTERNS: List<Pair<Regex, DateParser>> =
    listOf(
        DATE_TEXT_MONTH to
            { m: MatchResult, nowCal: Calendar ->
                val day = m.groupValues[1].toInt()
                val mo = MONTH_NAMES[m.groupValues[2].lowercase(RU)]
                if (mo == null) {
                    null
                } else {
                    var y = if (m.groupValues[3].isNotEmpty()) m.groupValues[3].toInt() else nowCal.get(Calendar.YEAR)
                    if (y < 100) y += 2000
                    calOf(y, mo, day)
                }
            },
        DATE_DMY to
            { m: MatchResult, _nowCal: Calendar ->
                val d = m.groupValues[1].toInt()
                val mo = m.groupValues[2].toInt() - 1
                var y = m.groupValues[3].toInt()
                if (y < 100) y += 2000
                calOf(y, mo, d)
            },
        DATE_YMD to
            { m: MatchResult, _nowCal: Calendar ->
                calOf(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
            },
    )

// === ВРЕМЯ СУТОК ===

private val WORD_HOURS =
    mapOf(
        "один" to 1, "одну" to 1, "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7,
        "восемь" to 8, "девять" to 9, "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12,
        "полдень" to 12, "полночь" to 0,
    )

/** «14:30», «в 14.30». */
private val TIME_COLON = Regex("(?:в\\s+)?(\\d{1,2})\\s*[:.]\\s*(\\d{2})(?!\\d)")

/** «в 10 часов», «в 10 часов 30 минут». */
private val TIME_HOURS = Regex("(?:в\\s*)?(\\d{1,2})\\s*час(?:ов|а)?(?:\\s*(\\d{1,2})\\s*минут(?:ы)?)?")

/** Голый час: «в 10», «к 10», «в десять». После числа не должно идти минут/штук/рублей и т.п. */
private val TIME_BARE =
    Regex(
        "(?<![а-яё])(?:в|к)\\s+(\\d{1,2}|полдень|полночь|один|одну|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать)" +
            "(?![\\dа-яё])(?!\\s*(?:минут|рубл|штук|процент|раз|кг|грамм|человек|лет|года|месяц|организац))",
    )

/** Уточнение после часа: «10 утра», «10 вечера», «2 ночи», «3 дня». */
private val DAY_PART_TAIL = Regex("^\\s*(утр[а-яё]*|дн[а-яё]*|обед[а-яё]*|вечер[а-яё]*|ноч[а-яё]*)(?![а-яё])")

/** Периоды без часа: берём время из настроек по умолчанию. */
private val PERIODS: List<Pair<Regex, String>> =
    listOf(
        Regex("(?<![а-яё])(утром|с утра|утра)(?![а-яё])") to "morning",
        Regex("(?<![а-яё])(до обеда|перед обедом)(?![а-яё])") to "beforeNoon",
        // «днём» — время суток, но ТОЛЬКО не в «с днём свадьбы / рождения»: иначе праздничная
        // фраза получала время 13:00 и уезжала на завтра.
        Regex(
            "(?<!с\\s)(?<![а-яё])(в обед|днём|днем|обед)(?![а-яё])" +
                "(?!\\s+(?:свадьбы|рождения|рожденья|победы|знаний|матери|отца))",
        ) to "noon",
        Regex("(?<![а-яё])(после обеда|послеобеденное)(?![а-яё])") to "afterNoon",
        Regex("(?<![а-яё])(вечером|под вечер|вечера)(?![а-яё])") to "evening",
        Regex("(?<![а-яё])(ночью|ночи)(?![а-яё])") to "night",
    )

/** Часть суток корректирует голый час: «10 вечера» → 22, но «9 утра» остаётся 9. */
private fun applyDayPart(
    hour: Int,
    word: String,
): Int =
    when {
        Regex("ноч").containsMatchIn(word) -> if (hour in 5..11) hour + 12 else hour
        Regex("вечер").containsMatchIn(word) -> if (hour < 12) hour + 12 else hour
        Regex("дн|обед").containsMatchIn(word) -> if (hour < 12) hour + 12 else hour
        else -> hour
    }

// === КАТЕГОРИИ ===

/** По слову-подсказке: «купить хлеб» → категория «Покупки», слово уходит из текста. */
private val CAT_SHOPPING =
    ci("(?<![а-яё])(?:купить|куплю|купи|покупать|покупаю|сходить\\s+в\\s+магазин|магазин)(?![а-яё])")

/** Конструкция «категория X» в конце фразы. */
private val CAT_WORD_TAIL = ci("\\s*категория\\s+([а-яёa-z]+)\\s*[.!?]?\\s*\$")

// === ПОСТ-ОЧИСТКА ТЕКСТА ===

private val HOUR_LEFTOVER = ci("(?<![а-яё])(?:часов|часа|час|ч\\.)(?![а-яё])")
private val DAYPART_LEFTOVER =
    ci("(?<![а-яё])(?:утра|утром|утре|вечера|вечером|вечере|дня|днём|днем|ночи|ночью)(?![а-яё])")
private val TRAILING_PREP = ci("\\s+(?:в|к|на|до|после|около)\$")
private val TRAILING_PUNCT = Regex("[\\s.,;:!?]+\$")

// === ВСПОМОГАТЕЛЬНОЕ ===

/** Вырезает найденный кусок из рабочей строки и схлопывает пробелы. */
private fun cutOut(
    working: String,
    start: Int,
    length: Int,
): String {
    val end = (start + length).coerceAtMost(working.length).coerceAtLeast(start)
    return (working.substring(0, start) + " " + working.substring(end)).replace(WS, " ").trim()
}

private fun dayShift(
    nowCal: Calendar,
    days: Int,
): Calendar {
    val d = nowCal.clone() as Calendar
    d.add(Calendar.DAY_OF_MONTH, days)
    return d
}

private fun sameDate(
    a: Calendar,
    b: Calendar,
): Boolean = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

// === ОСНОВНАЯ ФУНКЦИЯ ===

/**
 * Разбирает текст заметки: дата/время (относительное и абсолютное), категория словом,
 * а также чистит текст от распознанных кусков.
 *
 * @param rawText сырой текст (диктовка или ввод)
 * @param now текущий момент, мс (для тестов можно задать)
 * @param voiceCategory включать ли распознавание категории словом
 */
fun parseDateNlu(
    rawText: String,
    now: Long = System.currentTimeMillis(),
    voiceCategory: Boolean = true,
): ParsedNote {
    var working = normalizeOrdinalDates(cleanDictation(stripCommandWords(rawText)))
    var lower = working.lowercase(RU)
    val nowCal = Calendar.getInstance(RU).apply { timeInMillis = now }

    var result: Calendar? = null
    var timeResult = false
    var timeHours = 0
    var timeMinutes = 0
    var categoryId: String? = null
    var relativeApplied = false
    // Дата без названного часа = дело на весь день. Дефолтный час (12:00) НЕ подставляем:
    // иначе заметка к вечеру сама уезжает в «Просроченные».
    var allDay = false
    // Назван ли день словами («сегодня», «завтра», «4 ноября»): если да, день не переносим.
    var dateNamed = false

    // --- Категория словом (до разбора даты: слово-команда уходит из текста). ---
    if (voiceCategory) {
        val cm = CAT_SHOPPING.find(working)
        if (cm != null) {
            val rest = cutOut(working, cm.range.first, cm.value.length)
            // Категорию ставим только если в заметке остался смысл (иначе «купить» → пустая заметка).
            if (rest.isNotEmpty()) {
                categoryId = "shopping"
                working = rest
                lower = working.lowercase(RU)
            }
        }
        val catMatch = CAT_WORD_TAIL.find(working)
        if (catMatch != null) {
            val word = catMatch.groupValues[1].lowercase(RU)
            val found = DEFAULT_CATEGORIES.firstOrNull { it.second.lowercase(RU) == word || it.first == word }
            if (found != null) {
                categoryId = found.first
                working = working.substring(0, catMatch.range.first).trim()
                lower = working.lowercase(RU)
            }
        }
    }

    // 1. Относительное время: оно точное, дефолтные часы к нему не применяем.
    val rel = parseRelativeTime(lower)
    if (rel != null) {
        result = Calendar.getInstance(RU).apply { timeInMillis = now + rel.first }
        relativeApplied = true
        working = cutOut(working, lower.indexOf(rel.second), rel.second.length)
        lower = working.lowercase(RU)
    }

    // 2. Абсолютная дата.
    if (result == null) {
        for ((re, parse) in DATE_PATTERNS) {
            val m = re.find(lower) ?: continue
            val date = parse(m, nowCal) ?: continue
            result = date
            dateNamed = true
            working = cutOut(working, m.range.first, m.value.length)
            lower = working.lowercase(RU)
            break
        }
    }
    if (result == null) {
        if (lower.contains("послезавтра")) {
            result = dayShift(nowCal, 2)
            dateNamed = true
            working = cutOut(working, lower.indexOf("послезавтра"), 11)
        } else if (lower.contains("завтра")) {
            result = dayShift(nowCal, 1)
            dateNamed = true
            working = cutOut(working, lower.indexOf("завтра"), 6)
        } else if (lower.contains("сегодня")) {
            result = dayShift(nowCal, 0)
            dateNamed = true
            working = cutOut(working, lower.indexOf("сегодня"), 7)
        }
        lower = working.lowercase(RU)
    }
    // 2.4 «после выходных» — ближайший понедельник (если сегодня понедельник, то следующий);
    //     «в выходные»/«на выходных» — ближайшая суббота, а в сами выходные — сегодня.
    if (result == null && lower.contains("после выходных")) {
        val cd = weekdayIndex(nowCal)
        var diff = ((1 - cd) + 7) % 7 // до понедельника
        if (diff == 0) diff = 7 // сегодня понедельник — значит следующий
        result = dayShift(nowCal, diff)
        dateNamed = true
        working = cutOut(working, lower.indexOf("после выходных"), "после выходных".length)
        lower = working.lowercase(RU)
    }
    if (result == null && (lower.contains("в выходные") || lower.contains("на выходных"))) {
        val phrase = if (lower.contains("на выходных")) "на выходных" else "в выходные"
        val cd = weekdayIndex(nowCal)
        val diff = if (cd == 6 || cd == 7) 0 else ((6 - cd) + 7) % 7 // 6 — суббота
        result = dayShift(nowCal, diff)
        dateNamed = true
        working = cutOut(working, lower.indexOf(phrase), phrase.length)
        lower = working.lowercase(RU)
    }

    // 2.5 «в первую/вторую/третью/четвёртую/последнюю <день недели> [следующего] месяца».
    if (result == null) {
        val m = ORD_WEEKDAY_RE.find(lower)
        if (m != null) {
            val nth = ORDINAL_WEEKDAY_ORDER[m.groupValues[1]] ?: 1
            val dayIdx = WEEKDAY_INDEX[m.groupValues[2]] ?: -1
            if (dayIdx > 0) {
                val monthPart = m.groupValues[3]
                var offset = if (monthPart == "следующего") 1 else 0
                var date = nthWeekdayOfMonth(nowCal, offset, calendarDow(dayIdx), nth)
                // «в первую пятницу месяца», сказанное уже после неё, — значит следующего месяца.
                if (offset == 0 && date.timeInMillis < nowCal.timeInMillis) {
                    date = nthWeekdayOfMonth(nowCal, 1, calendarDow(dayIdx), nth)
                }
                result = date
                dateNamed = true
                working = cutOut(working, m.range.first, m.value.length)
                lower = working.lowercase(RU)
            }
        }
    }

    // 2.6 «в конце месяца» — последний день, а если он уже прошёл, то следующего месяца.
    if (result == null && lower.contains("в конце месяца")) {
        var date = endOfMonth(nowCal, 0)
        if (date.timeInMillis < nowCal.timeInMillis) date = endOfMonth(nowCal, 1)
        result = date
        dateNamed = true
        working = cutOut(working, lower.indexOf("в конце месяца"), "в конце месяца".length)
        lower = working.lowercase(RU)
    }

    if (result == null) {
        val wd = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
        val wg = listOf("понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье")
        for (i in wd.indices) {
            // «следующую среду» и «следующий понедельник» — обе формы.
            val re = Regex("(?:во?\\s*)?(следующ(?:ую|ий)\\s*)?(" + wd[i] + "|" + wg[i] + ")(?![а-яё])")
            val m = re.find(lower) ?: continue
            val td = i + 1 // понедельник = 1 … воскресенье = 7
            var cd = nowCal.get(Calendar.DAY_OF_WEEK) - 1
            if (cd == 0) cd = 7 // воскресенье в JS = 0
            var diff = td - cd
            if (diff < 0) diff += 7
            if (diff == 0) diff = 7
            if (m.groupValues[1].isNotEmpty()) diff += 7 // «следующую среду»
            result = dayShift(nowCal, diff)
            dateNamed = true
            working = cutOut(working, m.range.first, m.value.length)
            break
        }
        lower = working.lowercase(RU)
    }

    // 3. Время внутри суток (для относительного уже посчитано).
    if (!relativeApplied) {
        // 3.1 «14:30», «в 14.30»
        TIME_COLON.find(lower)?.let { m ->
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            if (h in 0..23 && mi in 0..59) {
                timeResult = true
                timeHours = h
                timeMinutes = mi
                working = cutOut(working, m.range.first, m.value.length)
                lower = working.lowercase(RU)
            }
        }
        // 3.2 «в 10 часов», «в 10 часов 30 минут»
        if (!timeResult) {
            TIME_HOURS.find(lower)?.let { m ->
                val h = m.groupValues[1].toInt()
                val mi = if (m.groupValues[2].isNotEmpty()) m.groupValues[2].toInt() else 0
                if (h in 0..23 && mi in 0..59) {
                    timeResult = true
                    timeHours = h
                    timeMinutes = mi
                    working = cutOut(working, m.range.first, m.value.length)
                    lower = working.lowercase(RU)
                }
            }
        }
        // 3.3 голый час: «в 10», «к 10», «в десять», «в 10 утра/дня/вечера/ночи»
        if (!timeResult) {
            TIME_BARE.find(lower)?.let { m ->
                val g1 = m.groupValues[1]
                if (Regex("\\d+").matches(g1) || WORD_HOURS.containsKey(g1)) {
                    var h = if (Regex("\\d+").matches(g1)) g1.toInt() else WORD_HOURS.getValue(g1)
                    val after = lower.substring(m.range.first + m.value.length)
                    val mod = DAY_PART_TAIL.find(after)
                    if (mod != null) {
                        h = applyDayPart(h, mod.groupValues[1])
                        working = cutOut(working, m.range.first, m.value.length + mod.value.length)
                    } else {
                        working = cutOut(working, m.range.first, m.value.length)
                    }
                    if (h in 0..23) {
                        timeResult = true
                        timeHours = h
                        timeMinutes = 0
                    }
                    lower = working.lowercase(RU)
                }
            }
        }
        // 3.4 периоды без часа
        if (!timeResult) {
            for ((re, key) in PERIODS) {
                val pm = re.find(lower) ?: continue
                val t = PERIOD_TIMES.getValue(key)
                timeResult = true
                timeHours = t.first
                timeMinutes = t.second
                working = cutOut(working, pm.range.first, pm.value.length)
                lower = working.lowercase(RU)
                break
            }
        }
    }

    // 4. Собираем результат.
    if (result != null && timeResult) {
        result.set(Calendar.HOUR_OF_DAY, timeHours)
        result.set(Calendar.MINUTE, timeMinutes)
        result.set(Calendar.SECOND, 0)
        result.set(Calendar.MILLISECOND, 0)
    } else if (timeResult && result == null) {
        val d = Calendar.getInstance(RU).apply { timeInMillis = now }
        d.set(Calendar.HOUR_OF_DAY, timeHours)
        d.set(Calendar.MINUTE, timeMinutes)
        d.set(Calendar.SECOND, 0)
        d.set(Calendar.MILLISECOND, 0)
        if (d.timeInMillis <= now) d.add(Calendar.DAY_OF_MONTH, 1) // названо только время, и оно прошло
        result = d
    } else if (result != null && !timeResult && !relativeApplied) {
        allDay = true
        result.set(Calendar.HOUR_OF_DAY, 0)
        result.set(Calendar.MINUTE, 0)
        result.set(Calendar.SECOND, 0)
        result.set(Calendar.MILLISECOND, 0)
    }
    // Переносим на завтра только «голое» время («в 18:00» без дня). Если день назван словами —
    // оставляем его: «сегодня вечером» должно остаться сегодняшним.
    if (result != null && timeResult && !dateNamed && result.timeInMillis <= now && sameDate(result, nowCal)) {
        val d = result.clone() as Calendar
        d.add(Calendar.DAY_OF_MONTH, 1)
        result = d
    }

    var cleanText = WS.replace(working, " ").trim()
    if (cleanText.isEmpty() && result != null) cleanText = "Напоминание"
    // Время разобрано из явного часа — значит оставшиеся «часов»/«утра»/«вечером» были
    // описанием этого часа, а не частью заметки (диктовка даёт любой порядок слов).
    if (timeResult) {
        cleanText = HOUR_LEFTOVER.replace(cleanText, " ")
        cleanText = DAYPART_LEFTOVER.replace(cleanText, " ")
    }
    cleanText = WS.replace(cleanText, " ").trim()
    // предлог, оставшийся без объекта («в», «к», «на») на конце — тоже мусор
    cleanText = TRAILING_PREP.replace(cleanText, "").trim()
    // GigaAM выдаёт текст с пунктуацией: хвостовая точка в самой заметке не нужна
    cleanText = TRAILING_PUNCT.replace(cleanText, "")
    cleanText = Regex("\\s{2,}").replace(cleanText, " ").trim()

    return ParsedNote(
        text = cleanText,
        datetime = result?.timeInMillis,
        allDay = allDay && result != null,
        categoryId = categoryId,
        dateNamed = dateNamed,
    )
}

// === САМОПРОВЕРКА ===


/** Номер дня недели по-нашему: понедельник = 1 … воскресенье = 7. */
private fun weekdayIndex(cal: Calendar): Int {
    val dow = cal.get(Calendar.DAY_OF_WEEK)
    return if (dow == Calendar.SUNDAY) 7 else dow - 1
}

/** То же в нумерации Calendar: понедельник = Calendar.MONDAY и так далее. */
private fun calendarDow(index: Int): Int = if (index == 7) Calendar.SUNDAY else index + 1

private val WEEKDAY_INDEX =
    mapOf(
        "понедельник" to 1,
        "вторник" to 2,
        "среда" to 3,
        "среду" to 3,
        "четверг" to 4,
        "пятница" to 5,
        "пятницу" to 5,
        "суббота" to 6,
        "субботу" to 6,
        "воскресенье" to 7,
    )

/** «первую … последнюю» — какой по счёту день недели берём в месяце. */
private val ORDINAL_WEEKDAY_ORDER =
    mapOf(
        "первую" to 1,
        "вторую" to 2,
        "третью" to 3,
        "четвёртую" to 4,
        "четвертую" to 4,
        "последнюю" to -1,
    )

private val ORD_WEEKDAY_RE =
    ci(
        "в\\s+(первую|вторую|третью|четвёртую|четвертую|последнюю)\\s+" +
            "(понедельник|вторник|среду|четверг|пятницу|субботу|воскресенье)" +
            "(?:\\s+(следующего|этого))?\\s+месяц[а-яё]*",
    )

/** nth-й день недели месяца (nth = -1 — последний). monthOffset: 0 — этот месяц, 1 — следующий.
 *  Возвращаем Calendar: в разборе результат хранится именно так (см. dayShift). */
private fun nthWeekdayOfMonth(
    base: Calendar,
    monthOffset: Int,
    dow: Int,
    nth: Int,
): Calendar {
    val c = base.clone() as Calendar
    c.clear()
    c.set(base.get(Calendar.YEAR), base.get(Calendar.MONTH) + monthOffset, 1, 0, 0, 0)
    if (nth > 0) {
        var day = 1 + ((dow - c.get(Calendar.DAY_OF_WEEK) + 7) % 7) + (nth - 1) * 7
        if (day > c.getActualMaximum(Calendar.DAY_OF_MONTH)) day -= 7
        c.set(Calendar.DAY_OF_MONTH, day)
    } else {
        c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH))
        c.add(Calendar.DAY_OF_MONTH, -(((c.get(Calendar.DAY_OF_WEEK) - dow) + 7) % 7))
    }
    return c
}

/** Последний день месяца: 0 — текущего, 1 — следующего. */
private fun endOfMonth(
    base: Calendar,
    monthOffset: Int,
): Calendar {
    val c = base.clone() as Calendar
    c.clear()
    c.set(base.get(Calendar.YEAR), base.get(Calendar.MONTH) + monthOffset, 1, 0, 0, 0)
    c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH))
    return c
}

private fun fmt(ms: Long): String {
    val c = Calendar.getInstance(RU).apply { timeInMillis = ms }
    fun p(v: Int) = if (v < 10) "0$v" else "$v"
    return "${c.get(Calendar.YEAR)}-${p(c.get(Calendar.MONTH) + 1)}-${p(c.get(Calendar.DAY_OF_MONTH))} " +
        "${p(c.get(Calendar.HOUR_OF_DAY))}:${p(c.get(Calendar.MINUTE))}"
}

/**
 * Печатает разбор набора фраз: распознанный момент (или «нет»), признак «весь день»,
 * категорию и оставшийся текст. Фиксированный опорный момент — чтобы вывод был воспроизводим.
 */
fun dateSelfTest(): String {
    val phrases =
        listOf(
            "завтра в 10:00 утра позвонить маме",
            "сегодня купить хлеб",
            "4 ноября сдать отчёт",
            "через 10 минут позвонить",
            "в 22:30 выключить компьютер",
            "послезавтра встреча в 9 утра",
            "позвонить маме",
            "купить молоко категория покупки",
            "8 марта в 18:00 поздравить маму",
            "в среду в 15:00 созвон",
            "через час позвонить папе",
            "1 января в 00:30 новый год",
            // дни недели и родственные конструкции (запрос владельца 07.11.2026)
            "в понедельник позвонить в банк",
            "в следующий понедельник сдать отчёт",
            "в следующую среду созвон",
            "после выходных разобрать почту",
            "в субботу уборка",
            "на выходных поехать на дачу",
            "в первую пятницу следующего месяца зарплата",
            "в последнюю пятницу месяца отчёт",
            "в конце месяца оплатить счета",
            // чистка диктовки не должна съедать смысл: «Это» и «Слушай» обязаны остаться
            "Это проверка. Слушай музыку",
            // а слова-паразиты из списка по-прежнему выкидываются: «короче говоря» → в тексте его нет
            "короче говоря напомни завтра купить хлеб",
            // дополнительные: ординалы, периоды без часа и слово-команда
            "четвёртого ноября сдать отчёт",
            "позвонить вечером",
            "напомни завтра купить хлеб",
        )
    // Суббота 7 ноября 2026, 14:20.
    val ref =
        Calendar.getInstance(RU).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 7, 14, 20, 0)
        }.timeInMillis

    val sb = StringBuilder()
    sb.append("Проверка разбора даты/времени, опорный момент: ").append(fmt(ref)).append('\n')
    for (p in phrases) {
        val r = parseDateNlu(p, ref, true)
        sb.append("— «").append(p).append("»\n")
        sb.append("    дата: ").append(r.datetime?.let { fmt(it) } ?: "нет")
        sb.append(" | весь день: ").append(if (r.allDay) "да" else "нет")
        sb.append(" | категория: ").append(r.categoryId ?: "нет")
        sb.append(" | день назван: ").append(if (r.dateNamed) "да" else "нет")
        sb.append(" | текст: «").append(r.text).append("»\n")
    }
    return sb.toString().trimEnd()
}
