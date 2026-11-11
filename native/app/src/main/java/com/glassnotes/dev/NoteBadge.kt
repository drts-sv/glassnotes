package com.glassnotes.dev

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Счётчик дел на иконке приложения.
 *
 * Отдельного API для числа на иконке в Android нет: лаунчер рисует его по уведомлению
 * с `setNumber`. Поэтому держим одно тихое уведомление «N дел на сегодня» и обновляем его
 * при каждом изменении списка; при нуле — снимаем.
 */
object NoteBadge {
    private const val CHANNEL = "glass-notes-count"
    private const val ID = 9001

    /** Actions и ключ: смахивание уведомления запоминается, чтобы оно не всплывало снова. */
    const val ACTION_DISMISS = "com.glassnotes.dev.ACTION_BADGE_DISMISS"
    const val EXTRA_COUNT = "badgeCount"
    const val KEY_DISMISSED = "badge_dismissed"

    fun update(
        context: Context,
        store: NotesStore,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val counts = store.reviewCounts()
        val active = counts.overdue + counts.today
        if (active <= 0) {
            manager.cancel(ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) {
                // Без разрешения уведомление не покажется; молча выходим, ничего не ломаем.
                return
            }
        }
        // Если пользователь смахнул уведомление при этом же числе дел — не возвращаем его.
        if (store.getKv(KEY_DISMISSED) == active.toString()) {
            Log.i("GlasNotesDev", "счётчик на иконке: %d скрыт пользователем".format(active))
            return
        }
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Счётчик дел", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(true)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
        val text =
            when {
                counts.overdue > 0 -> "Просрочено %d · сегодня %d".format(counts.overdue, counts.today)
                else -> "На сегодня %d".format(counts.today)
            }
        Log.i("GlasNotesDev", "счётчик на иконке: %d (просрочено %d, сегодня %d)".format(active, counts.overdue, counts.today))
        val notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_check)
                .setContentTitle("Дел на сегодня: %d".format(active))
                .setContentText(text)
                .setNumber(active)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setDeleteIntent(dismissIntent(context, active))
                .build()
        manager.notify(ID, notification)
    }

    /** Срабатывает при смахивании: запоминаем число, при котором уведомление убрали. */
    private fun dismissIntent(
        context: Context,
        count: Int,
    ): android.app.PendingIntent {
        val intent =
            android.content.Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_DISMISS
                putExtra(EXTRA_COUNT, count.toString())
            }
        return android.app.PendingIntent.getBroadcast(
            context,
            ID,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
