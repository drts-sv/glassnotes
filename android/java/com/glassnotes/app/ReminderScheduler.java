package com.glassnotes.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Планировщик напоминаний на AlarmManager.
 *
 * Зачем: раньше момент напоминания стерёг JS-таймер внутри WebView, а Android замораживает
 * приложение в фоне — уведомления приходили только при открытом приложении. Алярм поднимает
 * процесс сам, даже если приложение закрыто; после перезагрузки расписание переподнимает
 * BootReceiver из сохранённой копии.
 */
public class ReminderScheduler {

    static final String PREFS = "glass_notes_alarms";
    static final String KEY_LIST = "scheduled";
    static final String KEY_REVIEW = "review";

    /** Свой номер запроса: у напоминаний номера — это id заметок, тут нужен отдельный. */
    static final int REVIEW_REQUEST = 7001;
    static final String EXTRA_REVIEW_COUNT = "review_count";
    static final String EXTRA_REVIEW_TIME = "review_time";

    static final String EXTRA_ID = "note_id";
    static final String EXTRA_TITLE = "note_title";
    static final String EXTRA_BODY = "note_body";
    static final String EXTRA_AT = "note_at";

    /** Больше этого числа алярмов не держим: напоминаний на полгода вперёд не бывает. */
    private static final int MAX_ALARMS = 60;

    private final Context ctx;

    public ReminderScheduler(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /**
     * Пересобрать расписание. Приходит список активных заметок с временем в будущем:
     * [{"id":1,"at":1730000000000,"title":"...","body":"..."}]
     * Возвращает строку-отчёт для лога приложения.
     */
    @JavascriptInterface
    public String schedule(String json) {
        try {
            JSONArray list = new JSONArray(json == null || json.isEmpty() ? "[]" : json);
            cancelAllInternal();
            SharedPreferences.Editor editor = prefs().edit();
            JSONArray saved = new JSONArray();
            int count = 0;
            for (int i = 0; i < list.length() && count < MAX_ALARMS; i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                long at = item.optLong("at", 0L);
                long id = item.optLong("id", 0L);
                if (at <= System.currentTimeMillis()) continue;
                arm(id, at, item.optString("title", "Напоминание"), item.optString("body", ""));
                JSONObject keep = new JSONObject();
                keep.put("id", id);
                keep.put("at", at);
                keep.put("title", item.optString("title", "Напоминание"));
                keep.put("body", item.optString("body", ""));
                saved.put(keep);
                count++;
            }
            editor.putString(KEY_LIST, saved.toString()).apply();
            Log.i("GlassNotes", "расписание: поставлено алярмов " + count + " из " + list.length());
            return "поставлено " + count + " из " + list.length() + exactState();
        } catch (Throwable t) {
            Log.e("GlassNotes", "не удалось поставить расписание", t);
            return "ошибка: " + t;
        }
    }

    @JavascriptInterface
    public String cancelAll() {
        cancelAllInternal();
        prefs().edit().putString(KEY_LIST, "[]").apply();
        return "снято";
    }

    /** Точные алярмы разрешены? На Android 12+ это отдельное разрешение, его может не быть. */
    @JavascriptInterface
    public boolean exactAllowed() {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
        } catch (Throwable t) {
            return false;
        }
    }

    private String exactState() {
        return exactAllowed() ? ", точные" : ", нет разрешения на точные (сдвиг возможен)";
    }

    /**
     * Утреннее ревью: одно ежедневное напоминание со списком дел на день. Пустая строка — выключить.
     * Алярм один, поэтому его нельзя путать с напоминаниями заметок — отсюда свой requestCode.
     */
    @JavascriptInterface
    public String scheduleReview(String json) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return "нет планировщика";
        try {
            am.cancel(reviewIntent(0, ""));
        } catch (Throwable ignored) {
        }

        if (json == null || json.isEmpty()) {
            prefs().edit().remove(KEY_REVIEW).apply();
            Log.i("GlassNotes", "ревью: выключено");
            return "ревью выключено";
        }

