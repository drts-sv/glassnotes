package com.glassnotes.dev

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Показывает напоминание и обрабатывает кнопки на нём: «Отложить на 15 минут» и «Выполнено».
 *
 * Категория заметки задаёт цвет уведомления (`setColor` — красит маленькую иконку и имя приложения)
 * и большую картинку — круг её цвета с буквой названия. Полностью залитая карточка
 * (`setColorized`) не используется: на новых Android система её отменяет.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val id = intent.getLongExtra(Reminders.EXTRA_ID, -1L)
        when (intent.action) {
            Reminders.ACTION_SNOOZE -> {
                val minutes = intent.getIntExtra(Reminders.EXTRA_SNOOZE_MIN, 15)
                val at = System.currentTimeMillis() + minutes * 60_000L
                Reminders.schedule(
                    context,
                    id,
                    at,
                    intent.getStringExtra(Reminders.EXTRA_TEXT) ?: "",
                    intent.getStringExtra(Reminders.EXTRA_CATEGORY),
                    intent.getBooleanExtra(Reminders.EXTRA_ALL_DAY, false),
                )
                dismiss(context, id)
                Log.i(TAG, "напоминание #%d отложено на %d мин".format(id, minutes))
            }

            Reminders.ACTION_DONE -> {
                NotesStore(context).setStatus(id, "done")
                Reminders.cancel(context, id)
                dismiss(context, id)
                Log.i(TAG, "напоминание #%d отмечено выполненным".format(id))
            }

            else -> post(context, id, intent)
        }
    }

    private fun dismiss(
        context: Context,
        id: Long,
    ) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.toInt())
    }

    private fun post(
        context: Context,
        id: Long,
        intent: Intent,
    ) {
        val text = intent.getStringExtra(Reminders.EXTRA_TEXT).orEmpty().ifBlank { "Напоминание" }
        val category = intent.getStringExtra(Reminders.EXTRA_CATEGORY)
        val allDay = intent.getBooleanExtra(Reminders.EXTRA_ALL_DAY, false)
        Reminders.ensureChannel(context)

        val open =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val openPi = PendingIntent.getActivity(context, Reminders.openRequest(id), open, intentFlags())

        val builder =
            NotificationCompat.Builder(context, Reminders.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(if (allDay) "Дело на весь день" else "Напоминание")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                // Цвет категории: красит маленькую иконку и имя приложения.
                .setColor(Categories.androidColor(category))
                // Бейдж категории: круг её цвета с буквой — видно всегда и на всех версиях.
                .setLargeIcon(Reminders.badge(category, 168))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openPi)
                .setWhen(System.currentTimeMillis())

        if (!allDay) {
            builder.addAction(
                0,
                "Отложить на 15 минут",
                action(context, Reminders.ACTION_SNOOZE, id, text, category, snoozeMinutes = 15),
            )
        }
        builder.addAction(0, "Выполнено", action(context, Reminders.ACTION_DONE, id, text, category))

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(id.toInt(), builder.build())
        Log.i(
            TAG,
            "напоминание #%d показано: категория %s, цвет #%06X, текст «%s»".format(
                id,
                category ?: "нет",
                Categories.androidColor(category) and 0xFFFFFF,
                text,
            ),
        )
    }

    private fun action(
        context: Context,
        action: String,
        id: Long,
        text: String,
        category: String?,
        snoozeMinutes: Int = 0,
    ): PendingIntent {
        val intent =
            Intent(context, ReminderReceiver::class.java).apply {
                this.action = action
                putExtra(Reminders.EXTRA_ID, id)
                putExtra(Reminders.EXTRA_TEXT, text)
                putExtra(Reminders.EXTRA_CATEGORY, category)
                if (snoozeMinutes > 0) putExtra(Reminders.EXTRA_SNOOZE_MIN, snoozeMinutes)
            }
        val request = (Reminders.openRequest(id) + ("$action$snoozeMinutes").hashCode() and 0xFF)
        return PendingIntent.getBroadcast(context, request, intent, intentFlags())
    }

    private fun intentFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return flags
    }

    private companion object {
        const val TAG = "GlasNotesDev"
    }
}
