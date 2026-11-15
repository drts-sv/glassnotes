package com.glassnotes.dev

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Заметки в базе приложения — та же схема, что в боевой сборке (`StoreBridge.java`):
 *
 *   CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, json TEXT NOT NULL, ts INTEGER NOT NULL)
 *   CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)
 *
 * Файл базы тот же — `glassnotes.db`. Это не случайность: на этапе 5 пакет станет боевым
 * (`com.glassnotes.app`), и заметки переедут без конвертации. В dev-сборке база своя, потому что
 * каталоги данных у разных пакетов раздельные — писать в базу боевого приложения нативному
 * прототипу нельзя.
 *
 * Поля заметки повторяют то, что пишет JS боевого приложения: text, created (ISO), timestamp,
 * time (ЧЧ:ММ), status, categoryIds, color, notified.
 */
class NotesStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, json TEXT NOT NULL, ts INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        // Схема совпадает с боевой сборкой, поэтому обновлять пока нечего.
    }

    /** Сохранить заметку: текстом и категорией, как это делает приложение. */
    fun add(
        text: String,
        categoryId: String? = null,
        datetime: Long? = null,
        allDay: Boolean = false,
        contact: NoteContact? = null,
    ): Long {
        val now = System.currentTimeMillis()
        val created = ISO.format(Date(now))
        // Время в базе — это время НАПОМИНАНИЯ, а не создания: боевое приложение хранит так же.
        // Нет разобранной даты — поля пустые, а timestamp остаётся моментом создания записи.
        val due = datetime ?: now
        val note =
            JSONObject().apply {
                put("text", text)
                put("created", created)
                put("timestamp", due)
                put("time", if (datetime != null) CLOCK.format(Date(due)) else "")
                put("datetime", datetime?.let { ISO.format(Date(it)) } ?: JSONObject.NULL)
                put("allDay", allDay)
                put("status", "active")
                put("color", "color-1")
                put("categoryIds", JSONArray().apply { categoryId?.let { put(it) } })
                put("notified", false)
                contact?.let { put("contact", contactJson(it)) }
            }

        val values =
            ContentValues().apply {
                put("json", note.toString())
                put("ts", now)
            }

        val id = writableDatabase.insert("notes", null, values)
        if (id > 0) {
            // Дописываем id в сам JSON: список берёт по нему ключ, а боевое приложение хранит id
            // внутри заметки точно так же.
            note.put("id", id)
            val fix = ContentValues().apply { put("json", note.toString()) }
            writableDatabase.update("notes", fix, "id = ?", arrayOf(id.toString()))
        }
        return id
    }

    /** Пометить выполненной или вернуть в работу. */
    fun setStatus(
        id: Long,
        status: String,
    ) {
        patch(id) { it.put("status", status) }
    }

    /** Изменить текст заметки. */
    fun setText(
        id: Long,
        text: String,
    ) {
        patch(id) { it.put("text", text) }
    }

    /** Заменить список категорий заметки (в редакторе можно выбрать несколько). */
    fun setCategories(
        id: Long,
        categoryIds: List<String>,
    ) {
        patch(id) { json ->
            val arr = org.json.JSONArray()
            categoryIds.forEach { arr.put(it) }
            json.put("categoryIds", arr)
            json.put("color", categoryIds.firstOrNull() ?: "")
        }
    }

    /** Прикрепить контакт к заметке или снять его (null). */
    fun setContact(
        id: Long,
        contact: NoteContact?,
    ) {
        patch(id) { json ->
            if (contact == null) json.remove("contact") else json.put("contact", contactJson(contact))
        }
    }

    private fun contactJson(contact: NoteContact): JSONObject =
        JSONObject().apply {
            put("id", contact.id)
            put("name", contact.name)
            put("number", contact.number)
        }

    /** Сменить дату и время заметки (null — «без даты»). */
    fun setDatetime(
        id: Long,
        datetime: Long?,
        allDay: Boolean,
    ) {
        patch(id) { json ->
            if (datetime == null) {
                json.put("datetime", org.json.JSONObject.NULL)
                json.put("allDay", false)
            } else {
                // Формат как у создания заметки и у боевого приложения: ISO-строка, а не число.
                json.put("datetime", ISO.format(Date(datetime)))
                json.put("allDay", allDay)
                json.put("time", java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(datetime)))
            }
        }
    }

    /** Сменить категорию заметки (цвет карточки меняется сразу). */
    fun setCategory(
        id: Long,
        categoryId: String?,
    ) {
        patch(id) { note ->
            note.put("categoryIds", JSONArray().apply { categoryId?.let { put(it) } })
        }
    }

    /**
     * Все заметки как сырые строки (id, json, ts) — для резервной копии. Порядок по id,
     * чтобы файл был предсказуемым и сравнимым между выгрузками.
     */
    fun allRows(): List<Triple<Long, String, Long>> =
        readableDatabase
            .rawQuery("SELECT id, json, ts FROM notes ORDER BY id", null)
            .use { cursor ->
                val out = ArrayList<Triple<Long, String, Long>>(cursor.count)
                while (cursor.moveToNext()) {
                    out.add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getLong(2)))
                }
                out
            }

    fun remove(id: Long) {
        writableDatabase.delete("notes", "id = ?", arrayOf(id.toString()))
    }

    /**
     * Сырой JSON заметки и её метка времени — нужны, чтобы удаление можно было отменить
     * (плашка «Отменить»): вернуть строку на то же место в списке.
     */
    fun rawRow(id: Long): Pair<String, Long>? =
        readableDatabase
            .rawQuery("SELECT json, ts FROM notes WHERE id = ?", arrayOf(id.toString()))
            .use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
            }

    /** Вернуть удалённую заметку на место: тот же id, тот же JSON, тот же ts. */
    fun restore(
        id: Long,
        json: String,
        ts: Long,
    ) {
        val values =
            ContentValues().apply {
                put("id", id)
                put("json", json)
                put("ts", ts)
            }
        writableDatabase.insertWithOnConflict("notes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Удалить выполненные заметки, которые не менялись дольше [days] дней (заметка #68:
     * копятся сотни выполненных, список и бейдж от них пухнут). 0 — не удалять вовсе.
     * Возвращает число удалённых.
     */
    fun purgeDoneOlderThan(days: Int): Int {
        if (days <= 0) return 0
        val border = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        val ids = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT id, json, ts FROM notes WHERE ts < ?", arrayOf(border.toString())).use { c ->
            while (c.moveToNext()) {
                val status = runCatching { JSONObject(c.getString(1)).optString("status") }.getOrDefault("")
                if (status == "done") ids.add(c.getLong(0))
            }
        }
        if (ids.isEmpty()) return 0
        val marks = ids.joinToString(",") { "?" }
        return writableDatabase.delete("notes", "id IN ($marks)", ids.map { it.toString() }.toTypedArray())
    }

    /** Правка заметки: читаем JSON, меняем, пишем обратно — как это делает боевое приложение. */
    private fun patch(
        id: Long,
        change: (JSONObject) -> Unit,
    ) {
        val json =
            readableDatabase
                .rawQuery("SELECT json FROM notes WHERE id = ?", arrayOf(id.toString()))
                .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return
        val note = JSONObject(json)
        change(note)
        val values = ContentValues().apply { put("json", note.toString()) }
        writableDatabase.update("notes", values, "id = ?", arrayOf(id.toString()))
    }

    /**
     * Активные заметки с датой — по ним ставим будильники и восстанавливаем их после перезагрузки.
     * Фильтр по подстроке в SQL только для скорости, разбор — обычным разбором заметки.
     */
    fun activeWithDate(): List<DueNote> =
        readableDatabase
            .rawQuery("SELECT json FROM notes WHERE json LIKE '%stamp%'", null)
            .use { cursor ->
                val out = ArrayList<DueNote>()
                while (cursor.moveToNext()) {
                    val raw = cursor.getString(0)
                    val row = parseNote(raw) ?: continue
                    val json = JSONObject(raw)
                    if (row.done) continue
                    val ms = jsonDatetime(json) ?: continue
                    out.add(
                        DueNote(
                            id = row.id,
                            text = row.text,
                            datetime = ms,
                            allDay = json.optBoolean("allDay"),
                            categoryId = row.categoryIds.firstOrNull(),
                            notified = json.optBoolean("notified"),
                        ),
                    )
                }
                out
            }

    /**
     * Сводка дел для утреннего ревью: просроченные, на сегодня и без срока.
     * Дело «на весь день» относится к своему дню — у него время 00:00.
     */
    fun reviewCounts(now: Long = System.currentTimeMillis()): ReviewCounts {
        val cal = java.util.Calendar.getInstance(java.util.Locale("ru"))
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val todayEnd = todayStart + 24L * 60 * 60 * 1000

        var overdue = 0
        var today = 0
        var noDate = 0
        readableDatabase
            .rawQuery("SELECT json FROM notes", null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val raw = cursor.getString(0)
                    val json = org.json.JSONObject(raw)
                    if (json.optString("status") != "active") continue
                    val ms = jsonDatetime(json)
                    if (ms == null) {
                        noDate++
                        continue
                    }
                    if (ms < todayStart) overdue++ else if (ms < todayEnd) today++
                }
            }
        return ReviewCounts(overdue, today, noDate)
    }

    /** Настройки — в таблице kv той же базы. */
    fun getKv(key: String): String? =
        readableDatabase
            .rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf(key))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun setKv(
        key: String,
        value: String,
    ) {
        val values = ContentValues().apply { put("k", key); put("v", value) }
        writableDatabase.insertWithOnConflict("kv", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM notes", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    /** Последние заметки — для проверок и списка. */
    fun recent(limit: Int = 5): List<String> =
        readableDatabase
            .rawQuery("SELECT json FROM notes ORDER BY id DESC LIMIT ?", arrayOf(limit.toString()))
            .use { cursor ->
                val result = ArrayList<String>()
                while (cursor.moveToNext()) result.add(cursor.getString(0))
                result
            }

    private companion object {
        const val DATABASE_NAME = "glassnotes.db"
        const val DATABASE_VERSION = 1

        val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        val CLOCK = SimpleDateFormat("HH:mm", Locale.US)
    }
}
