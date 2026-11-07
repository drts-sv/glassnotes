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
import androidx.compose.foundation.layout.width
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
import kotlin.math.max
import kotlin.math.min

/** Полос 36 — как в проверенной WebView-сборке. */
private const val BARS = 36

private const val TAG = "GlasNotesDev"

/** Ограничение 60 кадров в секунду: экран 120 Гц, столько для полос не нужно (просьба владельца). */
private const val FRAME_NS = 16_666_667L

/**
 * Экран записи (этап 2 нативного пути) с подбором уровня руками.
 *
 * Полосы рисуются ОТ ЦЕНТРА, как в боевом приложении (`recDraw`): высота v*(h-6), минимум 3 px,
 * прозрачность 0.3+0.7v, зазор 2 px. Прошлая версия росла от нижнего края — владелец это заметил.
 *
 * Ползунки правят живые параметры [WaveLevel]. Причина, по которой они тут: чувствительность
 * и уровень шума зависят от телефона и комнаты, и на живом микрофоне нашлись две ошибки
 * (фон 0,000 и окно, считавшееся в кадрах). Подобранные числа видны в логе и на экране.
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
    val bars = remember { ArrayDeque<Float>(BARS + 1) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    // Подбираемые параметры
    var dbWindow by remember { mutableFloatStateOf(wave.dbWindow) }
    var floorPercent by remember { mutableFloatStateOf(wave.floorPercent) }
    var noiseGate by remember { mutableFloatStateOf(wave.noiseGate) }
    var curve by remember { mutableFloatStateOf(wave.curve) }
    var peakDecay by remember { mutableFloatStateOf(wave.peakDecayPerSecond) }

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

    // Ползунки → живые параметры. Пишем в лог с оговоркой по частоте: тянуть ползунок можно долго,
    // а нужен один понятный след с итоговыми числами.
    LaunchedEffect(dbWindow, floorPercent, noiseGate, curve, peakDecay) {
        wave.dbWindow = dbWindow
        wave.floorPercent = floorPercent
        wave.noiseGate = noiseGate
        wave.curve = curve
        wave.peakDecayPerSecond = peakDecay
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
        bars.clear()
        repeat(BARS) { bars.addLast(0f) }

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
            bars.addLast(shown)
            if (bars.size > BARS) bars.removeFirst()
            level = shown
            version++

            if (frame % 60 == 0) {
                Log.i(
                    TAG,
                    "полосы: rms %d (сырая %.3f), метр %.3f, полоса %.3f, фон %.3f, пик %.3f, кадров %d"
                        .format(rawRms, raw, recorder.level, shown, wave.floor, wave.peak, frame),
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
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )

        // Полосы — от центра, как в боевом приложении.
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(64.dp),
        ) {
            val seen = version
            if (seen < 0) return@Canvas

            val gap = 2.dp.toPx()
            val barWidth = ((size.width - gap * (BARS - 1)) / BARS).coerceAtLeast(2f)
            val values = bars.toList()
            val accent = Color(0xFF8AB4F8)

            for (index in 0 until BARS) {
                val value = values.getOrElse(index) { 0f }.coerceIn(0f, 1f)
                val height = max(3f, value * (size.height - 6f))
                val left = index * (barWidth + gap)
                drawRoundRect(
                    color = accent.copy(alpha = 0.3f + 0.7f * value),
                    topLeft = Offset(left, (size.height - height) / 2f),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(min(barWidth / 2f, 1.5f * density), min(barWidth / 2f, 1.5f * density)),
                )
            }
        }

        Box(modifier = Modifier.padding(top = 18.dp), contentAlignment = Alignment.Center) {
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

        Spacer(Modifier.height(16.dp))

        Text(
            text = "rms %d · сырая %.3f · фон %.3f · пик %.3f · полоса %.3f · %d к/с"
                .format(rawRms, raw, wave.floor, wave.peak, level, fps),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(10.dp))

        ParamSlider("Чувствительность (шкала, дБ)", dbWindow, 20f..90f) { dbWindow = it }
        ParamSlider("Фон: процентиль", floorPercent, 0.05f..0.70f) { floorPercent = it }
        ParamSlider("Порог тишины", noiseGate, 0f..0.40f) { noiseGate = it }
        ParamSlider("Кривая отображения", curve, 0.40f..1.50f) { curve = it }
        ParamSlider("Затухание пика в секунду", peakDecay, 0.30f..1.00f) { peakDecay = it }

        Text(
            text = "Подобранные числа уходят в лог тегом GlasNotesDev (строка «НАСТРОЙКИ»).",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
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
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
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
