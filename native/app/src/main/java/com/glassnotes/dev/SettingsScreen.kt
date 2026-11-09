package com.glassnotes.dev

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.gigapisar.model.ModelManager
import ru.gigapisar.speech.GigaAmOnnxRecognizer
import kotlin.math.roundToInt

private const val TAG = "GlasNotesDev"

/**
 * Настройки — окно как `#settingsModal` в боевом приложении: центрированная стеклянная карточка
 * (max 420, радиус 40, поля 1.8/1.5rem), заголовок «⚙️ Настройки» с крестиком 36 и разделы-аккордеоны
 * (🎨 Тема и стекло, 🎛 График записи, ⏱ Таймеры, 🏷 Категории, ⏰ Время по умолчанию,
 * 🌅 Утреннее ревью, 🧠 Модель, ↺ Сброс) плюс кнопка «✓ Применить».
 *
 * Ползунки графика записи переехали сюда с экрана записи: там остался только сам график.
 * Значения пишутся в таблицу `kv` в момент отпускания ползунка, поэтому окно можно закрыть
 * крестиком без потери настроек.
 */
@Composable
fun SettingsScreen(
    store: NotesStore,
    accent: Color,
    onClose: () -> Unit,
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current

    var glass by remember {
        mutableFloatStateOf(AppSettings.int(store, AppSettings.GLASS_ALPHA, AppSettings.DEFAULT_GLASS_ALPHA, 15..95).toFloat())
    }
    var veil by remember {
        mutableFloatStateOf(AppSettings.int(store, AppSettings.APP_VEIL, AppSettings.DEFAULT_APP_VEIL, 0..90).toFloat())
    }
    var blur by remember {
        mutableFloatStateOf(AppSettings.int(store, AppSettings.BG_BLUR, AppSettings.DEFAULT_BG_BLUR, 0..40).toFloat())
    }
    // Тема: "auto" — за системой (isSystemInDarkTheme), иначе явный выбор.
    val systemDark = isSystemInDarkTheme()
    var themeMode by remember { mutableStateOf(AppSettings.themeMode(store)) }

    // График записи: числа по умолчанию — из боевого (шкала 65 дБ, порог 0.11, кривая 0.54).
    var dbWindow by remember { mutableFloatStateOf(store.getKv("dbWindow")?.toFloatOrNull() ?: 65f) }
    var noiseGate by remember { mutableFloatStateOf(store.getKv("noiseGate")?.toFloatOrNull() ?: 0.11f) }
    var curve by remember { mutableFloatStateOf(store.getKv("curve")?.toFloatOrNull() ?: 0.54f) }
    var threads by remember { mutableFloatStateOf(store.getKv("threads")?.toFloatOrNull() ?: 1f) }
    var silenceSec by remember { mutableFloatStateOf(store.getKv("silenceSec")?.toFloatOrNull() ?: 3f) }

    var catSheetSec by remember {
        mutableFloatStateOf(AppSettings.int(store, AppSettings.CAT_SHEET_SEC, AppSettings.DEFAULT_CAT_SHEET_SEC, 0..15).toFloat())
    }

    // Удержание свайпа: сколько держать карточку за порогом, чтобы сработало действие.
    var holdDoneSec by remember {
        mutableFloatStateOf(AppSettings.int(store, AppSettings.HOLD_DONE_SEC, AppSettings.DEFAULT_HOLD_DONE_SEC, 0..10).toFloat())
    }
    var holdDeleteSec by remember {
        mutableFloatStateOf(
            AppSettings.int(store, AppSettings.HOLD_DELETE_SEC, AppSettings.DEFAULT_HOLD_DELETE_SEC, 0..10).toFloat(),
        )
    }

    var askCategory by remember { mutableStateOf(AppSettings.flag(store, AppSettings.ASK_CATEGORY, true)) }
    var voiceCategory by remember { mutableStateOf(AppSettings.flag(store, AppSettings.VOICE_CATEGORY, true)) }
    var filler by remember { mutableStateOf(AppSettings.flag(store, AppSettings.FILLER, true)) }

    var fabPos by remember { mutableStateOf(store.getKv("fabPos") ?: "right") }

    var reviewOn by remember { mutableStateOf(AppSettings.flag(store, AppSettings.REVIEW_ENABLED, true)) }
    var reviewTime by remember { mutableStateOf(store.getKv(Reminders.REVIEW_TIME_KEY) ?: Reminders.DEFAULT_REVIEW_TIME) }

    val times = remember { HashMap<String, String>() }
    for (row in AppSettings.timeRows) {
        if (!times.containsKey(row.second)) times[row.second] = AppSettings.periodTime(store, row.second)
    }
    var timeEdit by remember { mutableStateOf<String?>(null) }
    var timeEditText by remember { mutableStateOf("") }

    var openTheme by remember { mutableStateOf(true) }
    var openWave by remember { mutableStateOf(false) }
    var openTimers by remember { mutableStateOf(false) }
    var openCats by remember { mutableStateOf(false) }
    var openTimes by remember { mutableStateOf(false) }
    var openReview by remember { mutableStateOf(false) }
    var openModel by remember { mutableStateOf(false) }
    var openData by remember { mutableStateOf(false) }

    val model =
        remember {
            runCatching {
                val manager = ModelManager(context)
                val installed = manager.isInstalled()
                val sizeMb = if (installed) manager.modelFile.length() / (1024 * 1024) else 0L
                installed to sizeMb
            }.getOrElse { false to 0L }
        }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(16.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .heightIn(max = 700.dp)
                        .clip(RoundedCornerShape(40.dp))
                        .background(Design.glassRgb.copy(alpha = 0.94f))
                        .border(1.dp, Design.border, RoundedCornerShape(40.dp))
                        .padding(start = 24.dp, end = 24.dp, top = 28.8.dp, bottom = 28.8.dp),
            ) {
                // .modal-header: заголовок слева, крестик 36 справа.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 9.6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "⚙️ Настройки", color = Design.textPrimary, fontSize = 17.6.sp, fontWeight = FontWeight.SemiBold)
                    Box(
                        modifier =
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Design.tint(0.06f))
                                .clickable {
                                    Haptics.tick(view)
                                    onClose()
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "✕", color = Design.textSecondary, fontSize = 15.sp)
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    // ---------- 🎨 Тема и стекло ----------
                    SettingsGroup("🎨 Тема и стекло", null, openTheme, { openTheme = !openTheme }) {
                        SliderRow("Плотность стекла", glass, 15f..95f, 15, "%d%%".format(glass.roundToInt()), accent, { glass = it }) {
                            store.setKv(AppSettings.GLASS_ALPHA, glass.roundToInt().toString())
                            Design.cardAlpha = glass / 100f
                        }
                        SliderRow("Затемнение фона", veil, 0f..90f, 17, "%d%%".format(veil.roundToInt()), accent, { veil = it }) {
                            store.setKv(AppSettings.APP_VEIL, veil.roundToInt().toString())
                            Design.veil = veil / 100f
                        }
                        SliderRow("Размытие фона (стекло)", blur, 0f..40f, 19, "%dpx".format(blur.roundToInt()), accent, { blur = it }) {
                            store.setKv(AppSettings.BG_BLUR, blur.roundToInt().toString())
                            Design.backdropBlur = blur.roundToInt().dp
                        }
                        // Светлая/тёмная тема — как в боевом приложении: выбор из трёх плиток,
                        // «как в системе» идёт за настройкой Android.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                "dark" to "🌙 Тёмная",
                                "light" to "☀️ Светлая",
                                "auto" to "🔄 Системная",
                            ).forEach { (mode, label) ->
                                Tile(label, themeMode == mode, accent, Modifier.weight(1f)) {
                                    themeMode = mode
                                    store.setKv(AppSettings.THEME, mode)
                                    Design.setTheme(mode, systemDark)
                                }
                            }
                        }
                    }

                    // ---------- 🎛 График записи (перенесено с экрана записи) ----------
                    SettingsGroup(
                        "🎛 График записи",
                        "%.0f дБ · %.2f".format(dbWindow, noiseGate),
                        openWave,
                        { openWave = !openWave },
                    ) {
                        SliderRow("Чувствительность (шкала, дБ)", dbWindow, 40f..90f, 49, "%.0f".format(dbWindow), accent, { dbWindow = it }) {
                            store.setKv("dbWindow", dbWindow.toString())
                        }
                        SliderRow("Порог тишины", noiseGate, 0f..0.30f, 29, "%.2f".format(noiseGate), accent, { noiseGate = it }) {
                            store.setKv("noiseGate", noiseGate.toString())
                        }
                        SliderRow("Отзывчивость на тихий голос", curve, 0.40f..1.30f, 17, "%.2f".format(curve), accent, { curve = it }) {
                            store.setKv("curve", curve.toString())
                        }
                        SliderRow(
                            "Окончание записи по тишине",
                            silenceSec,
                            0f..10f,
                            9,
                            "%d с".format(silenceSec.roundToInt()),
                            accent,
                            { silenceSec = it },
                        ) {
                            store.setKv("silenceSec", silenceSec.toString())
                        }
                        SliderRow("Потоки распознавания", threads, 1f..7f, 5, "%d".format(threads.roundToInt()), accent, { threads = it }) {
                            store.setKv("threads", threads.roundToInt().toString())
                            GigaAmOnnxRecognizer.intraOpThreads = threads.roundToInt()
                        }
                    }

                    // ---------- ⏱ Таймеры ----------
                    SettingsGroup(
                        "⏱ Таймеры",
                        "держ. %d с вправо · %d с влево".format(holdDoneSec.roundToInt(), holdDeleteSec.roundToInt()),
                        openTimers,
                        { openTimers = !openTimers },
                    ) {
                        // Удержание свайпа: вправо — «выполнено», влево — «удалить». Удаление дольше,
                        // чтобы не снести заметку случайным движением.
                        SliderRow("Удержание свайпа вправо (выполнено)", holdDoneSec, 0f..10f, 19, "%d с".format(holdDoneSec.roundToInt()), accent, { holdDoneSec = it }) {
                            store.setKv(AppSettings.HOLD_DONE_SEC, holdDoneSec.roundToInt().toString())
                        }
                        SliderRow("Удержание свайпа влево (удалить)", holdDeleteSec, 0f..10f, 19, "%d с".format(holdDeleteSec.roundToInt()), accent, { holdDeleteSec = it }) {
                            store.setKv(AppSettings.HOLD_DELETE_SEC, holdDeleteSec.roundToInt().toString())
                        }
                        SliderRow("Таймер сплэша категорий", catSheetSec, 0f..15f, 14, "%d с".format(catSheetSec.roundToInt()), accent, { catSheetSec = it }) {
                            store.setKv(AppSettings.CAT_SHEET_SEC, catSheetSec.roundToInt().toString())
                        }
                    }

                    // ---------- 🏷 Категории ----------
                    SettingsGroup("🏷 Категории", if (voiceCategory) "голосом" else "вручную", openCats, { openCats = !openCats }) {
                        ToggleRow("Спрашивать категорию после записи", askCategory) {
                            askCategory = it
                            AppSettings.setFlag(store, AppSettings.ASK_CATEGORY, it)
                        }
                        ToggleRow("Распознавать категории голосом", voiceCategory) {
                            voiceCategory = it
                            AppSettings.setFlag(store, AppSettings.VOICE_CATEGORY, it)
                            NluOptions.voiceCategory = it
                            Log.i(TAG, "настройки: категория голосом %s".format(if (it) "вкл" else "выкл"))
                        }
                        ToggleRow("Чистить слова-паразиты", filler) {
                            filler = it
                            AppSettings.setFlag(store, AppSettings.FILLER, it)
                            NluOptions.fillerCleanup = it
                        }
                    }

                    // ---------- ⏰ Время по умолчанию ----------
                    SettingsGroup("⏰ Время по умолчанию", null, openTimes, { openTimes = !openTimes }) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Design.tint(0.06f))
                                    .border(1.dp, Design.border, RoundedCornerShape(16.dp)),
                        ) {
                            AppSettings.timeRows.forEachIndexed { index, row ->
                                val label = row.first
                                val key = row.second
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                Haptics.tick(view)
                                                timeEdit = key
                                                timeEditText = times[key] ?: ""
                                            }.padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(text = label, color = Design.textPrimary.copy(alpha = 0.85f), fontSize = 13.6.sp)
                                    Text(text = times[key] ?: "", color = accent, fontSize = 13.6.sp, fontWeight = FontWeight.Medium)
                                }
                                if (index < AppSettings.timeRows.size - 1) {
                                    Box(Modifier.fillMaxWidth().size(1.dp).background(Design.tint(0.03f)))
                                }
                            }
                        }
                    }

                    // ---------- 🌅 Утреннее ревью ----------
                    SettingsGroup("🌅 Утреннее ревью", if (reviewOn) reviewTime else "выключено", openReview, { openReview = !openReview }) {
                        ToggleRow("Показывать дела на день", reviewOn) { on ->
                            reviewOn = on
                            AppSettings.setFlag(store, AppSettings.REVIEW_ENABLED, on)
                            if (on) Reminders.scheduleReview(context) else Reminders.cancelReview(context)
                            Log.i(TAG, "настройки: утреннее ревью %s".format(if (on) "включено" else "выключено"))
                        }
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Design.tint(0.06f))
                                    .border(1.dp, Design.border, RoundedCornerShape(16.dp))
                                    .clickable {
                                        Haptics.tick(view)
                                        timeEdit = "review"
                                        timeEditText = reviewTime
                                    }.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("⏰ Во сколько", color = Design.textPrimary.copy(alpha = 0.85f), fontSize = 13.6.sp)
                            Text(text = reviewTime, color = accent, fontSize = 13.6.sp, fontWeight = FontWeight.Medium)
                        }
                        Text(
                            text = "Утром приходит напоминание, а при первом открытии после этого времени " +
                                "показывается список дел: просроченные, на сегодня и без срока.",
                            color = Design.textSecondary.copy(alpha = 0.7f),
                            fontSize = 12.5.sp,
                            lineHeight = 16.sp,
                        )
                    }

                    // ---------- 🧠 Модель распознавания ----------
                    SettingsGroup("🧠 Модель распознавания", if (model.first) "на месте" else "нет", openModel, { openModel = !openModel }) {
                        val installed = model.first
                        val sizeMb = model.second
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Design.tint(0.06f))
                                    .border(1.dp, Design.border, RoundedCornerShape(16.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(text = "GigaAM v3 e2e", color = Design.textPrimary, fontSize = 13.6.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = if (installed) "установлена · %d МБ".format(sizeMb) else "не установлена",
                                color = Design.textSecondary.copy(alpha = 0.8f),
                                fontSize = 12.5.sp,
                            )
                        }
                        Text(
                            text = "Докачка модели в нативной сборке пока не перенесена — файл кладётся в dev-пакет отдельно.",
                            color = Design.textSecondary.copy(alpha = 0.6f),
                            fontSize = 11.5.sp,
                        )
                    }

                    // ---------- 🧩 Кнопка записи ----------
                    Column {
                        GroupTitle("🧩 Кнопка записи")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            Tile("⇤ Слева", fabPos == "left", accent, Modifier.weight(1f)) {
                                fabPos = "left"
                                store.setKv("fabPos", "left")
                            }
                            Tile("⇔ Центр", fabPos == "center", accent, Modifier.weight(1f)) {
                                fabPos = "center"
                                store.setKv("fabPos", "center")
                            }
                            Tile("Справа ⇥", fabPos == "right", accent, Modifier.weight(1f)) {
                                fabPos = "right"
                                store.setKv("fabPos", "right")
                            }
                        }
                    }

                    // ---------- 💾 Данные ----------
                    SettingsGroup(
                        "💾 Данные",
                        "экспорт и импорт",
                        openData,
                        { openData = !openData },
                    ) {
                        Text(
                            text = "Файл JSON: те же записи, что в базе. Импорт перезаписывает " +
                                "совпадающие заметки и добавляет новые — перед импортом чужих " +
                                "копий лучше сделать экспорт.",
                            color = Design.textSecondary.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(40.dp))
                                        .background(accent.copy(alpha = 0.9f))
                                        .clickable {
                                            Haptics.tick(view)
                                            onExport()
                                        }.padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("⬇ Экспорт", color = Color.White, fontSize = 14.4.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(40.dp))
                                        .background(Design.tint(0.08f))
                                        .border(1.dp, Design.border, RoundedCornerShape(40.dp))
                                        .clickable {
                                            Haptics.tick(view)
                                            onImport()
                                        }.padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("⬆ Импорт", color = Design.textPrimary, fontSize = 14.4.sp)
                            }
                        }
                    }

                    // ---------- ↺ Сброс ----------
                    Column {
                        GroupTitle("↺ Сброс")
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0x0DFF4646))
                                    .border(1.dp, Color(0x33FF4646), RoundedCornerShape(14.dp))
                                    .clickable {
                                        Haptics.destructive(view)
                                        resetAll(store)
                                        AppSettings.applyAll(store)
                                        glass = (Design.cardAlpha * 100).roundToInt().toFloat()
                                        veil = (Design.veil * 100).roundToInt().toFloat()
                                        blur = Design.backdropBlur.value
                                        holdDoneSec = AppSettings.DEFAULT_HOLD_DONE_SEC.toFloat()
                                        holdDeleteSec = AppSettings.DEFAULT_HOLD_DELETE_SEC.toFloat()
                                        catSheetSec = AppSettings.DEFAULT_CAT_SHEET_SEC.toFloat()
                                        dbWindow = 65f
                                        noiseGate = 0.11f
                                        curve = 0.54f
                                        threads = 1f
                                        silenceSec = 3f
                                        askCategory = true
                                        voiceCategory = true
                                        filler = true
                                        fabPos = "right"
                                        reviewOn = true
                                        reviewTime = Reminders.DEFAULT_REVIEW_TIME
                                        NluOptions.voiceCategory = true
                                        NluOptions.fillerCleanup = true
                                        for (row in AppSettings.timeRows) times[row.second] = row.third
                                        Reminders.scheduleReview(context)
                                        Log.i(TAG, "настройки сброшены на значения по умолчанию")
                                    }.padding(horizontal = 14.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "Сбросить все настройки", color = Color(0xFFFF6B6B), fontSize = 14.4.sp)
                        }
                    }

                    // .apply-btn: 14 по вертикали, радиус 60, стекло.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(60.dp))
                                .background(Design.tint(0.06f))
                                .border(1.dp, Design.border, RoundedCornerShape(60.dp))
                                .clickable {
                                    Haptics.confirm(view)
                                    onClose()
                                }.padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "✓ Применить", color = Design.textPrimary, fontSize = 17.6.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Text(
                        text = "Сборка %s".format(BuildConfig.VERSION_NAME),
                        color = Design.textSecondary.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }

    // Правка времени текстом «ЧЧ:ММ».
    timeEdit?.let { key ->
        AlertDialog(
            onDismissRequest = { timeEdit = null },
            title = { Text("Время", color = Design.textPrimary, fontSize = 16.sp) },
            text = {
                Column {
                    BasicTextField(
                        value = timeEditText,
                        onValueChange = { timeEditText = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Design.textPrimary, fontSize = 18.sp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Design.glassRgb.copy(alpha = 0.5f))
                                .padding(12.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("Формат ЧЧ:ММ, например 09:00", color = Design.textSecondary.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            },
            confirmButton = {
                Text(
                    text = "Сохранить",
                    color = accent,
                    fontSize = 15.sp,
                    modifier =
                        Modifier.clickable {
                            val text = timeEditText.trim()
                            if (Regex("^\\d{1,2}:\\d{2}$").matches(text)) {
                                if (key == "review") {
                                    reviewTime = text
                                    store.setKv(Reminders.REVIEW_TIME_KEY, text)
                                    Reminders.scheduleReview(context)
                                } else {
                                    times[key] = text
                                    store.setKv(AppSettings.timeKey(key), text)
                                    TimeDefaults.load(store)
                                }
                                Log.i(TAG, "настройки: время %s = %s".format(key, text))
                                timeEdit = null
                            }
                        }.padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "Отмена",
                    color = Design.textSecondary,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable { timeEdit = null }.padding(8.dp),
                )
            },
            containerColor = Design.glassRgb.copy(alpha = 0.98f),
        )
    }
}

/** Сброс настроек: ключи возвращаются к значениям по умолчанию (заметки не трогаем). */
private fun resetAll(store: NotesStore) {
    val keys =
        mutableListOf(
            AppSettings.GLASS_ALPHA, AppSettings.APP_VEIL, AppSettings.BG_BLUR,
            AppSettings.HOLD_DONE_SEC, AppSettings.HOLD_DELETE_SEC, AppSettings.CAT_SHEET_SEC,
            AppSettings.ASK_CATEGORY, AppSettings.VOICE_CATEGORY, AppSettings.FILLER,
            AppSettings.REVIEW_ENABLED, Reminders.REVIEW_TIME_KEY,
            "dbWindow", "noiseGate", "curve", "threads", "silenceSec", "fabPos",
        )
    for (row in AppSettings.timeRows) keys.add(AppSettings.timeKey(row.second))
    for (key in keys) store.setKv(key, "")
    store.setKv(AppSettings.GLASS_ALPHA, AppSettings.DEFAULT_GLASS_ALPHA.toString())
    store.setKv(AppSettings.APP_VEIL, AppSettings.DEFAULT_APP_VEIL.toString())
    store.setKv(AppSettings.BG_BLUR, AppSettings.DEFAULT_BG_BLUR.toString())
    store.setKv(AppSettings.HOLD_DONE_SEC, AppSettings.DEFAULT_HOLD_DONE_SEC.toString())
    store.setKv(AppSettings.HOLD_DELETE_SEC, AppSettings.DEFAULT_HOLD_DELETE_SEC.toString())
    store.setKv(AppSettings.CAT_SHEET_SEC, AppSettings.DEFAULT_CAT_SHEET_SEC.toString())
    store.setKv(Reminders.REVIEW_TIME_KEY, Reminders.DEFAULT_REVIEW_TIME)
}

/** Заголовок группы — .group-title: 0.7rem, капсом, белый 0.3. */
@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text.uppercase(),
        color = Color.White.copy(alpha = 0.3f),
        fontSize = 11.2.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** Раздел настроек: заголовок-аккордеон с текущим значением справа и шевроном. */
@Composable
private fun SettingsGroup(
    title: String,
    value: String?,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Design.tint(0.06f))
                    .border(1.dp, Design.border, RoundedCornerShape(14.dp))
                    .clickable {
                        Haptics.tick(view)
                        onToggle()
                    }.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    Text(text = value, color = Design.textSecondary, fontSize = 12.5.sp)
                    Spacer(Modifier.size(6.dp))
                }
                Text(text = if (open) "▾" else "▸", color = Design.textSecondary.copy(alpha = 0.7f), fontSize = 13.sp)
            }
        }
        if (open) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

/** .slider-card: плитка стекла 0.06, радиус 16, поля 12×14; подпись, значение, ползунок. */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    text: String,
    accent: Color,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Design.tint(0.06f))
                .border(1.dp, Design.border, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = Design.textPrimary.copy(alpha = 0.85f),
            fontSize = 13.6.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value,
                onValueChange = onChange,
                onValueChangeFinished = onFinished,
                valueRange = range,
                steps = steps,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = text,
                color = accent,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
}

/** Переключатель с подписью. */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Design.tint(0.06f))
                .border(1.dp, Design.border, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Design.textPrimary.copy(alpha = 0.85f),
            fontSize = 13.2.sp,
            modifier = Modifier.weight(1f).padding(end = 10.dp),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Плитка выбора (как `.tile`): радиус 16, стекло; выбранная — акцент 12 %. */
@Composable
private fun Tile(
    label: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (active) accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.06f))
                .border(
                    1.dp,
                    if (active) accent.copy(alpha = 0.25f) else Design.border,
                    RoundedCornerShape(16.dp),
                ).clickable {
                    Haptics.tick(view)
                    onClick()
                }.padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) Design.textPrimary else Design.textSecondary.copy(alpha = 0.6f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}
