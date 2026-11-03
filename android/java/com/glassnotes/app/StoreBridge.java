package com.glassnotes.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Хранилище заметок и настроек в базе приложения (SQLite).
 *
 * Зачем: IndexedDB и localStorage привязаны к origin страницы, а страница отдаётся
 * с http://127.0.0.1:ПОРТ. Сменился порт (8734 → 8735) — приложение видело другое хранилище
 * и список выглядел пустым, будто заметки пропали. Здесь данные лежат в базе приложения:
 * origin больше не важен, и то же хранилище доступно нативной стороне (пригодится для виджетов).
 *
 * Схема нарочно простая: заметка — это её JSON, ключ — автоинкремент.
 * Настройки — таблица ключ/значение, чтобы пережить смену origin вместе с заметками.
 */
public class StoreBridge extends SQLiteOpenHelper {

    private static final String TAG = "GlassNotes";
    private static final String DB = "glassnotes.db";
    private static final int VERSION = 1;

    private static StoreBridge instance;

    public static synchronized StoreBridge get(Context ctx) {
        if (instance == null) instance = new StoreBridge(ctx.getApplicationContext());
        return instance;
    }

    private StoreBridge(Context ctx) {
        super(ctx, DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, json TEXT NOT NULL, ts INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)");
        Log.i(TAG, "хранилище: база создана");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // схема одна: заметки как JSON + ключ/значение для настроек
    }

    // ===== заметки =====

    @JavascriptInterface
    public String available() {
        return "yes";
    }

    @JavascriptInterface
    public int count() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM notes", null);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) {
            Log.e(TAG, "хранилище: count", t);
            return -1;
        } finally {
            if (c != null) c.close();
        }
    }

    /** Все заметки: JSON-массив, в каждом элементе поле id проставлено из базы. */
    @JavascriptInterface
    public String all() {
        Cursor c = null;
        StringBuilder sb = new StringBuilder("[");
        try {
            c = getReadableDatabase().rawQuery("SELECT id, json FROM notes ORDER BY id", null);
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String json = c.getString(1);
                if (sb.length() > 1) sb.append(',');
                sb.append(withId(json, id));
            }
        } catch (Throwable t) {
            Log.e(TAG, "хранилище: all", t);
            return "[]";
        } finally {
            if (c != null) c.close();
        }
        return sb.append(']').toString();
    }

    /** Подставляет id из базы: в JSON его может не быть или он может быть старым. */
    private static String withId(String json, long id) {
        try {
            JSONObject o = new JSONObject(json);
            o.put("id", id);
            return o.toString();
        } catch (Throwable t) {
            return "{\"id\":" + id + "}";
        }
    }

    @JavascriptInterface
    public long add(String json) {
        try {
            JSONObject o = new JSONObject(json);
            o.remove("id");
            ContentValues v = new ContentValues();
            v.put("json", o.toString());
            v.put("ts", System.currentTimeMillis());
            long id = getWritableDatabase().insert("notes", null, v);
            Log.i(TAG, "хранилище: добавлена заметка " + id);
            return id;
        } catch (Throwable t) {
            Log.e(TAG, "хранилище: add", t);
            return -1;
        }
    }

    /** Правка: приходит только изменённые поля, остальное берём из базы. */
    @JavascriptInterface
    public String update(String idStr, String patch) {
        try {
            long id = Long.parseLong(idStr);
            SQLiteDatabase db = getWritableDatabase();
            String json = null;
            Cursor c = db.rawQuery("SELECT json FROM notes WHERE id=?", new String[]{String.valueOf(id)});
            try {
                if (c.moveToFirst()) json = c.getString(0);
            } finally {
                c.close();
            }
            if (json == null) return "not found";

            JSONObject o = new JSONObject(json);
            JSONObject p = new JSONObject(patch);
            Iterator<String> keys = p.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                o.put(k, p.get(k));
            }
            ContentValues v = new ContentValues();
            v.put("json", o.toString());
            v.put("ts", System.currentTimeMillis());
            db.update("notes", v, "id=?", new String[]{String.valueOf(id)});
            return "ok";
        } catch (Throwable t) {
            Log.e(TAG, "хранилище: update", t);
            return "error: " + t;
        }
    }

    @JavascriptInterface
    public String remove(String idStr) {
        try {
            long id = Long.parseLong(idStr);
            getWritableDatabase().delete("notes", "id=?", new String[]{String.valueOf(id)});
            Log.i(TAG, "хранилище: удалена заметка " + id);
            return "ok";
        } catch (Throwable t) {
            Log.e(TAG, "хранилище: remove", t);
            return "error: " + t;
        }
    }

    @JavascriptInterface
    public String clearAll() {
        try {
            getWritableDatabase().delete("notes", null, null);
            Log.i(TAG, "хранилище: все заметки удалены");
            return "ok";
        } catch (Throwable t) {
            Log.e(TAG, "хранилище: clearAll", t);
            return "error: " + t;
        }
    }

    // ===== настройки (ключ/значение) =====

    @JavascriptInterface
    public String kvAll() {
        Cursor c = null;
        JSONObject out = new JSONObject();
        try {
            c = getReadableDatabase().rawQuery("SELECT k, v FROM kv", null);
            while (c.moveToNext()) {
                try {
                    out.put(c.getString(0), c.getString(1));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "хранилище: kvAll", t);
        } finally {
            if (c != null) c.close();
        }
        return out.toString();
    }

    @JavascriptInterface
    public String kvSet(String k, String v) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("k", k);
            cv.put("v", v);
            getWritableDatabase().insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            return "ok";
        } catch (Throwable t) {
            Log.e(TAG, "хранилище: kvSet", t);
            return "error: " + t;
        }
    }

    @JavascriptInterface
    public String kvRemove(String k) {
        try {
            getWritableDatabase().delete("kv", "k=?", new String[]{k});
            return "ok";
        } catch (Throwable t) {
            Log.e(TAG, "хранилище: kvRemove", t);
            return "error: " + t;
        }
    }
}
