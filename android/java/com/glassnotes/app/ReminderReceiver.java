package com.glassnotes.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

/**
 * Показывает напоминание по алярму — работает и когда приложение закрыто.
 * Кнопки: отложить на 15 минут, отложить на час, отметить выполненным.
 * «Выполнено» открывает приложение с флагом: саму заметку меняет JS, он владеет базой.
 */
public class ReminderReceiver extends BroadcastReceiver {

    static final String ACTION_REMIND = "com.glassnotes.app.ACTION_REMIND";
    static final String ACTION_SNOOZE = "com.glassnotes.app.ACTION_SNOOZE";
    static final String ACTION_SILENCE = "com.glassnotes.app.ACTION_SILENCE";
    static final String ACTION_REVIEW = "com.glassnotes.app.ACTION_REVIEW";
    static final String ACTION_REVIEW_CANCEL = "com.glassnotes.app.ACTION_REVIEW_CANCEL";
    /** Смахивание уведомления ревью: показывать сплэш сегодня больше не нужно. */
    static final String ACTION_REVIEW_DISMISS = "com.glassnotes.app.ACTION_REVIEW_DISMISS";

    private static final String EXTRA_SNOOZE_MIN = "snooze_min";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        long id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, 0L);
        String title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE);
        String body = intent.getStringExtra(ReminderScheduler.EXTRA_BODY);

        if (ACTION_REVIEW.equals(action)) {
            // Утреннее ревью: показываем напоминание и сразу ставим следующее на завтра.
            int count = intent.getIntExtra(ReminderScheduler.EXTRA_REVIEW_COUNT, 0);
            int noDate = intent.getIntExtra(ReminderScheduler.EXTRA_REVIEW_NODATE, 0);
            // Пересчитываем по базе: сохранённое число устаревает за ночь и врало («0 дел»).
            try {
                JSONObject live = new JSONObject(StoreBridge.get(context).reviewCounts());
                count = live.optInt("today", count);
                noDate = live.optInt("noDate", noDate);
            } catch (Throwable t) {
                Log.w("GlassNotes", "ревью: пересчёт не вышел, беру сохранённый", t);
            }
            if (count + noDate > 0) showReview(context, count, noDate);
            else Log.i("GlassNotes", "ревью: дел нет — уведомление не показываю");
            new ReminderScheduler(context).rescheduleReview();
            return;
        }

        if (ACTION_REVIEW_CANCEL.equals(action) || ACTION_REVIEW_DISMISS.equals(action)) {
            // И кнопка «Отменить», и смахивание означают одно: сегодня тему закрыли.
            // Уведомление гасим и запоминаем дату — сплэш сам больше не появится (#77).
            try {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(ReminderScheduler.REVIEW_REQUEST);
                new ReminderScheduler(context).markReviewDismissedToday();
                Log.i("GlassNotes", "ревью: уведомление убрано, на сегодня закрыто");
            } catch (Throwable t) {
                Log.e("GlassNotes", "ревью: не удалось убрать уведомление", t);
            }
            return;
        }

        if (ACTION_SILENCE.equals(action)) {
            // Аварийный выход: гасим уведомление и снимаем все алярмы приложения — до этого
            // единственным способом остановить зацикленный сигнал была перезагрузка телефона.
            cancel(context, id);
            new ReminderScheduler(context).cancelAll();
            android.util.Log.i("GlassNotes", "тишина: уведомление и расписание сняты");
            return;
        }

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
                    .setOnlyAlertOnce(true)   // повторная выдача не звенит заново
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setContentIntent(openApp(context, id, 0))
                    // Короткие подписи: длинные «Отложить 15 минут» Android обрезал до «Отлож…»,
                    // и было не понять, чем кнопки отличаются.
                    .addAction(0, "+15 мин", snooze(context, id, title, body, 15))
                    .addAction(0, "+1 час", snooze(context, id, title, body, 60))
                    .addAction(0, "Выполнено", openApp(context, id, 1))
                    .addAction(0, "Тишина", silence(context, id));

            nm.notify((int) (id & 0x7fffffff), b.build());
            Log.i("GlassNotes", "показано напоминание: " + title);
        } catch (Throwable t) {
            Log.e("GlassNotes", "не удалось показать напоминание", t);
        }
    }

    /** Уведомление утреннего ревью: тап открывает приложение, оно само покажет список дел. */
    private void showReview(Context context, int count, int noDate) {
        try {
            NotificationBridge.ensureChannel(context);
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            android.content.Intent open = new android.content.Intent(context, MainActivity.class);
            open.setFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(context, ReminderScheduler.REVIEW_REQUEST, open, flags);

            Notification.Builder b = (android.os.Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(context, "glass-notes-reminders")
                    : new Notification.Builder(context);
            // Цифр в уведомлении нет намеренно: число устаревало за ночь и врало («0 дел»),
            // а список всё равно показывает сплэш. Здесь только приглашение и две кнопки.
            String text = "Нажмите, чтобы посмотреть задачи на день";
            Log.i("GlassNotes", "ревью: уведомление — " + text + " (дел на день " + count + ", без срока " + noDate + ")");
            // Значок приложения берём через PackageManager и рисуем на канве: иконка у нас
            // адаптивная (mipmap-anydpi-v26/ic_launcher.xml), и BitmapFactory.decodeResource
            // для неё возвращает null — на этом первая попытка и провалилась.
            android.graphics.Bitmap bigIcon = null;
            try {
                android.graphics.drawable.Drawable d = context.getPackageManager()
                        .getApplicationIcon(context.getPackageName());
                int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 144;
                int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 144;
                bigIcon = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(bigIcon);
                d.setBounds(0, 0, w, h);
                d.draw(cv);
            } catch (Throwable t) {
                Log.w("GlassNotes", "ревью: значок приложения не получился", t);
            }
            Log.i("GlassNotes", "ревью: крупная иконка " + (bigIcon != null ? "есть" : "не нашлась"));
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("🌅 Утреннее ревью")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    // #77: тап по самому уведомлению (не только по кнопке) открывает ревью...
                    .setContentIntent(reviewOpen(context))
                    // ...а смахивание закрывает тему на сегодня
                    .setDeleteIntent(reviewDismiss(context))
                    .addAction(0, "Показать", reviewOpen(context))
                    .addAction(0, "Отменить", reviewDismiss(context))
                    .setStyle(new Notification.BigTextStyle().bigText("Просроченные, на сегодня и без срока — всё в одном списке"));
            // крупная иконка — узнаваемый значок приложения слева от заголовка
            if (bigIcon != null) b.setLargeIcon(bigIcon);
            nm.notify(ReminderScheduler.REVIEW_REQUEST, b.build());
            Log.i("GlassNotes", "ревью: уведомление показано, дел " + count);
        } catch (Throwable t) {
            Log.e("GlassNotes", "ревью: не удалось показать уведомление", t);
        }
    }

    /** «Показать» — открыть приложение сразу на сплэше ревью. */
    private PendingIntent reviewOpen(Context ctx) {
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(MainActivity.EXTRA_REVIEW, true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(ctx, ReminderScheduler.REVIEW_REQUEST + 1, intent, flags);
    }

    /** Смахивание уведомления или «Отменить» — убрать уведомление и закрыть тему на сегодня. */
    private PendingIntent reviewDismiss(Context ctx) {
        Intent intent = new Intent(ctx, ReminderReceiver.class);
        intent.setAction(ACTION_REVIEW_DISMISS);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, ReminderScheduler.REVIEW_REQUEST + 2, intent, flags);
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

    /** «Тишина»: гасит уведомление и все алярмы приложения. */
    private PendingIntent silence(Context ctx, long id) {
        Intent intent = new Intent(ctx, ReminderReceiver.class);
        intent.setAction(ACTION_SILENCE);
        intent.putExtra(ReminderScheduler.EXTRA_ID, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, (int) ((id & 0x1fffff) * 10 + 7), intent, flags);
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
