package com.glassnotes.dev

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import com.glassnotes.app.SileroVad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.gigapisar.audio.AudioRecorder
import ru.gigapisar.model.ModelManager
import java.util.Locale

private const val TAG = "GlasNotesDev"

/** Ограничение 60 кадров в секунду (просьба владельца). */
private const val FRAME_NS = 16_666_667L

/** Длина развёртки: 44 отсчёта ≈ 0,73 с — «график побыстрее». */
private const val TRACE = 44

private val WAVE_HEIGHT = 170.dp

private data class TraceLayer(
    val shiftSamples: Int,
    val scale: Float,
    val alpha: Float,
    val widthDp: Float,
)

/** Главная огибающая и вторичные следы со сдвигом по фазе. */
private val LAYERS =
    listOf(
        TraceLayer(0, 1.00f, 0.72f, 2.0f),
        TraceLayer(4, 0.96f, 0.20f, 1.5f),
        TraceLayer(8, 0.90f, 0.13f, 1.3f),
        TraceLayer(12, 0.84f, 0.08f, 1.1f),
    )

/** Категории с их цветами (DEFAULT_CATEGORIES боевого приложения). */
private data class Category(val id: String, val name: String, val color: Color)

private val CATEGORIES =
    listOf(
        Category("important", "Важно", Color(0xFFFF3B30)),
        Category("work", "Работа", Color(0xFF54A0FF)),
        Category("personal", "Личное", Color(0xFFFF6B81)),
        Category("shopping", "Покупки", Color(0xFFFF9F43)),
        Category("other", "Другое", Color(0xFF888EA8)),
        Category("dev", "Разработка", Color(0xFF4D6BFE)),
    )

/** Развёртка в зелёно-синих тонах (просьба владельца). */
private val TraceGreen = Color(0xFF34C759)
private val TraceBlue = Color(0xFF54A0FF)

