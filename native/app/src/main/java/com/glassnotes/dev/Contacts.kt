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
    private val CALL_WORDS =
        listOf("позвонить", "позвони", "перезвонить", "набрать", "дозвониться", "звонок", "звонить")

    private val VOWELS = "аеёиоуыэюяй".toSet()

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

    /** Найти в тексте упоминание контакта. Возвращает самое длинное совпадение (или null). */
    fun findMention(
        context: Context,
        text: String,
    ): Person? {
        if (text.isBlank() || !hasAccess(context)) return null
        val haystack = normalize(text)
        var best: Person? = null
        var bestLen = 0
        for (person in people(context)) {
            for (part in normalize(person.name).split(' ', '.', '-')) {
                if (part.length < 4) continue
                // «Иванов» → «Ивано»: падежные окончания больше не мешают.
                val stem = part.dropLast(1)
                if (stem.length < 3 || stem.length <= bestLen) continue
                if (containsWord(haystack, stem)) {
                    best = person
                    bestLen = stem.length
                }
            }
        }
        return best
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
        val vocab = LinkedHashMap<String, String>()
        for (person in people(context)) {
            for (part in person.name.split(' ', '.', '-')) {
                if (part.length < 5) continue
                val key = normalize(part)
                if (!vocab.containsKey(key)) vocab[key] = part
            }
        }
        if (vocab.isEmpty()) return Correction(text, emptyList())

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
            val fixed = matchWord(word, vocab)
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
     * Ближайшее слово из книги. Правила узкие — проверены на прототипе 15.11.2026:
     *  - длина та же, первая буква и последние две совпадают (значит ошибка не в окончании);
     *  - отличается ровно одна буква, и обе — гласные.
     * Тогда «шеденко» → «Шиденко», «пляшаденко» → «Пляшеденко», а падежные формы
     * («иванову», «кузнецову») и обычные слова («молоко», «поликлиника») остаются как есть.
     */
    private fun matchWord(
        word: String,
        vocab: Map<String, String>,
    ): String? {
        val lower = normalize(word)
        if (lower.length < 5) return null
        if (vocab.containsKey(lower)) return null // написано верно — не трогаем
        for ((key, written) in vocab) {
            if (key.length != lower.length) continue
            if (key[0] != lower[0] || key.takeLast(2) != lower.takeLast(2)) continue
            var diffs = 0
            var ok = true
            for (idx in lower.indices) {
                val a = lower[idx]
                val b = key[idx]
                if (a == b) continue
                diffs++
                if (diffs > 1 || a !in VOWELS || b !in VOWELS) {
                    ok = false
                    break
                }
            }
            if (ok && diffs == 1) return written
        }
        return null
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
