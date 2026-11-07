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
    ): Long {
        val now = System.currentTimeMillis()
        val created = ISO.format(Date(now))
        val note =
            JSONObject().apply {
                put("text", text)
                put("created", created)
                put("timestamp", now)
                put("time", CLOCK.format(Date(now)))
                put("datetime", JSONObject.NULL)
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
