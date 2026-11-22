package com.glassnotes.dev

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Контакты для заметок (#84).
 *
 * Что делает:
 *  - находит в тексте заметки фамилию/имя из телефонной книги;
 *  - отдаёт контакт, который показывается кружком в углу карточки (тап — набрать номер).
 *
 * Поиск нарочно грубый и предсказуемый: сравниваем корни слов, отрезая одну последнюю букву
 * падежного окончания. Поэтому «Иванов», «Иванову», «Ивановым», «Иванова» — один контакт.
 * Ложные срабатывания возможны («Иваново»), но лучше так, чем требовать точного имени.
 */
object Contacts {
    /** Контакт в том виде, в каком он нужен заметке. */
    data class Person(
        val id: Long,
        val name: String,
        val number: String,
    )

    /** Слова, после которых контакт прикрепляется сразу, без вопроса. */
    /**
     * Контрольные слова «эта заметка про человека»: по ним контакт предлагается (а при
     * единственном кандидате крепится молча). Набор — глаголы обращения к человеку:
     * связаться любым способом, передать что-то, предупредить. Слова без «звонка»
     * добавлены по просьбе владельца 22.11 — раньше «написать Иванову» контакт не цепляло.
     */
    private val CALL_WORDS =
        listOf(
            // звонок
            "позвонить", "позвони", "позвоню", "перезвонить", "перезвони", "набрать", "набери",
            "дозвониться", "звонок", "звонить", "звони",
            // сообщение
            "написать", "напиши", "напишу", "написать сообщение", "сообщение", "смс", "эсэмэс",
            "телеграм", "ватсап", "вотсап", "whatsapp", "telegram", "отправить", "отправь",
            "скинуть", "скинь", "переслать", "перешли",
            // сообщить
            "сообщить", "сообщи", "рассказать", "расскажи", "передать", "передай", "дать знать",
            "известить", "уведомить", "информировать", "предупредить", "предупреди",
            // связаться и разговор
            "связаться", "свяжись", "созвониться", "созвонимся", "уточнить у", "уточни у",
            "спросить у", "спроси у", "обсудить с", "договориться с", "встретиться с",
            "пригласить", "поздравить",
        )

    /** Редукция безударных: в ключе остаётся класс гласной, а не сама буква. */
    private val VOWEL_CLASS =
        mapOf(
            'а' to 'а', 'о' to 'а', 'я' to 'а',
            'е' to 'е', 'и' to 'е', 'ё' to 'е', 'э' to 'е',
            'у' to 'у', 'ю' to 'у',
            'ы' to 'и', 'й' to 'и',
        )

    /** Оглушение парных согласных — ASR их путает чаще всего. */
    private val DEAFEN =
        mapOf('б' to 'п', 'в' to 'ф', 'г' to 'к', 'д' to 'т', 'ж' to 'ш', 'з' to 'с')

    private var cache: List<Person>? = null
    private var cachedAt = 0L
    private const val CACHE_MS = 5 * 60 * 1000L

    fun hasAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED

    /** Сбросить кэш: контакты могли измениться, пока приложение было в фоне. */
    fun invalidate() {
        cache = null
        cachedAt = 0L
    }

    /** «Позвонить» и родственные слова — по ним контакт крепится молча. */
    fun wantsCall(text: String): Boolean {
        val lower = text.lowercase(Locale("ru"))
        return CALL_WORDS.any { lower.contains(it) }
    }

    /** Все контакты с номером. Кэш на 5 минут — читать телефонную книгу на каждую заметку дорого. */
    fun people(context: Context): List<Person> {
        val now = System.currentTimeMillis()
        cache?.takeIf { now - cachedAt < CACHE_MS }?.let { return it }
        if (!hasAccess(context)) return emptyList()

        val projection =
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
        val out = LinkedHashMap<Long, Person>()
        runCatching {
            context.contentResolver
                .query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1)?.trim().orEmpty()
                        val number = cursor.getString(2)?.trim().orEmpty()
                        if (id <= 0L || name.isEmpty() || number.isEmpty()) continue
                        // Первый номер на контакт: список отсортирован по имени, этого достаточно.
                        if (!out.containsKey(id)) out[id] = Person(id, name, number)
                    }
                }
        }
        val list = out.values.toList()
        cache = list
        cachedAt = now
        return list
    }

    /**
     * Найти в тексте упоминание контакта. Возвращает самое длинное совпадение (или null).
     * Оставлено для редактора, где нужен один контакт.
     */
    fun findMention(
        context: Context,
        text: String,
    ): Person? = findMentions(context, text).firstOrNull()?.person

    /**
     * Все контакты, чья фамилия (корень слова) есть в тексте.
     *
     * Если фамилия одна, а людей несколько («Шиденко» — три человека в книге), сужаем по имени
     * и отчеству: считаем, сколько ещё слов из имени встречается в тексте, и оставляем тех,
     * у кого совпало больше. Окончательный выбор остаётся за человеком — окно показывает список.
     */
    /**
     * Скелет слова: только согласные, парные оглушены, удвоения схлопнуты.
     * «Павлу» и «Павел» → «пвл», «Ольге» и «Ольга» → «лк», а «позвонить» → «псв» —
     * поэтому имя в падеже находится, а «поздняков» к «позвонить» не цепляется.
     */
    /**
     * Имя в падеже: «Павлу» ↔ «Павел», «Саше» ↔ «Саша». Совпадают первые три буквы,
     * и слово не длиннее имени больше чем на падежное окончание — иначе «Кари» цеплялось
     * за «карточке», а «Катя» за «кататься».
     */
    private fun nameLike(
        word: String,
        name: String,
    ): Boolean {
        val w = normalize(word)
        val n = normalize(name)
        if (w.length < 3 || n.length < 3) return false
        val k = minOf(3, n.length - 1)
        return w.startsWith(n.take(k)) && kotlin.math.abs(w.length - n.length) <= 3
    }

    private fun skeleton(word: String): String {
        val sb = StringBuilder()
        for (ch in normalize(word)) {
            if (ch == 'ь' || ch == 'ъ') continue
            if (ch !in 'а'..'я' && ch !in 'a'..'z') continue
            // Первую гласную оставляем: без неё «Иванов» и «Ефанова» дают один скелет «фнф».
            if (ch in "аеёиоуыэюя") {
                if (sb.isEmpty()) sb.append(ch)
                continue
            }
            val deg = DEAFEN[ch] ?: ch
            if (sb.isNotEmpty() && sb.last() == deg) continue
            sb.append(deg)
        }
        return sb.toString()
    }

    /**
     * Слово текста и часть имени совпали по скелету согласных (падежи не мешают).
     *
     * Только ПОЛНОЕ равенство и только для скелета от 4 знаков. Прежнее «начало скелета
     * плюс 2 знака» тянуло случайных людей на обычных словах: «купить» → «кпт» = «ГИБДД»,
     * «программы» → «пркрм» ≈ «Пирог» → «прк». Из-за этого окно контакта всплывало на
     * каждой второй заметке (заметки #168/#169).
     */
    private fun skeletonClose(
        word: String,
        part: String,
    ): Boolean {
        val sw = skeleton(word)
        val sp = skeleton(part)
        if (sw.length < 4 || sp.length < 4) return false
        return sw == sp
    }

    /** Нормализация для поиска в списке контактов (нижний регистр, ё→е). */
    fun normalizeText(value: String): String = normalize(value)

    /** Части имени без скобок, дефисов и прочей пунктуации («Черная (Шиденко)» → два слова). */
    private fun nameParts(name: String): List<String> =
        normalize(name).split(Regex("[^а-я]+")).filter { it.length >= 4 }

    /** Найденный контакт вместе со словом, по которому он совпал (по нему запоминаем выбор). */
    data class Match(
        val person: Person,
        val word: String,
    )

    /** Ключ памяти: какая фамилия → кого выбрали в прошлый раз. */
    private fun pickKey(word: String): String = "contact_pick:" + normalize(word).dropLast(1)

    /** Сохранённый ранее выбор для этой фамилии (или null). */
    fun remembered(
        context: Context,
        word: String,
    ): Long? = NotesStore(context.applicationContext).getKv(pickKey(word))?.toLongOrNull()

    /** Запомнить выбор: в следующий раз этот человек будет первым и закрепится без вопроса. */
    fun rememberPick(
        context: Context,
        word: String,
        id: Long,
        name: String,
    ) {
        NotesStore(context.applicationContext).setKv(pickKey(word), id.toString())
        android.util.Log.i("GlasNotesDev", "запомнен выбор контакта для «%s»: %s (#%d)".format(word, name, id))
    }

    /**
     * Уменьшительные имена → полная форма (#144). Без этого «позвонить Тане» не находило контакт
     * «Танюшка»: «тане» и «танюшка» не совпадают ни по корню, ни по скелету согласных,
     * и единственным кандидатом оставался чужой контакт.
     *
     * Основы от длинных к коротким: срабатывает первая подходящая, поэтому «танюш» проверяется
     * раньше «тан». Короткие основы («кол» → Николай, «пет» → Пётр, «вит» → Виктор) сознательно
     * не добавлены: они ловят «коллега», «петрушка», «витамин».
     */
    private val DIMINUTIVES: List<Pair<String, String>> =
        listOf(
            "александр" to "александр", "татьян" to "татьяна", "танюш" to "татьяна", "тан" to "татьяна",
            "саш" to "александр", "алекс" to "александр",
            "павел" to "павел", "паш" to "павел",
            "михаил" to "михаил", "миш" to "михаил",
            "натал" to "наталья", "наташ" to "наталья",
            "дмитр" to "дмитрий", "дим" to "дмитрий",
            "екатерин" to "екатерина", "катерин" to "екатерина",
            "анастас" to "анастасия", "настас" to "анастасия",
            "людмил" to "людмила", "светлан" to "светлана",
            "владимир" to "владимир", "евген" to "евгений", "жен" to "евгений",
            "сергей" to "сергей", "серег" to "сергей", "серёж" to "сергей",
            "васил" to "василий", "никол" to "николай", "роман" to "роман",
            "алексе" to "алексей", "лёш" to "алексей", "леш" to "алексей",
            "ольг" to "ольга", "оля" to "ольга", "ирин" to "ирина", "ира" to "ирина",
            "юли" to "юлия", "юля" to "юлия", "мария" to "мария", "марь" to "мария", "маш" to "мария",
            "софия" to "софия", "соф" to "софия", "дарья" to "дария", "даш" to "дария",
            "георг" to "георгий", "гош" to "георгий", "борис" to "борис", "бор" to "борис",
            "иван" to "иван", "вань" to "иван", "анна" to "анна", "анн" to "анна", "аня" to "анна",
            "елен" to "елена", "лена" to "елена",
        ).sortedByDescending { it.first.length }

    /** Полная форма имени по уменьшительной основе; null, если слово не похоже на имя. */
    private fun canonical(word: String): String? {
        val w = normalize(word)
        if (w.length < 3) return null
        return DIMINUTIVES.firstOrNull { w.startsWith(it.first) }?.second
    }

    /** Кандидат вместе с позицией первого совпадения в тексте (#144). */
    private data class Hit(
        val person: Person,
        val word: String,
        val score: Int,
        val pos: Int,
    )

    fun findMentions(
        context: Context,
        text: String,
    ): List<Match> {
        if (text.isBlank() || !hasAccess(context)) return emptyList()
        val haystack = normalize(text)
        val wordList = haystack.split(Regex("[^а-я0-9]+")).filter { it.length >= 3 }

        // Собираем всех, чьи слова есть в тексте. Фамилия весит много: заметка «позвонить
        // Шиденко» должна тянуть Шиденко, а не человека с именем Олег.
        val hits = ArrayList<Hit>()
        for (person in people(context)) {
            val parts = nameParts(person.name)
            if (parts.isEmpty()) continue
            var score = 0
            var pos = Int.MAX_VALUE
            for ((index, part) in parts.withIndex()) {
                // «Иванов» → «Ивано»: падежные окончания больше не мешают.
                val stem = part.dropLast(1)
                if (stem.length < 3) continue
                // Стем от 4 знаков: иначе «Кари» (стем «кар») ловилась словом «карточке».
                val stemLong = stem.length >= 4
                // Совпало либо слово в косвенном падеже («шиденко»), либо начало слова
                // («Павлу» ↔ «Павел»: три первые буквы).
                val byStem = stemLong && containsWord(haystack, stem) && haystack.indexOf(stem) >= 0
                // Имя в падеже: «Павлу» ↔ «Павел», «Саше» ↔ «Саша» — первые три буквы и разница
                // в длине не больше падежного окончания. Скелет как самостоятельный критерий
                // больше не нужен: он давал «купить» = «ГИБДД».
                val byName =
                    if (index == 0) {
                        wordList.firstOrNull { w -> nameLike(w, part) }?.let { w -> haystack.indexOf(w) } ?: -1
                    } else {
                        -1
                    }
                // #144: «Тане» ↔ контакт «Танюшка» — через полную форму имени. Только для имени:
                // уменьшительных фамилий не бывает.
                val aliasPos =
                    if (index == 0) {
                        val full = canonical(part)
                        if (full == null) {
                            -1
                        } else {
                            wordList.firstOrNull { w -> canonical(w) == full }
                                ?.let { w -> haystack.indexOf(w) } ?: -1
                        }
                    } else {
                        -1
                    }
                if (!byStem && byName < 0 && aliasPos < 0) continue
                score +=
                    when {
                        index == parts.lastIndex -> 10 // фамилия
                        // Имя, найденное через уменьшительную форму («Танюшка» по слову «Тане»),
                        // весит чуть больше полной формы: иначе «Саша» тянет за собой ещё
                        // и «Александра», и в окне выбора появляется лишний человек.
                        index == 0 && aliasPos >= 0 && canonical(part) != normalize(part) -> 5
                        index == 0 -> 4 // имя
                        else -> 2 // отчество и прочее
                    }
                // #144: где контакт упомянут. «позвонить Тане по поводу математики Саши» —
                // Таня раньше Саши, значит и в списке она первая.
                if (byStem) pos = minOf(pos, haystack.indexOf(stem))
                if (byName >= 0) pos = minOf(pos, byName)
                if (aliasPos >= 0) pos = minOf(pos, aliasPos)
            }
            if (score > 0) hits.add(Hit(person, parts.last(), score, pos))
        }
        if (hits.isEmpty()) return emptyList()
        val best = hits.maxOf { it.score }
        // #144: при равных очках берём только тех, кто упомянут в САМОМ РАННЕМ месте текста.
        // «позвонить Тане по поводу математики Саши»: Танюшка (позиция «Тане») и все однословные
        // Александры имели одинаковый вес 10, и в список попадали чужие люди. Тёзки, упомянутые
        // одним и тем же словом («позвонить Шиденко»), остаются все — им позиция одинаковая.
        val bestPos = hits.filter { it.score == best }.minOf { it.pos }
        val top = hits.filter { it.score == best && it.pos == bestPos }.distinctBy { it.person.id }
        val word = top.first().word

        // Универсальный порядок: сначала тот, кого выбирали в прошлый раз для этой фамилии,
        // дальше — по месту в тексте, затем по алфавиту.
        val rememberedId = remembered(context, word)
        return top
            .sortedWith(
                compareBy(
                    { if (it.person.id == rememberedId) 0 else 1 },
                    { it.pos },
                    { it.person.name },
                ),
            )
            .take(8)
            .map { Match(it.person, word) }
    }

    /** Диагностика: все, кто набрал очки по тексту, с расшифровкой (только для тестовых запусков). */
    fun debugScores(
        context: Context,
        text: String,
    ): String {
        if (!hasAccess(context)) return "нет доступа"
        val haystack = normalize(text)
        val wordList = haystack.split(Regex("[^а-я0-9]+")).filter { it.length >= 3 }
        val out = StringBuilder()
        for (person in people(context)) {
            val parts = nameParts(person.name)
            if (parts.isEmpty()) continue
            var score = 0
            val why = StringBuilder()
            for ((index, part) in parts.withIndex()) {
                val stem = part.dropLast(1)
                if (stem.length < 3) continue
                val byStem = containsWord(haystack, stem)
                val word = wordList.firstOrNull { w -> skeletonClose(w, part) }
                if (!byStem && word == null) continue
                val w =
                    when {
                        index == parts.lastIndex -> 10
                        index == 0 -> 4
                        else -> 2
                    }
                score += w
                why.append(" ").append(part).append(":").append(w)
                if (word != null) why.append("~").append(word).append("→").append(skeleton(word))
            }
            if (score > 0) out.append("\n  ").append(person.name).append(" = ").append(score).append(" [").append(why).append(" ]")
        }
        return out.toString()
    }

    /** Итог автоподстановки: исправленный текст и список замен для тоста/лога. */
    data class Correction(
        val text: String,
        val fixes: List<String>,
    )

    /** Фамилия (последнее слово имени): «Таня Иванова» → «Иванова», «Сидоров А. С.» → «Сидоров». */
    fun surname(person: Person): String =
        person.name.split(' ', '-').lastOrNull { it.length > 3 } ?: person.name

    /**
     * Автоподстановка фамилий (#148): ASR слышит «шеденко», а в книге «Шиденко».
     *
     * Правила нарочно узкие, чтобы не портить обычные слова: слово не короче 5 букв,
     * первые две буквы совпадают, разница не больше 1 буквы до 7 букв и 2 — от 8.
     * Совпавшие точно слова не трогаем. Возвращает текст и журнал «было → стало».
     */
    fun correct(
        context: Context,
        text: String,
    ): Correction {
        if (text.isBlank() || !hasAccess(context)) return Correction(text, emptyList())
        // Словарь телефонной книги: нормализованное слово → как оно записано в контакте.
        // Звуковой ключ считаем ОДИН раз на слово: иначе на 390 контактах прогон заметки
        // повисает в главном потоке (проверено 15.11.2026 — приложение зависло на fix_test).
        val vocab = LinkedHashMap<String, String>()
        for (person in people(context)) {
            for (part in person.name.split(' ', '.', '-')) {
                if (part.length < 5) continue
                val key = normalize(part)
                if (!vocab.containsKey(key)) vocab[key] = part
            }
        }
        if (vocab.isEmpty()) return Correction(text, emptyList())
        val entries = vocab.map { (key, written) -> Entry(key, phonetic(key), written) }

        val out = StringBuilder(text.length)
        val fixes = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (!ch.isLetter()) {
                out.append(ch)
                i++
                continue
            }
            var j = i
            while (j < text.length && text[j].isLetter()) j++
            val word = text.substring(i, j)
            val fixed = matchWord(word, entries)
            if (fixed != null && fixed != word) {
                out.append(fixed)
                fixes.add("$word → $fixed")
            } else {
                out.append(word)
            }
            i = j
        }
        return Correction(out.toString(), fixes)
    }

    /**
     * Ближайшее слово из книги по звучанию (#148).
     *
     * Русский «метафон» для фамилий: ё→е, ь/ъ выкидываем, гласные сводим к редукции
     * (а/о/я → «а», е/и/э → «е», у/ю → «у», ы/й → «и»), звонкие оглушаем (б→п, в→ф, г→к, д→т,
     * ж→ш, з→с), двойные схлопываем. ASR путает парные согласные и безударные гласные —
     * такой ключ эти ошибки гасит.
     *
     * Дополнительно: ASR иногда приклеивает слог слева («пляшаденко», «зашеденко»), поэтому
     * пробуем отрезать 0…3 первых буквы. Сравнение идёт по ключам; ключи могут отличаться
     * одной безударной гласной а↔е не в начале и не в конце (это редукция, а не другое имя).
     *
     * Замер на реальной книге владельца (390 контактов, порча по одной букве/слогу) —
     * 96% попаданий, 0 ложных на списке частых рабочих слов.
     * Падежные формы не ломаем: «иванову», «кузнецову» остаются как в тексте, потому что
     * у них другая последняя буква.
     */
    /** Слово из книги со заранее посчитанным звуковым ключом. */
    private class Entry(
        val word: String,
        val key: String,
        val written: String,
    )

    private fun matchWord(
        word: String,
        entries: List<Entry>,
    ): String? {
        val lower = normalize(word)
        if (lower.length < 5) return null
        for (shift in 0..3) {
            val core = lower.substring(shift)
            if (core.length < 5) continue
            // Точно совпало: либо написано верно (не трогаем), либо ASR приклеил слог слева —
            // тогда возвращаем написание из книги.
            entries.firstOrNull { it.word == core }?.let { return if (shift == 0) null else it.written }
            val coreKey = phonetic(core)
            for (entry in entries) {
                if (entry.word.length != core.length) continue
                if (!alike(core.first(), entry.word.first()) || !alike(core.last(), entry.word.last())) continue
                if (keysClose(coreKey, entry.key)) return entry.written
            }
        }
        return null
    }

    /** Звуковой ключ слова: редукция гласных + оглушение парных согласных + схлопывание. */
    private fun phonetic(value: String): String {
        val out = StringBuilder(value.length)
        for (ch in normalize(value)) {
            if (ch == 'ь' || ch == 'ъ') continue
            val reduced = VOWEL_CLASS[ch] ?: ch
            val c = DEAFEN[reduced] ?: reduced
            if (out.isEmpty() || out[out.length - 1] != c) out.append(c)
        }
        return out.toString()
    }

    /** Буквы звучат одинаково: совпадают или это пара звонкая/глухая. */
    private fun alike(
        a: Char,
        b: Char,
    ): Boolean = a == b || DEAFEN[a] == b || DEAFEN[b] == a

    /** Ключи отличаются одной безударной гласной а↔е — это редукция, а не другое имя. */
    private fun keysClose(
        a: String,
        b: String,
    ): Boolean {
        if (a == b) return true
        if (a.length != b.length) return false
        var differ = -1
        for (idx in a.indices) {
            if (a[idx] == b[idx]) continue
            if (differ >= 0) return false
            differ = idx
        }
        if (differ <= 0 || differ >= a.length - 1) return false
        return (a[differ] == 'а' && b[differ] == 'е') || (a[differ] == 'е' && b[differ] == 'а')
    }

    /** Системный выбор телефона из книги контактов (в редакторе заметки). */
    fun pickIntent(): android.content.Intent =
        android.content.Intent(
            android.content.Intent.ACTION_PICK,
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        )

    /** Разобрать результат выбора: id, имя, номер. */
    fun fromPicker(
        context: Context,
        data: android.content.Intent?,
    ): NoteContact? {
        val uri = data?.data ?: return null
        val projection =
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    NoteContact(
                        c.getLong(0),
                        c.getString(1) ?: "",
                        c.getString(2) ?: "",
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("GlasNotesDev", "выбор контакта из книги не удался: " + e.message)
            null
        }
    }

    /** Открыть клавиатуру набора с номером — звонок человек подтверждает сам. */
    fun dial(
        context: Context,
        number: String,
    ) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' }))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun normalize(value: String): String = value.lowercase(Locale("ru")).replace('ё', 'е')

    /** Вхождение корня как начала слова: «ивано» в «иваново» не считается началом слова? Считается — но с проверкой слева. */
    private fun containsWord(
        haystack: String,
        stem: String,
    ): Boolean {
        var from = 0
        while (true) {
            val index = haystack.indexOf(stem, from)
            if (index < 0) return false
            val before = if (index == 0) null else haystack[index - 1]
            if (before == null || !before.isLetter()) return true
            from = index + 1
        }
    }
}
