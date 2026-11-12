package com.glassnotes.dev

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.gigapisar.model.ModelConfig
import ru.gigapisar.model.ModelManager

/**
 * Скачивание модели вне UI: экран можно гасить, приложение — уводить в фон.
 *
 * Зачем сервис: Doze (`mState=IDLE`) отрезает сеть обычному приложению, даже если у него
 * есть WakeLock, — DNS перестаёт резолвиться и 224 МБ обрываются. Foreground-сервис
 * типа `dataSync` + уведомление с прогрессом продолжают загрузку, а вместе с исключением
 * из оптимизации батареи (его просит экран настроек) сеть остаётся живой.
 *
 * Прогресс уходит в SharedPreferences `model_download`: сервис и UI живут в разных местах,
 * и опрос префов дешевле привязки к LiveData.
 */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startInForeground()
        publish(state = STATE_RUNNING, progress = 0, message = null)

        job?.cancel()
        job =
            scope.launch {
                try {
                    ModelManager(applicationContext).download { percent ->
                        publish(state = STATE_RUNNING, progress = percent, message = null)
                        updateNotification(percent)
                    }
                    publish(state = STATE_OK, progress = 100, message = null)
                    updateNotification(100, done = true)
                } catch (error: CancellationException) {
                    publish(state = STATE_IDLE, progress = -1, message = null)
                    throw error
                } catch (error: Throwable) {
                    val message = error.message ?: "не удалось скачать"
                    Log.w(TAG, "сервис: загрузка модели не удалась — $message")
                    publish(state = STATE_ERROR, progress = -1, message = message)
                } finally {
                    job = null
                    stopSelf()
                }
            }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(CHANNEL_ID, "Скачивание модели", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Загрузка GigaAM v3"
                    setShowBadge(false)
                }
            manager.createNotificationChannel(channel)
        }

        val notification = buildNotification(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(
        percent: Int,
        done: Boolean = false,
    ) {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(percent, done))
        }
    }

    private fun buildNotification(
        percent: Int,
        done: Boolean = false,
    ): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val megabytes = (ModelConfig.EXPECTED_MODEL_SIZE / (1024 * 1024)).toInt()
        val text =
            when {
                done -> "Готово. Модель проверена и установлена."
                percent <= 0 -> "Готовлюсь к загрузке…"
                else -> "%d%% · %d из %d МБ. Экран можно гасить.".format(percent, megabytes * percent / 100, megabytes)
            }

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (done) "Модель распознавания готова" else "Скачиваю модель распознавания")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setContentIntent(open)
            .apply {
                if (!done && percent > 0) {
                    setProgress(100, percent, false)
                }
            }.build()
    }

    private fun publish(
        state: String,
        progress: Int,
        message: String?,
    ) {
        prefs(this)
            .edit()
            .putString(KEY_STATE, state)
            .putInt(KEY_PROGRESS, progress)
            .putString(KEY_MESSAGE, message)
            .apply()
    }

    companion object {
        const val STATE_IDLE = "idle"
        const val STATE_RUNNING = "running"
        const val STATE_OK = "ok"
        const val STATE_ERROR = "error"

        private const val TAG = "GlasNotesDev"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 4711
        private const val PREFS = "model_download"
        private const val KEY_STATE = "state"
        private const val KEY_PROGRESS = "progress"
        private const val KEY_MESSAGE = "message"

        fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun state(context: Context): String = prefs(context).getString(KEY_STATE, STATE_IDLE) ?: STATE_IDLE

        fun progress(context: Context): Int = prefs(context).getInt(KEY_PROGRESS, -1)

        fun message(context: Context): String? = prefs(context).getString(KEY_MESSAGE, null)

        fun start(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ModelDownloadService::class.java))
            prefs(context).edit().putString(KEY_STATE, STATE_IDLE).putInt(KEY_PROGRESS, -1).apply()
        }

        fun clearResult(context: Context) {
            prefs(context).edit().putString(KEY_STATE, STATE_IDLE).putInt(KEY_PROGRESS, -1).putString(KEY_MESSAGE, null).apply()
        }
    }
}
