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

    /** Сменить категорию заметки (цвет карточки меняется сразу). */
    fun setCategory(
        id: Long,
        categoryId: String?,
    ) {
        patch(id) { note ->
            note.put("categoryIds", JSONArray().apply { categoryId?.let { put(it) } })
        }
    }

    fun remove(id: Long) {
        writableDatabase.delete("notes", "id = ?", arrayOf(id.toString()))
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
                    val dt = json.optString("datetime")
                    if (dt.isBlank() || dt == "null") continue
                    val ms = runCatching { ISO.parse(dt)?.time }.getOrNull() ?: continue
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

    /** Настройки — в таблице kv той же базы. Ключи короткие и совпадают с названиями полей. */
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
