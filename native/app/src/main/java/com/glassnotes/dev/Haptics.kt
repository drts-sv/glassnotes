package com.glassnotes.dev

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Тактильная отдача. Через системные константы `HapticFeedbackConstants`, а не через вибратор:
 * так отклик уважает системную настройку вибрации и не требует разрешения VIBRATE.
 * Значения подобраны по смыслу боевого приложения (`HapticBridge.haptic('click')` на свайп).
 */
object Haptics {
    /** Короткий отклик: нажатие, шаг при сдвигании карточки. */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Сдвинули до края — отклик сильнее. */
    fun strong(view: View) {
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        view.performHapticFeedback(constant)
    }

    /** Долгое нажатие (открылось меню). */
    fun longPress(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
