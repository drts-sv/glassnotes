package com.glassnotes.dev

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Тактильная отдача. Всё через системные `HapticFeedbackConstants`, а не через вибратор:
 * так отклик уважает системную настройку вибрации и не требует разрешения VIBRATE.
 *
 * Как это делают другие (проверено 07.11.2026):
 * - Android, принципы отклика (developer.android.com/develop/ui/views/haptics/haptics-principles):
 *   отклик — на дискретные события, и прямо назван хорошим моментом «пересечение порога в жесте,
 *   когда смахивание будет выполнено». Там же предупреждение: самая частая ошибка — откликов
 *   СЛИШКОМ много, а «buzzy» вибрации надо избегать.
 * - Крупные приложения (YouTube, Instagram, TikTok) при pull-to-refresh дают ОДИН отклик на
 *   достижении порога, а не серию.
 * - В самой платформе для этого жеста есть точные константы: `GESTURE_THRESHOLD_ACTIVATE`
 *   (перешли порог) и `GESTURE_THRESHOLD_DEACTIVATE` (вернулись под порог).
 *
 * Отсюда отклик при свайпе: короткий — в начале сдвига, переход порога (и обратно) — платформенными
 * константами, действие по отпусканию — подтверждение (удаление — сильнее). Пошаговой трещотки нет.
 */
object Haptics {
    private fun perform(
        view: View,
        constant: Int,
    ) = view.performHapticFeedback(constant)

    /** Короткий отклик: нажатие, чипы, начало сдвига карточки. */
    fun tick(view: View) = perform(view, HapticFeedbackConstants.KEYBOARD_TAP)

    /**
     * Перешли порог смахивания (`active = true`) или вернулись под него (`false`).
     * На Android 11+ — специальные константы платформы, ниже — короткий щелчок.
     */
    fun threshold(
        view: View,
        active: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            perform(
                view,
                if (active) {
                    HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                } else {
                    HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
                },
            )
        } else if (active) {
            perform(view, HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /** Действие выполнено: отметили выполненным — подтверждение. */
    fun confirm(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            perform(view, HapticFeedbackConstants.CONFIRM)
        } else {
            perform(view, HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** Удаление — отклик сильнее: действие необратимое. */
    fun destructive(view: View) = perform(view, HapticFeedbackConstants.CONTEXT_CLICK)

    /**
     * Тройная отдача — «действие подтверждено удержанием» (свайп с удержанием).
     * Три коротких импульса с паузами: их не спутать с одиночным подтверждением.
     */
    fun triple(view: View) {
        perform(view, HapticFeedbackConstants.LONG_PRESS)
        view.postDelayed({ perform(view, HapticFeedbackConstants.CONTEXT_CLICK) }, 130)
        view.postDelayed({ perform(view, HapticFeedbackConstants.CONTEXT_CLICK) }, 260)
    }

    /** Долгое нажатие (открылось меню). */
    fun longPress(view: View) = perform(view, HapticFeedbackConstants.LONG_PRESS)
}
