package com.glassnotes.dev

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import ru.gigapisar.audio.AudioRecorder
import java.util.Locale

/** Полос 36 — как в проверенной WebView-сборке. */
private const val BARS = 36

private const val TAG = "GlasNotesDev"

/**
 * Экран записи (этап 2 нативного пути).
 *
 * Что здесь важно и почему так:
 *  - кадры берём через `withFrameNanos`, поэтому самодельное ограничение «60 кадров/с» не нужно:
 *    это и был лишний код в прошлой пробной сборке (там выходило «120 кадров/с»);
 *  - уровень читаем из того же `AudioRecorder`/`LevelMeter`, что работают в боевом приложении,
 *    а нормализация — `WaveLevel` (перенос проверенного алгоритма: фон 10-м процентилем за 10 с
 *    плюс автомасштаб по недавнему пику);
 *  - раз в 30 кадров пишем в лог уровень, полосу, фон и пик — это проверяемые числа, а не «на глаз».
 */
@Composable
fun RecordingScreen(autoStart: Boolean = false) {
    val context = LocalContext.current
    val recorder = remember { AudioRecorder() }
    val wave = remember { WaveLevel() }

    var recording by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var version by remember { mutableIntStateOf(0) }
    var seconds by remember { mutableIntStateOf(0) }
    var frame by remember { mutableIntStateOf(0) }
    val bars = remember { ArrayDeque<Float>(BARS + 1) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val askPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) askPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Диагностический вход для проверки без тапа: запись начинается сама, если приложение
    // запущено с extra-полем record (в бою такой вход не используется).
    LaunchedEffect(autoStart, granted) {
        if (autoStart && granted && !recording) {
            recording = recorder.start()
            Log.i(TAG, "запись запущена из интента: %s".format(recording))
        }
    }

    DisposableEffect(Unit) {
        onDispose { recorder.cancel() }
    }

    // Кадры: пока идёт запись, каждый кадр экрана обновляем полосу и уровень свечения кнопки.
    LaunchedEffect(recording) {
        if (!recording) {
            level = 0f
            return@LaunchedEffect
        }

        wave.reset()
        frame = 0
        bars.clear()
        repeat(BARS) { bars.addLast(0f) }

        while (recording) {
            withFrameNanos { }
            frame++
            val shown = wave.value(recorder.level, frame)
            bars.addLast(shown)
            if (bars.size > BARS) bars.removeFirst()
            level = shown
            version++

            if (frame % 30 == 0) {
                Log.i(
                    TAG,
                    "полосы: уровень %.3f, полоса %.3f, фон %.3f, пик %.3f, кадров %d".format(
                        recorder.level,
                        shown,
                        wave.floor,
                        wave.peak,
                        frame,
                    ),
                )
            }
        }
    }

    LaunchedEffect(recording) {
        seconds = 0
        while (recording) {
            delay(1000)
            seconds++
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (recording) "Слушаю…" else "Готов записывать",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 18.sp,
        )

        Text(
            text = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
        )

        // Полосы. Читаем version, чтобы отрисовка обновлялась на каждый кадр.
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(64.dp),
        ) {
            val seen = version
            if (seen < 0) return@Canvas

            val gap = size.width * 0.012f
            val barWidth = ((size.width - gap * (BARS - 1)) / BARS).coerceAtLeast(1f)
            val values = bars.toList()
            val accent = Color(0xFF8AB4F8)

            for (index in 0 until BARS) {
                val value = values.getOrElse(index) { 0f }.coerceIn(0f, 1f)
                val height = (size.height * value).coerceAtLeast(2f)
                val left = index * (barWidth + gap)
                drawRoundRect(
                    color = accent.copy(alpha = 0.35f + 0.65f * value),
                    topLeft = Offset(left, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }

        Box(
            modifier = Modifier.padding(top = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Свечение вокруг кнопки — по текущему уровню, как `--level` в WebView-сборке.
            Box(
                modifier =
                    Modifier
                        .size(132.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    Color(0xFF8AB4F8).copy(alpha = 0.10f + 0.45f * level),
                                    Color.Transparent,
                                ),
                            ),
                        ),
            )

            Box(
                modifier =
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(
                            if (recording) {
                                Color(0xFFE05C5C)
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        ).clickable {
                            if (recording) {
                                recorder.stop()
                                recording = false
                                Log.i(TAG, "запись остановлена на %d с".format(seconds))
                            } else if (granted) {
                                recording = recorder.start()
                                Log.i(TAG, "запись запущена: %s".format(recording))
                            }
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (recording) "Стоп" else "Запись",
                    color = Color(0xFF101114),
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                )
            }
        }

        if (!granted) {
            Text(
                text = "Нужно разрешение на микрофон",
                color = Color(0xFFE0A35C),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}
