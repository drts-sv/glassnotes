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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import ru.gigapisar.audio.AudioRecorder
import java.util.Locale

private const val TAG = "GlasNotesDev"

/** Ограничение 60 кадров в секунду (просьба владельца). */
private const val FRAME_NS = 16_666_667L

/** Сколько отсчётов держим в развёртке: 96 при 60 к/с ≈ 1.6 секунды истории. */
private const val TRACE = 96

/** Высота поля развёртки: владелец просил «побольше по вертикали». */
private val WAVE_HEIGHT = 170.dp

/**
 * Экран записи (этап 2 нативного пути) с развёрткой в духе осциллографа.
 *
 * Как нарисовано (просьба владельца: «рисуй огибающую и смежные ей вторичные огибающие, дающие
 * эффект осциллографа»):
 *  - лента между огибающей и её зеркалом заливается вертикальным градиентом — «послесвечение луча»;
 *  - главная огибающая рисуется линией, у неё есть зеркальная пара ниже центра — так сигнал читается
 *    как развёртка, а не как гистограмма;
 *  - вторичные огибающие идут следом с уменьшением масштаба и прозрачности (0.62 / 0.42 / 0.26
 *    и 0.34 / 0.22 / 0.14) и сдвинуты на пару пикселей вправо: получается затухающий «веер»,
 *    как остаточный след луча;
 *  - отсчёты сглажены скользящим средним (v[i−1] + 2v[i] + v[i+1]) / 4, иначе на 60 Гц видно ступеньки.
 *
 * Значения уровня (шкала, порог, кривая) — подобранные владельцем на живом микрофоне; ползунки
 * оставлены, чтобы менять их на месте, не пересобирая приложение.
 */
