package com.glassnotes.dev

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Резервная копия заметок: файл наружу и обратно.
 *
 * Формат — тот же, что у боевого приложения: массив сырых строк (`id`, `json`, `ts`), поэтому
 * копия читается и там, и здесь. При импорте совпадающие `id` перезаписываются, новые — добавляются
 * с теми же номерами (важно: на id ссылаются напоминания).
 */
object Backup {
    private const val TAG = "GlasNotesDev"
    private val STAMP = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)

    fun stamp(): String = STAMP.format(Date())

    fun exportJson(store: NotesStore): String {
        val notes = JSONArray()
        store.allRows().forEach { (id, json, ts) ->
            notes.put(
                JSONObject().apply {
                    put("id", id)
                    put("json", JSONObject(json))
                    put("ts", ts)
                },
            )
        }
        val root =
            JSONObject().apply {
                put("app", "glasnote")
                put("version", 1)
                put("exported", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
                put("notes", notes)
            }
        Log.i(TAG, "экспорт: заметок %d".format(notes.length()))
        return root.toString(1)
    }

    /**
     * Читает файл и возвращает число записей; -1 — файл не наш. Пустой или битый файл не
     * должен стирать базу: всё проверяется до первой записи.
     */
    fun importJson(store: NotesStore, text: String): Int {
        val root =
            runCatching { JSONObject(text) }.getOrElse {
                Log.i(TAG, "импорт: файл не JSON")
                return -1
            }
        if (root.optString("app") != "glasnote") {
            Log.i(TAG, "импорт: чужой файл (app=%s)".format(root.optString("app")))
            return -1
        }
        val notes = root.optJSONArray("notes") ?: return -1
        var count = 0
        for (i in 0 until notes.length()) {
            val item = notes.optJSONObject(i) ?: continue
            val id = item.optLong("id", 0L)
            val json = item.optJSONObject("json") ?: continue
            val ts = item.optLong("ts", System.currentTimeMillis())
            if (id <= 0L) continue
            store.restore(id, json.toString(), ts)
            count++
        }
        Log.i(TAG, "импорт: записей %d".format(count))
        return count
    }
}