/**
 * Экран записи в дизайне боевого приложения (этап 2 нативного пути).
 *
 * Геометрия взята оттуда же, где её видит владелец: `index.html` и `ThemeBridge.java`. Порядок
 * сверху вниз — как в приложении (замеры 2.61: развёртка 374–438, подпись 448–469, кнопка 496–612,
 * облако категорий 677–770): развёртка → подпись «Слушаю…» → кнопка → облако категорий.
 *
 * Фон — обои с размытием и матированием (`--app-veil` 0.35), акцент — из системной палитры
 * (`system_accent1_*`), чипы 9×15 px с радиусом 12 и высотой 42 px, кнопка 130 px с красной рамкой
 * и свечением `0.22 + 0.7·уровень`.
 *
 * Панель подбора уровня спрятана за строкой «⚙ подбор уровня»: в обычном виде экран выглядит как
 * приложение, а не как стенд.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordingScreen(autoStart: Boolean = false) {
    val context = LocalContext.current
    val recorder = remember { AudioRecorder() }
    val wave = remember { WaveLevel() }
    val accent = remember { systemAccent(context) }

    var recording by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var raw by remember { mutableFloatStateOf(0f) }
    var version by remember { mutableIntStateOf(0) }
    var seconds by remember { mutableIntStateOf(0) }
    var fps by remember { mutableIntStateOf(0) }
    var rawRms by remember { mutableIntStateOf(0) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    /** Распознанные фразы: новые снизу, старые бледнее — тикер под развёрткой. */
    val phrases = remember { mutableStateListOf<String>() }
    var transcriber by remember { mutableStateOf<LiveTranscriber?>(null) }
    var vad by remember { mutableStateOf<SileroVad?>(null) }
    var showDev by remember { mutableStateOf(false) }
    var geometryLogged by remember { mutableStateOf(false) }
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

    // Прогрев распознавания делаем ОДИН раз при открытии экрана: модель тяжёлая (224 МБ),
    // и в боевом приложении она тоже поднимается заранее. Ключ `recording` тут не годится —
    // эффект успевал запуститься дважды и создавал два движка (в логе это два «готово»).
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val detector = vad ?: SileroVad(context).also { vad = it }
                recorder.detector = detector
                val live = transcriber ?: LiveTranscriber(context, ModelManager(context)) { text ->
                    phrases.add(text)
                }.also { transcriber = it }
                recorder.onChunk = { bytes, count -> live.onChunk(bytes, count, recorder.speechProb) }
                Log.i(TAG, "распознавание на ходу готово (VAD доступен: %s)".format(detector.available))
            } catch (t: Throwable) {
                Log.e(TAG, "не удалось поднять распознавание", t)
            }
        }
    }

    // Остановка записи: забрать остаток звука в распознавание.
    LaunchedEffect(recording) {
        if (!recording) transcriber?.finish()
    }

    DisposableEffect(Unit) {
        onDispose {
            transcriber?.close()
            vad?.close()
            recorder.cancel()
        }
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

            if (frame % 120 == 0) {
                Log.i(
                    TAG,
                    "кадры: %d/с, распознаёт: %s, фраз: %d".format(fps, if (transcriber?.busy == true) "да" else "нет", phrases.size),
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

    Box(modifier = Modifier.fillMaxSize()) {
        GlassBackdrop()

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(
                        start = Design.padSide,
                        end = Design.padSide,
                        top = Design.padTop,
                        bottom = Design.padBottom,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Развёртка стоит в верхней трети экрана (просьба владельца): сверху только небольшой отступ.
            Spacer(Modifier.height(18.dp))

            // Развёртка (симметричный контур, вторичные следы со сдвигом по фазе).
            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(WAVE_HEIGHT),
            ) {
                val seen = version
                if (seen < 0) return@Canvas

                val values = trace.toList()
                if (values.size < 8) return@Canvas

                val n = values.size
                val stepX = size.width / (n - 1).toFloat()
                val centerY = size.height / 2f
                val maxAmp = size.height / 2f - 2f
                val px = density

                val sm = FloatArray(n) { i ->
                    val a = values.getOrElse(i - 1) { values[i] }
                    val b = values[i]
                    val c = values.getOrElse(i + 1) { values[i] }
                    (a + 2f * b + c) / 4f
                }

                fun layerPath(
                    shift: Int,
                    sign: Float,
                    scale: Float,
                ): Path {
                    val path = Path()
                    var started = false
                    for (i in 0 until n) {
                        val j = i - shift
                        if (j < 0) continue
                        val x = i * stepX
                        val y = centerY - sign * sm[j] * scale * maxAmp
                        if (!started) {
                            path.moveTo(x, y)
                            started = true
                        } else {
                            path.lineTo(x, y)
                        }
                    }
                    return path
                }

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
                        Brush.horizontalGradient(
                            colors =
                                listOf(
                                    TraceGreen.copy(alpha = 0.05f),
                                    TraceBlue.copy(alpha = 0.11f),
                                ),
                            startX = 0f,
                            endX = size.width,
                        ),
                )

                /** Градиент зелёный → синий с нужной прозрачностью слоя. */
                fun traceBrush(alpha: Float): Brush =
                    Brush.horizontalGradient(
                        colors = listOf(TraceGreen.copy(alpha = alpha), TraceBlue.copy(alpha = alpha)),
                        startX = 0f,
                        endX = size.width,
                    )

                for (layer in LAYERS.reversed()) {
                    val stroke =
                        Stroke(
                            width = layer.widthDp * px,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        )
                    drawPath(
                        path = layerPath(layer.shiftSamples, -1f, layer.scale),
                        brush = traceBrush(layer.alpha),
                        style = stroke,
                    )
                    drawPath(
                        path = layerPath(layer.shiftSamples, 1f, layer.scale),
                        brush = traceBrush(layer.alpha),
                        style = stroke,
                    )
                }
            }

            // Тикер распознанных фраз: новые снизу, старые бледнее — «бегущая строка» под развёрткой.
            if (phrases.isNotEmpty()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(112.dp)
                            .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    val tail = phrases.takeLast(3)
                    tail.forEachIndexed { index, phrase ->
                        val age = (tail.size - 1 - index).toFloat()
                        Text(
                            text = phrase,
                            color = Design.textPrimary.copy(alpha = (0.85f - 0.25f * age).coerceAtLeast(0.25f)),
                            fontSize = 20.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Text(
                text = if (recording) "Слушаю…" else "Готов записывать",
                color = Design.textPrimary,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 10.dp),
            )

            // Кнопка записи: красная полупрозрачная с рамкой и свечением по уровню.
            Box(
                modifier = Modifier.padding(top = 28.dp).size(Design.fab),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Свечение намеренно тихое: в боевом приложении это box-shadow, то есть размытие,
                    // а не сплошное пятно акцента. Прежние 0.22+0.7·уровень владелец назвал слишком яркими.
                    val glowAlpha = Design.glowAlphaMin + Design.glowAlphaRange * level
                    val glowRadius = size.minDimension * 0.62f +
                        (Design.glowRadiusMin + (Design.glowRadiusMax - Design.glowRadiusMin) * level).toPx()
                    drawCircle(
                        brush =
                            Brush.radialGradient(
                                colors =
                                    listOf(
                                        accent.copy(alpha = glowAlpha),
                                        accent.copy(alpha = 0f),
                                    ),
                                center = center,
                                radius = glowRadius,
                            ),
                        radius = glowRadius,
                    )

                    // Кольцо таймера: в боевом приложении смещено на −6px и повёрнуто на −90°.
                    val ringInset = 6.dp.toPx()
                    val ringTopLeft = Offset(ringInset * 0.5f, ringInset * 0.5f)
                    drawArc(
                        color = accent.copy(alpha = 0.85f),
                        startAngle = -90f,
                        sweepAngle = 360f * ((seconds % 60) / 60f),
                        useCenter = false,
                        topLeft = ringTopLeft,
                        size = Size(size.width - ringInset, size.height - ringInset),
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                    )

                    val glyphColor = Design.stopRed
                    if (recording) {
                        val side = 30.dp.toPx()
                        drawRoundRect(
                            color = glyphColor,
                            topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f),
                            size = Size(side, side),
                            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                        )
                    } else {
                        // Микрофон: капсула, дужка и стойка.
                        val capW = 18.dp.toPx()
                        val capH = 32.dp.toPx()
                        val lift = 9.dp.toPx()   // микрофон поднят внутри кнопки (просьба владельца)
                        val capTop = (size.height - capH) / 2f - 4.dp.toPx() - lift
                        drawRoundRect(
                            color = glyphColor,
                            topLeft = Offset((size.width - capW) / 2f, capTop),
                            size = Size(capW, capH),
                            cornerRadius = CornerRadius(capW / 2f, capW / 2f),
                        )
                        drawArc(
                            color = glyphColor,
                            startAngle = 0f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = Offset((size.width - 34.dp.toPx()) / 2f, capTop + capH - 12.dp.toPx()),
                            size = Size(34.dp.toPx(), 30.dp.toPx()),
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                        )
                        drawLine(
                            color = glyphColor,
                            start = Offset(size.width / 2f, capTop + capH + 18.dp.toPx()),
                            end = Offset(size.width / 2f, capTop + capH + 28.dp.toPx()),
                            strokeWidth = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .border(2.dp, Design.stopRed.copy(alpha = 0.4f), CircleShape)
                            .background(Design.stopRed.copy(alpha = 0.15f))
                            .clickable {
                                if (recording) {
                                    recorder.stop()
                                    transcriber?.finish()
                                    recording = false
                                    Log.i(TAG, "запись остановлена на %d с".format(seconds))
                                } else if (granted) {
                                    recording = recorder.start()
                                    Log.i(TAG, "запись запущена: %s".format(recording))
                                }
                            },
                )
            }

            // Облако категорий: переносится по строкам, стоит по центру.
            FlowRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(Design.chipGap, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Design.chipGap),
            ) {
                for (category in CATEGORIES) {
                    val selected = selectedCategory == category.id
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(Design.chipRadius))
                                .background(category.color.copy(alpha = if (selected) 0.45f else 0.24f))
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = category.color.copy(alpha = if (selected) 1f else 0.55f),
                                    shape = RoundedCornerShape(Design.chipRadius),
                                ).clickable {
                                    selectedCategory = if (selected) null else category.id
                                    Log.i(TAG, "категория выбрана: %s".format(selectedCategory ?: "нет"))
                                }.defaultMinSize(minHeight = Design.chipMinHeight)
                                .padding(horizontal = Design.chipPadH, vertical = Design.chipPadV),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = category.name, color = Design.textPrimary, fontSize = Design.chipFont)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = if (showDev) "⚙ подбор уровня — скрыть" else "⚙ подбор уровня",
                color = Design.textSecondary.copy(alpha = 0.55f),
                fontSize = 12.sp,
                modifier = Modifier.clickable { showDev = !showDev },
            )

            if (showDev) {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(
                        text = "rms %d · сырая %.3f · фон %.3f · пик %.3f · полоса %.3f · %d к/с"
                            .format(rawRms, raw, wave.floor, wave.peak, level, fps),
                        color = Design.textSecondary.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    ParamSlider("Чувствительность (шкала, дБ)", dbWindow, accent, 40f..90f) { dbWindow = it }
ParamSlider("Порог тишины", noiseGate, accent, 0f..0.30f) { noiseGate = it }
ParamSlider("Отзывчивость на тихий голос", curve, accent, 0.40f..1.30f) { curve = it }
                    if (!geometryLogged) {
                        LaunchedEffect(Unit) {
                            geometryLogged = true
                            Log.i(
                                TAG,
                                "ГЕОМЕТРИЯ: плотность %.2f, кнопка %.0f px при 130 dp, боковое поле %.0f px, акцент #%06X, обои %s"
                                    .format(
                                        context.resources.displayMetrics.density,
                                        Design.fab.value * context.resources.displayMetrics.density,
                                        Design.padSide.value * context.resources.displayMetrics.density,
                                        accent.toArgb() and 0xFFFFFF,
                                        if (Build.VERSION.SDK_INT >= 31) "размываются" else "без размытия",
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    accent: Color,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                color = Design.textSecondary.copy(alpha = 0.85f),
                fontSize = 12.sp,
            )
            Text(
                text = "%.2f".format(value),
                color = accent,
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

