package com.glassnotes.dev

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.Log

/**
 * Напоминания на AlarmManager — те же правила, что в боевом приложении: будильник ставим точный,
 * если система разрешает точные, иначе обычный. На Android 12+ без разрешения точный будильник
 * молча не срабатывает, поэтому выбор обязателен.
 *
 * Запросы строятся по id заметки — по нему же отмена и перенос.
 */
object Reminders {
    const val CHANNEL = "glass-notes-reminders"
    const val ACTION_REMIND = "com.glassnotes.dev.ACTION_REMIND"
    const val ACTION_SNOOZE = "com.glassnotes.dev.ACTION_SNOOZE"
    const val ACTION_DONE = "com.glassnotes.dev.ACTION_DONE"
    const val ACTION_REVIEW = "com.glassnotes.dev.ACTION_REVIEW"
    const val EXTRA_ID = "noteId"
    const val EXTRA_TEXT = "noteText"
    const val EXTRA_CATEGORY = "noteCategory"
    const val EXTRA_ALL_DAY = "noteAllDay"
    const val EXTRA_SNOOZE_MIN = "snoozeMinutes"

    private const val TAG = "GlasNotesDev"
    private const val BASE_REQUEST = 7100
    private const val REVIEW_REQUEST = 7001 // как REVIEW_REQUEST в боевом приложении
    const val REVIEW_NOTIFICATION_ID = 7001

    /** Когда показывать утреннее ревью. Дефолт боевого приложения; в настройки вынести — следующий шаг. */
    const val REVIEW_HOUR = 8
    const val REVIEW_MINUTE = 30
    private const val BASE_OPEN = 7900

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val channel = NotificationChannel(CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "Напоминания о делах по времени"
            channel.enableVibration(true)
            nm.createNotificationChannel(channel)
        }
    }

    /** Поставить напоминание на конкретный момент. */
    fun schedule(
        context: Context,
        id: Long,
        at: Long,
        text: String,
        categoryId: String?,
        allDay: Boolean = false,
    ) {
        ensureChannel(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, id, text, categoryId, allDay)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        Log.i(
            TAG,
            "напоминание #%d поставлено на %s (%s)".format(
                id,
                java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(at)),
                if (exact) "точно" else "неточно — нет разрешения на точные будильники",
            ),
        )
    }

    /** Снять напоминание: выполнено, удалено, перенесено. */
    fun cancel(
        context: Context,
        id: Long,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, id, "", null, false)
        am.cancel(pi)
        pi.cancel()
    }

    /** Поставить утреннее ревью: без аргумента — на ближайшие 8:30, в остальных случаях на заданный момент. */
    fun scheduleReview(
        context: Context,
        at: Long = 0L,
    ) {
        val whenAt =
            if (at > 0) {
                at
            } else {
                java.util.Calendar.getInstance(java.util.Locale("ru")).apply {
                    set(java.util.Calendar.HOUR_OF_DAY, REVIEW_HOUR)
                    set(java.util.Calendar.MINUTE, REVIEW_MINUTE)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_MONTH, 1)
                }.timeInMillis
            }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply { action = ACTION_REVIEW }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(context, REVIEW_REQUEST, intent, flags)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, pi)
        }
        Log.i(
            TAG,
            "утреннее ревью запланировано на %s".format(
                java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(whenAt)),
            ),
        )
    }

    /** После перезагрузки будильники теряются — ставим заново по базе. */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        var count = 0
        NotesStore(context).activeWithDate().forEach { note ->
            val at = note.datetime ?: return@forEach
            if (at > now && !note.notified) {
                schedule(context, note.id, at, note.text, note.categoryId, note.allDay)
                count++
            }
        }
        Log.i(TAG, "после перезагрузки восстановлено напоминаний: %d".format(count))
    }

    /** Куда доставить напоминание: широковещательное сообщение нашему приёмнику. */
    private fun pending(
        context: Context,
        id: Long,
        text: String,
        categoryId: String?,
        allDay: Boolean,
    ): PendingIntent {
        val intent =
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_REMIND
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_CATEGORY, categoryId)
                putExtra(EXTRA_ALL_DAY, allDay)
            }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, (BASE_REQUEST + id).toInt(), intent, flags)
    }

    /** Код для открытия приложения (общий для всех напоминаний). */
    fun openRequest(id: Long): Int = (BASE_OPEN + id).toInt()

    /**
     * Бейдж категории: круг её цвета с первой буквой названия. Это «большая картинка» уведомления —
     * единственный способ показать цвет в полную силу: полностью залитую карточку система на новых
     * Android отменяет, а `setColor` красит только маленькую иконку и имя приложения.
     */
    fun badge(
        categoryId: String?,
        sizePx: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Categories.androidColor(categoryId)
                style = Paint.Style.FILL
            }
        val radius = sizePx / 2f
        canvas.drawCircle(radius, radius, radius, fill)
        val letter =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = sizePx * 0.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
        val baseline = radius - (letter.descent() + letter.ascent()) / 2f
        canvas.drawText(Categories.letter(categoryId), radius, baseline, letter)
        return bitmap
    }
}