        try {
            JSONObject item = new JSONObject(json);
            long at = item.optLong("at", 0L);
            if (at <= System.currentTimeMillis()) return "ревью: время уже прошло";
            String time = item.optString("time", "08:00");
            int count = item.optInt("count", 0);
            prefs().edit().putString(KEY_REVIEW, json).apply();
            armReview(am, at, count, time);
            Log.i("GlassNotes", "ревью: поставлено на " + new java.util.Date(at) + ", дел на день " + count);
            return "ревью поставлено";
        } catch (Throwable t) {
            Log.e("GlassNotes", "ревью: не удалось поставить", t);
            return "ошибка: " + t;
        }
    }

    /** Для BootReceiver: состояние ревью лежит в prefs, нужен только контекст. */
    public static void rescheduleReview(Context ctx) {
        new ReminderScheduler(ctx).rescheduleReview();
    }

    /** Переподнять ревью на следующий день: после срабатывания и после перезагрузки телефона. */
    public void rescheduleReview() {
        String saved = prefs().getString(KEY_REVIEW, null);
        if (saved == null || saved.isEmpty()) return;
        try {
            JSONObject item = new JSONObject(saved);
            String time = item.optString("time", "08:00");
            int count = item.optInt("count", 0);
            long at = nextReviewAt(time);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            armReview(am, at, count, time);
            Log.i("GlassNotes", "ревью: переподнято на " + new java.util.Date(at));
        } catch (Throwable t) {
            Log.e("GlassNotes", "ревью: не удалось переподнять", t);
        }
    }

    /** Ближайшее наступление времени ревью: сегодня, если ещё не прошло, иначе завтра. */
    static long nextReviewAt(String time) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int hour = 8, minute = 0;
        try {
            String[] parts = (time == null ? "08:00" : time).split(":");
            hour = Integer.parseInt(parts[0]);
            if (parts.length > 1) minute = Integer.parseInt(parts[1]);
        } catch (Throwable ignored) {
        }
        c.set(java.util.Calendar.HOUR_OF_DAY, hour);
        c.set(java.util.Calendar.MINUTE, minute);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    private void armReview(AlarmManager am, long at, int count, String time) {
        PendingIntent pi = reviewIntent(count, time);
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            Log.e("GlassNotes", "ревью: алярм не поставлен", t);
        }
    }

    private PendingIntent reviewIntent(int count, String time) {
        Intent intent = new Intent(ctx, ReminderReceiver.class);
        intent.setAction(ReminderReceiver.ACTION_REVIEW);
        intent.putExtra(EXTRA_REVIEW_COUNT, count);
        intent.putExtra(EXTRA_REVIEW_TIME, time);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REVIEW_REQUEST, intent, flags);
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void cancelAllInternal() {
        try {
            JSONArray saved = new JSONArray(prefs().getString(KEY_LIST, "[]"));
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.optJSONObject(i);
                if (item == null) continue;
                am.cancel(pending(item.optLong("id", 0L), null, null, 0L));
            }
        } catch (Throwable t) {
            Log.w("GlassNotes", "снятие алярмов: " + t);
        }
    }

    private void arm(long id, long at, String title, String body) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(id, title, body, at);
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            Log.e("GlassNotes", "алярм не поставлен для " + id, t);
        }
    }

    private PendingIntent pending(long id, String title, String body, long at) {
        Intent intent = new Intent(ctx, ReminderReceiver.class);
        intent.setAction(ReminderReceiver.ACTION_REMIND);
        intent.putExtra(EXTRA_ID, id);
        if (title != null) intent.putExtra(EXTRA_TITLE, title);
        if (body != null) intent.putExtra(EXTRA_BODY, body);
        intent.putExtra(EXTRA_AT, at);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, (int) (id & 0x7fffffff), intent, flags);
    }

    /** Попросить у системы разрешение на точные алярмы (Android 12+). */
    @JavascriptInterface
    public boolean requestExactPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            if (!exactAllowed()) {
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(android.net.Uri.parse("package:" + ctx.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
                return false;
            }
        } catch (Throwable t) {
            Log.w("GlassNotes", "запрос точных алярмов: " + t);
        }
        return exactAllowed();
    }

    /** Отложить конкретное напоминание: ставим новый алярм и правим сохранённую копию расписания. */
    public static void snooze(Context ctx, long id, String title, String body, int minutes) {
        try {
            Context app = ctx.getApplicationContext();
            ReminderScheduler scheduler = new ReminderScheduler(app);
            long at = System.currentTimeMillis() + minutes * 60_000L;
            String safeTitle = (title == null || title.isEmpty()) ? "Напоминание" : title;
            String safeBody = body == null ? "" : body;
            scheduler.arm(id, at, safeTitle, safeBody);

            SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray saved = new JSONArray(p.getString(KEY_LIST, "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.optJSONObject(i);
                if (item == null || item.optLong("id", -1L) == id) continue;
                next.put(item);
            }
            JSONObject keep = new JSONObject();
            keep.put("id", id);
            keep.put("at", at);
            keep.put("title", safeTitle);
            keep.put("body", safeBody);
            next.put(keep);
            p.edit().putString(KEY_LIST, next.toString()).apply();
        } catch (Throwable t) {
            Log.e("GlassNotes", "отложить напоминание", t);
        }
    }

    /** Переподнять расписание после перезагрузки телефона. */
    public static void rescheduleAll(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray saved = new JSONArray(p.getString(KEY_LIST, "[]"));
            if (saved.length() == 0) return;
            ReminderScheduler scheduler = new ReminderScheduler(app);
            String result = scheduler.schedule(saved.toString());
            Log.i("GlassNotes", "после перезагрузки: " + result);
        } catch (Throwable t) {
            Log.e("GlassNotes", "переподнятие расписания", t);
        }
    }
}
