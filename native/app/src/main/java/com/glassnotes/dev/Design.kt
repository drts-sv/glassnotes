package com.glassnotes.dev

import android.content.Context
import android.os.Build
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    /**
     * Тема — числа из `body.dark` / `body.light` боевого приложения, а не на глаз:
     * стекло 28 30 42 против 252 253 255, текст white / #1a1c2c против
     * rgba(230,235,255,0.9) / #2c3e66, рамка 0.10 белого против 0.10 чёрного.
     */
    var light by mutableStateOf(false)
        private set

    /** Режим из настроек: "auto" | "dark" | "light" — храним как есть, чтобы показать выбор. */
    var themeMode by mutableStateOf("auto")
        private set

    /** Переключить тему: auto идёт за системой (isSystemInDarkTheme). */
    fun setTheme(
        mode: String,
        systemDark: Boolean,
    ) {
        themeMode = mode
        light =
            when (mode) {
                "light" -> true
                "dark" -> false
                else -> !systemDark
            }
    }

    /** --glass-rgb: 28 30 42 (тёмная) / 252 253 255 (светлая). */
    val glassRgb: Color
        get() = if (light) Color(0xFFFCFDFF) else Color(0xFF13151D)

    /**
     * --card-alpha. Именно состояние, а не обычная переменная: раньше ползунок менял число,
     * но Compose не знал об изменении, и карточки (они в LazyColumn со стабильными ключами)
     * оставались с прежней плотностью — перерисовывалась только карточка заголовка.
     */
    var cardAlpha by mutableStateOf(0.80f)

    /** --app-veil: матовая подложка под всем приложением. */
    var veil by mutableStateOf(0.35f)

    /** --bg-blur: 14px */
    var backdropBlur by mutableStateOf(14.dp)

    /** --surface-border / --glass-border */
    val border: Color
        get() = if (light) Color(0x1A000000) else Color(0x1AFFFFFF)

    /** Верхняя светлая кромка «парящего» стекла. */
    val highlight: Color
        get() = if (light) Color(0xB3FFFFFF) else Color(0x33FFFFFF)

    /**
     * Мягкая подложка/разделитель под тему: на тёмном стекле — белая, в светлой теме — чёрная.
     * Без этого плитки настроек в светлой теме становились невидимыми (белое на белом).
     */
    fun tint(alpha: Float): Color = if (light) Color.Black.copy(alpha = alpha) else Color.White.copy(alpha = alpha)

    /**
     * Фон приложения — тот же вертикальный градиент, что за списком. Нужен экранам, которые
     * открываются поверх (утреннее ревью на весь экран): иначе композиция «экран в экране»
     * выглядит как всплывающая карточка, а не как отдельный экран.
     */
    fun appBackground(): androidx.compose.ui.graphics.Brush =
        if (light) {
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color(0xFFE8EDF5), Color(0xFFD0D8E8)),
            )
        } else {
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color(0xFF192337), Color(0xFF0A0C12)),
            )
        }

    /** --text-primary / --text-secondary */
    val textPrimary: Color
        get() = if (light) Color(0xFF1A1C2C) else Color.White
    val textSecondary: Color
        get() = if (light) Color(0xFF2C3E66) else Color(0xE6E6EBFF)

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

    /**
     * .rec-cat-chip: в приложении padding 9px 15px, radius 12px, min-height 42px, font 0.9rem.
     * Владелец просил чипы крупнее — размеры увеличены примерно в 1.25 раза (42 → 52 px и т. д.).
     */
    val chipPadH = 20.dp
    val chipPadV = 13.dp
    val chipRadius = 14.dp
    val chipMinHeight = 52.dp
    val chipGap = 10.dp
    val chipFont = 16.5.sp

    /**
     * Свечение кнопки. В приложении это box-shadow (`0 0 calc(10px + 30px*level) accent`), то есть
     * мягкое размытие; владелец сказал, что сплошное пятно по формуле 0.22 + 0.7·уровень слишком яркое,
     * поэтому здесь тише: 0.04 … 0.18.
     */
    const val glowAlphaMin = 0.04f
    const val glowAlphaRange = 0.14f
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
        if (Design.light) {
            // body.light: --bg-start #e8edf5, --bg-end #d0d8e8 — обои в светлой теме не показываем,
            // иначе тёмный текст на фотографии нечитаем.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0xFFE8EDF5), Color(0xFFD0D8E8)))),
            )
        } else if (wallpaper != null && Build.VERSION.SDK_INT >= 31) {
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
                    .background(
                        if (Design.light) {
                            Color.White.copy(alpha = Design.veil * 0.8f)
                        } else {
                            Design.glassRgb.copy(alpha = Design.veil)
                        },
                    ),
        )
    }
}
