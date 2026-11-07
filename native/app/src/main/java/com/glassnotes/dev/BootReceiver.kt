package com.glassnotes.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * После перезагрузки телефона все будильники в системе теряются — ставим напоминания заново
 * по базе заметок (как это делает боевое приложение).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }
        Log.i("GlasNotesDev", "загрузка телефона: восстанавливаю напоминания")
        Reminders.rescheduleAll(context)
    }
}