@Composable
fun RecordingScreen(autoStart: Boolean = false) {
    val context = LocalContext.current
    val recorder = remember { AudioRecorder() }
    val wave = remember { WaveLevel() }

    var recording by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var raw by remember { mutableFloatStateOf(0f) }
    var version by remember { mutableIntStateOf(0) }
    var seconds by remember { mutableIntStateOf(0) }
    var fps by remember { mutableIntStateOf(0) }
    var rawRms by remember { mutableIntStateOf(0) }
    val trace = remember { ArrayDeque<Float>(TRACE + 2) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    var dbWindow by remember { mutableFloatStateOf(wave.dbWindow) }
    var noiseGate by remember { mutableFloatStateOf(wave.noiseGate) }
    var curve by remember { mutableFloatStateOf(wave.curve) }
    var lastParamLog by remember { mutableStateOf(0L) }

    val askPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) askPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(autoStart, granted) {
        if (autoStart && granted && !recording) {
            recording = recorder.start()
            Log.i(TAG, "запись запущена из интента: %s".format(recording))
        }
    }

    LaunchedEffect(dbWindow, noiseGate, curve) {
        wave.dbWindow = dbWindow
        wave.noiseGate = noiseGate
        wave.curve = curve
        val now = System.currentTimeMillis()
        if (now - lastParamLog > 700) {
            lastParamLog = now
            Log.i(TAG, "НАСТРОЙКИ: " + wave.describe())
        }
    }

    DisposableEffect(Unit) {
        onDispose { recorder.cancel() }
    }

    LaunchedEffect(recording) {
        if (!recording) {
            level = 0f
            return@LaunchedEffect
        }

        wave.reset()
        var frame = 0
        var lastDraw = 0L
        var fpsCount = 0
        var fpsStart = 0L
        trace.clear()

        while (recording) {
            val now = withFrameNanos { it }
            if (lastDraw != 0L && now - lastDraw < FRAME_NS) continue // ограничение 60 кадров/с
            lastDraw = now
            frame++
            fpsCount++
            if (fpsStart == 0L) {
                fpsStart = now
            } else if (now - fpsStart >= 1_000_000_000L) {
                fps = fpsCount
                fpsCount = 0
                fpsStart = now
            }

            rawRms = recorder.rms
            raw = wave.rawFromRms(rawRms)
            val shown = wave.value(raw, now)
            trace.addLast(shown)
            while (trace.size > TRACE) trace.removeFirst()
            level = shown
            version++

            if (frame % 60 == 0) {
                Log.i(
                    TAG,
                    "развёртка: rms %d (сырая %.3f), полоса %.3f, фон %.3f, пик %.3f, отсчётов %d"
                        .format(rawRms, raw, shown, wave.floor, wave.peak, trace.size),
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
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
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(22.dp))

        // Развёртка.
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(WAVE_HEIGHT),
        ) {
            val seen = version
            if (seen < 0) return@Canvas

            val values = trace.toList()
            if (values.size < 3) return@Canvas

            val n = values.size
            val stepX = size.width / (n - 1).toFloat()
            val centerY = size.height / 2f
            val maxAmp = size.height / 2f - 2f
            val accent = Color(0xFF8AB4F8)
            val px = density

            // Сглаживание скользящим средним: без него на 60 Гц видны ступеньки.
            val sm = FloatArray(n) { i ->
                val a = values.getOrElse(i - 1) { values[i] }
                val b = values[i]
                val c = values.getOrElse(i + 1) { values[i] }
                (a + 2f * b + c) / 4f
            }

            fun tracePath(
                scale: Float,
                offsetX: Float,
            ): Path {
                val path = Path()
                for (i in 0 until n) {
                    val x = i * stepX + offsetX
                    val y = centerY - sm[i] * scale * maxAmp
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                return path
            }

            // Лента между огибающей и её зеркалом — «послесвечение».
            val band = Path()
            for (i in 0 until n) {
                val x = i * stepX
                val y = centerY - sm[i] * maxAmp
                if (i == 0) band.moveTo(x, y) else band.lineTo(x, y)
            }
            for (i in n - 1 downTo 0) {
                band.lineTo(i * stepX, centerY + sm[i] * maxAmp)
            }
            band.close()
            drawPath(
                path = band,
                brush =
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                accent.copy(alpha = 0.06f),
                                accent.copy(alpha = 0.26f),
                                accent.copy(alpha = 0.06f),
                            ),
                        startY = 0f,
                        endY = size.height,
                    ),
            )

            // Вторичные огибающие: затухающий «веер» следом за главной.
            val scales = floatArrayOf(0.62f, 0.42f, 0.26f)
            val alphas = floatArrayOf(0.34f, 0.22f, 0.14f)
            for (k in scales.indices) {
                drawPath(
                    path = tracePath(scales[k], (k + 1) * 2.5f * px),
                    color = accent.copy(alpha = alphas[k]),
                    style = Stroke(width = 1.6f * px, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            // Зеркальная пара главной огибающей — сигнал читается как развёртка.
            drawPath(
                path = tracePath(-1f, 0f),
                color = accent.copy(alpha = 0.45f),
                style = Stroke(width = 2.2f * px, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            // Главная огибающая.
            drawPath(
                path = tracePath(1f, 0f),
                color = accent,
                style = Stroke(width = 2.2f * px, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        Spacer(Modifier.height(24.dp))

        Box(contentAlignment = Alignment.Center) {
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
                        .background(if (recording) Color(0xFFE05C5C) else MaterialTheme.colorScheme.primary)
                        .clickable {
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
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Spacer(Modifier.height(22.dp))

        Text(
            text = "rms %d · сырая %.3f · фон %.3f · пик %.3f · полоса %.3f · %d к/с"
                .format(rawRms, raw, wave.floor, wave.peak, level, fps),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(12.dp))

        ParamSlider("Чувствительность (шкала, дБ)", dbWindow, 40f..90f) { dbWindow = it }
        ParamSlider("Порог тишины", noiseGate, 0f..0.30f) { noiseGate = it }
        ParamSlider("Отзывчивость на тихий голос", curve, 0.40f..1.30f) { curve = it }

        Text(
            text = "Развёртка: главная огибающая + зеркало + три вторичных следа (0.62/0.42/0.26).",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 20.dp),
        )
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                fontSize = 12.sp,
            )
            Text(
                text = "%.2f".format(value),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
