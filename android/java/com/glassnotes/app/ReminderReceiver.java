package com.glassnotes.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Показывает напоминание по алярму — работает и когда приложение закрыто.
 * Кнопки: отложить на 15 минут, отложить на час, отметить выполненным.
 * «Выполнено» открывает приложение с флагом: саму заметку меняет JS, он владеет базой.
 */
public class ReminderReceiver extends BroadcastReceiver {

    static final String ACTION_REMIND = "com.glassnotes.app.ACTION_REMIND";
    static final String ACTION_SNOOZE = "com.glassnotes.app.ACTION_SNOOZE";

    private static final String EXTRA_SNOOZE_MIN = "snooze_min";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        long id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, 0L);
        String title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE);
        String body = intent.getStringExtra(ReminderScheduler.EXTRA_BODY);

        if (ACTION_SNOOZE.equals(action)) {
            int minutes = intent.getIntExtra(EXTRA_SNOOZE_MIN, 15);
            ReminderScheduler.snooze(context, id, title, body, minutes);
            cancel(context, id);
            Log.i("GlassNotes", "отложено на " + minutes + " мин: " + title);
            return;
        }

        try {
            NotificationBridge.ensureChannel(context);
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Notification.Builder b = (android.os.Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(context, "glass-notes-reminders")
                    : new Notification.Builder(context);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title == null ? "Напоминание" : title)
                    .setContentText(body == null ? "" : body)
                    .setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body))
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setContentIntent(openApp(context, id, 0))
                    .addAction(0, "Отложить 15 мин", snooze(context, id, title, body, 15))
                    .addAction(0, "Отложить 1 час", snooze(context, id, title, body, 60))
                    .addAction(0, "Выполнено", openApp(context, id, 1));

            nm.notify((int) (id & 0x7fffffff), b.build());
            Log.i("GlassNotes", "показано напоминание: " + title);
        } catch (Throwable t) {
            Log.e("GlassNotes", "не удалось показать напоминание", t);
        }
    }

    private PendingIntent snooze(Context ctx, long id, String title, String body, int minutes) {
        Intent intent = new Intent(ctx, ReminderReceiver.class);
        intent.setAction(ACTION_SNOOZE);
        intent.putExtra(ReminderScheduler.EXTRA_ID, id);
        intent.putExtra(ReminderScheduler.EXTRA_TITLE, title);
        intent.putExtra(ReminderScheduler.EXTRA_BODY, body);
        intent.putExtra(EXTRA_SNOOZE_MIN, minutes);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        // различаем кнопки не только extras: requestCode должен быть своим у каждой
        return PendingIntent.getBroadcast(ctx, (int) ((id & 0x3fffff) * 10 + minutes % 10), intent, flags);
    }

    private PendingIntent openApp(Context ctx, long id, int mode) {
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(MainActivity.EXTRA_NOTE_ID, id);
        intent.putExtra(MainActivity.EXTRA_NOTE_MODE, mode);   // 0 — открыть, 1 — отметить выполненной
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(ctx, (int) ((id & 0x3fffff) * 10 + mode), intent, flags);
    }

    private void cancel(Context ctx, long id) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel((int) (id & 0x7fffffff));
        } catch (Throwable ignored) {
        }
    }
}
