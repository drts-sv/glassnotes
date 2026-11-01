package com.glassnotes.app;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.webkit.JavascriptInterface;
import android.util.Log;

/**
 * Тактильный отклик. WebView-овский navigator.vibrate на Android работает через раз,
 * поэтому дёргаем системный вибратор, а на Android 10+ — штатные эффекты Pixel
 * (EFFECT_TICK — лёгкий тик на жест, EFFECT_CLICK — отклик на кнопку).
 */
public class HapticBridge {

    private final Context ctx;

    public HapticBridge(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Лёгкий тик: смахивание карточки, порог жеста. */
    @JavascriptInterface
    public boolean tick() {
        return buzz(10L, VibrationEffect.EFFECT_TICK);
    }

    /** Отклик на кнопку и подтверждения. */
    @JavascriptInterface
    public boolean click() {
        return buzz(22L, VibrationEffect.EFFECT_CLICK);
    }

    private boolean buzz(long fallbackMs, int effectId) {
        try {
            Vibrator v = vibrator();
            if (v == null || !v.hasVibrator()) return false;
            if (Build.VERSION.SDK_INT >= 29) {
                v.vibrate(VibrationEffect.createPredefined(effectId));
            } else if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(fallbackMs);
            }
            return true;
        } catch (Throwable t) {
            Log.w("GlassNotes", "вибро: " + t);
            return false;
        }
    }

    private Vibrator vibrator() {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm == null ? null : vm.getDefaultVibrator();
        }
        return (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
