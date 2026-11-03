package com.glassnotes.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.JavascriptInterface;

import java.util.HashMap;
import java.util.Map;

/**
 * Мост уведомлений: в WebView нет Notification API, поэтому страница получает
 * шим (native-shim.js), который зовёт эти методы, а они кладут настоящее
 * уведомление Android в шторку.
 */
public class NotificationBridge {

    private static final String CHANNEL_ID = "glass-notes-reminders";
    private static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    private final Activity ctx;

    public NotificationBridge(Activity ctx) {
        this.ctx = ctx;
    }

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Напоминания", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Напоминания Glass Notes");
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }
        }
    }

    @JavascriptInterface
    public String permission() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return ctx.checkSelfPermission(POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED ? "granted" : "default";
            }
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            return (nm != null && nm.areNotificationsEnabled()) ? "granted" : "denied";
        } catch (Throwable t) {
            return "granted";
        }
    }

    /** Заодно поднимает системный запрос разрешения — из JS он невозможен. */
    @JavascriptInterface
    public String requestPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && ctx.checkSelfPermission(POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ctx.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ctx.requestPermissions(new String[]{POST_NOTIFICATIONS}, MainActivity.REQ_PERMS);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        return permission();
    }

    /** Порта больше нет: страницу отдаём из assets по постоянному origin. Метод оставлен для совместимости. */
    @JavascriptInterface
    public int port() {
        return 0;
    }

    /** Действие из уведомления («Выполнено») — забираем один раз. */
    @JavascriptInterface
    public String consumeAction() {
        return MainActivity.takePendingAction();
    }

    /** Когда это же уведомление показывали в прошлый раз — чтобы не звонить повторно. */
    private static final Map<String, Long> LAST_SHOWN = new HashMap<>();

    /**
     * Гасит уведомление напоминания по id заметки: после «Выполнено» оно не должно
     * оставаться в шторке — иначе висит источник звука, который можно и не заметить.
     */
    @JavascriptInterface
    public String cancelNote(long noteId) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                android.util.Log.w("GlassNotes", "отмена: нет NotificationManager");
                return "нет менеджера";
            }
            int id = (int) (noteId & 0x7fffffff);
            nm.cancel(id);
            android.util.Log.i("GlassNotes", "отмена уведомления id=" + id);
            return "ok " + id;
        } catch (Throwable t) {
            android.util.Log.e("GlassNotes", "отмена уведомления не удалась", t);
            return "ошибка: " + t;
        }
    }

    @JavascriptInterface
    public boolean show(String title, String body, String tag) {
        try {
            ensureChannel(ctx);
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(ctx, CHANNEL_ID)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)   // повторный показ того же не должен звучать снова
                    .setContentIntent(pi)
                    .setStyle(new Notification.BigTextStyle().bigText(body));

            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            // Стабильный id: то же уведомление должно ЗАМЕНЯТЬ себя, а не копиться в шторке.
            // Раньше id считался от текущего времени, поэтому каждое повторное срабатывание
            // добавляло новое уведомление: звук шёл бесконечно, смахивание убирало только одно.
            String key = (tag != null && !tag.isEmpty()) ? tag : (title + "|" + body);
            int id = key.hashCode() & 0x7fffffff;

            long now = System.currentTimeMillis();
            Long prev = LAST_SHOWN.get(key);
            if (prev != null && now - prev < 30000L) {
                android.util.Log.i("GlassNotes", "уведомление повторено раньше 30 с — пропускаю");
                return false;
            }
            LAST_SHOWN.put(key, now);
            nm.notify(id, b.build());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
