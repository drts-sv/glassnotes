package com.glassnotes.dev

import android.content.Context
import android.os.Build
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.app.WallpaperManager

/**
 * Значения взяты из боевого приложения (`index.html`, `ThemeBridge.java`), а не придуманы:
 * дизайн переносится числами, иначе он «похож», но не тот.
 */
object Design {
    /** --glass-rgb: 28 30 42 */
    val glassRgb = Color(0xFF1C1E2A)

    /** --app-veil: 0.35 — матовая подложка под всем приложением. */
    const val veil = 0.35f

    /** --card-alpha: 0.55 */
    const val cardAlpha = 0.55f

    /** --bg-blur: 14px */
    val backdropBlur = 14.dp

    /** --surface-border: rgba(255,255,255,0.10) */
    val border = Color(0x1AFFFFFF)

    /** --text-primary / --text-secondary */
    val textPrimary = Color.White
    val textSecondary = Color(0xE6E6EBFF)

    /** --accent: #007aff (переопределяется палитрой системы) */
    val accentDefault = Color(0xFF007AFF)

    /** --tab-done-color: #34c759 */
    val done = Color(0xFF34C759)

    /** цвет кнопки «Стоп»: border rgba(255,59,48,0.4), background rgba(255,59,48,0.15) */
    val stopRed = Color(0xFFFF3B30)

    // ---- размеры (CSS px) ----
    /** padding у .glass-app: 0.35rem сверху + инсет, 0.8rem по бокам, 0.4rem снизу */
    val padTop = 5.6.dp
    val padSide = 12.8.dp
    val padBottom = 6.4.dp

    /** .cancel-fab: 130px (116px на узких экранах) */
    val fab = 130.dp
    val fabIcon = 52.dp

    /** .rec-cat-chip: padding 9px 15px, radius 12px, min-height 42px, font 0.9rem */
    val chipPadH = 15.dp
    val chipPadV = 9.dp
    val chipRadius = 12.dp
    val chipMinHeight = 42.dp
    val chipGap = 9.dp
    val chipFont = 14.4.sp

    /** свечение кнопки: opacity 0.22 + 0.7*level, радиус 10px + 30px*level */
    fun glowAlpha(level: Float) = 0.22f + 0.7f * level
    val glowRadiusMin = 10.dp
    val glowRadiusMax = 40.dp
}

/**
 * Акцент из системной палитры — та же логика, что в `ThemeBridge` боевого приложения:
 * `system_accent1_200`, иначе `system_accent1_500`; на Android < 12 палитры нет.
 */
fun systemAccent(context: Context): Color {
    if (Build.VERSION.SDK_INT < 31) return Design.accentDefault
    val value = firstColor(context, "system_accent1_200", "system_accent1_500")
    return if (value != 0) Color(value) else Design.accentDefault
}

/** Цвет текста на акценте — по яркости акцента (в боевом приложении это --on-accent). */
fun onAccent(accent: Color): Color {
    val luminance = 0.299f * accent.red + 0.587f * accent.green + 0.114f * accent.blue
    return if (luminance > 0.6f) Color(0xFF101114) else Color.White
}

private fun firstColor(
    context: Context,
    vararg names: String,
): Int {
    for (name in names) {
        val id = context.resources.getIdentifier(name, "color", "android")
        if (id == 0) continue
        try {
            val value = context.getColor(id)
            if (value != 0) return value
        } catch (_: Throwable) {
        }
    }
    return 0
}

/**
 * Фон приложения: обои пользователя с размытием + матирование, как `backdrop-filter` в боевом
 * приложении. Если системные обои недоступны (или Android < 12, где нет `Modifier.blur`),
 * остаётся тот же градиент, что на скриншотах приложения.
 */
@Composable
fun GlassBackdrop() {
    val context = LocalContext.current
    val wallpaper = remember { runCatching { WallpaperManager.getInstance(context).drawable }.getOrNull() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (wallpaper != null && Build.VERSION.SDK_INT >= 31) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        setImageDrawable(wallpaper)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize().blur(Design.backdropBlur),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xF2192337), Color(0xFF0A0C12)),
                            ),
                        ),
            )
        }

        // Матовая подложка: без неё на светлых обоях текст нечитаем (то же решение, что в приложении).
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Design.glassRgb.copy(alpha = Design.veil)),
        )
    }
}
