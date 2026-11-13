package com.glassnotes.dev

import androidx.compose.ui.draw.shadow

import androidx.compose.foundation.border

import androidx.compose.foundation.layout.heightIn

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Главный экран — по боевому приложению: карточка заголовка с кольцом прогресса и тремя кнопками
 * (поиск, отбор, настройки), чипы отбора, вкладки «Активные/Выполнено» со счётчиками, поиск и список.
 *
 * Вид карточек заметок не меняется: он одобрен владельцем (стекло, радиус 14, поля 14×12,
 * оттенок по категории, свайпы вправо/влево, меню по долгому нажатию).
 */
@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
fun NotesScreen(
    store: NotesStore,
    accent: Color,
    onRecord: () -> Unit = {},
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    openOnStart: String? = null,
    /** Счётчик тапов по уведомлению ревью: >0 — показать ревью, не глядя на reviewShownFor. */
    reviewSignal: Int = 0,
) {
    var notes by remember { mutableStateOf<List<NoteRow>>(emptyList()) }
    var tab by remember { mutableStateOf("active") }
    // При каждом новом открытии приложения — вкладка «Активные», как бы ни закрыли прошлый раз.
    // Ловим ON_START: это и холодный запуск, и возврат из фона.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_START) tab = "active"
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var dateFilter by remember { mutableStateOf<String?>(null) }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }

    // Тема: "auto" идёт за системой. Перечитываем при открытии/закрытии настроек,
    // потому что там выбирают «светлая / тёмная / как в системе».
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(settingsOpen, systemDark) {
        Design.setTheme(AppSettings.themeMode(store), systemDark)
    }

    val context = LocalContext.current
    // Озвучка заметок системным TTS (заметка #82). Один движок на экран, гасим при выходе.
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    // Отклик в диалоге: LocalView читаем здесь — внутри clickable его вызывать нельзя.
    val screenView = LocalView.current

    // Лист заметки: открывается нажатием на карточку. Меняются текст, дата/время, категория —
    // как в боевом приложении (там это openNoteSheet + openEdit).
    var editNote by remember { mutableStateOf<NoteRow?>(null) }
    // Просмотр заметки (noteSheet боевого): карточка с текстом и кнопками
    // «Изменить»/«Удалить». Редактор открывается только по «Изменить».
    var sheetNote by remember { mutableStateOf<NoteRow?>(null) }
    var editText by remember { mutableStateOf("") }
    // Дата и время — отдельными значениями, как в боевом editDate/editTime: дата без
    // времени = «весь день», время без даты ставит сегодняшнюю дату.
    var editDate by remember { mutableStateOf<LocalDate?>(null) }
    var editTime by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var editCats by remember { mutableStateOf(emptySet<String>()) }
    // #117/#118: «Улучшить текст» через DeepSeek — запрос уходит из редактора, ответ показываем
    // в отдельной панели, чтобы «Применить» не теряло исходный текст.
    var aiOpen by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiSource by remember { mutableStateOf("") }
    var aiReply by remember { mutableStateOf<DeepSeek.Reply?>(null) }
    var aiError by remember { mutableStateOf<String?>(null) }
    // Пункты «что изменено» — отдельный платный запрос, поэтому пусто до нажатия.
    var aiChanges by remember { mutableStateOf("") }
    var aiChangesBusy by remember { mutableStateOf(false) }
    // Цена второго запроса («что изменено») — показываем рядом с пунктами.
    var aiChangesCost by remember { mutableStateOf<DeepSeek.Cost?>(null) }

    /** Заполняет редактор значениями заметки и открывает его. Вызывать и из меню, и из листа. */
    fun openEditor(n: NoteRow) {
        editText = n.text
        editCats = n.categoryIds.toSet()
        val at = n.datetime
        if (at != null && at > 0L) {
            val dt = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
            editDate = dt.toLocalDate()
            editTime = if (n.allDay) null else (dt.hour to dt.minute)
        } else {
            editDate = null
            editTime = null
        }
        editNote = n
    }
    /** «Сегодня»/«Завтра» — дата без часа: показывает так же, как «весь день» в поле. */
    fun dayText(plusDays: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_MONTH, plusDays)
        cal.set(java.util.Calendar.HOUR_OF_DAY, WhenInput.ALL_DAY_HOUR)
        cal.set(java.util.Calendar.MINUTE, 0)
        return WhenInput.display(cal.timeInMillis, allDay = true)
    }

    LaunchedEffect(revision) {
        notes = store.recent(200).mapNotNull { parseNote(it) }
    }

    // Диагностика: сразу открыть лист просмотра или редактор первой заметки, чтобы снять
    // дамп UI и проверить раскладку числами (телефон часто заперт, тапать нечем).
    LaunchedEffect(notes.size, openOnStart) {
        if (notes.isEmpty() || openOnStart == null || sheetNote != null || editNote != null) return@LaunchedEffect
        if (openOnStart == "sheet") sheetNote = notes.first() else openEditor(notes.first())
        Log.i("GlasNotesDev", "диагностика: открыт %s для заметки #%d".format(openOnStart, notes.first().id))
    }

    val shown = NoteFilters.apply(notes, tab, dateFilter, categoryFilter, query)

    // Снимок состояния списка в лог: снаружи интерфейс не читается, а проверять надо данными.
    LaunchedEffect(tab, dateFilter, categoryFilter, query, notes.size) {
        Log.i(
            "GlasNotesDev",
            "список: вкладка %s, отбор %s, категория %s, поиск «%s» → показано %d из %d".format(
                tab,
                dateFilter ?: "нет",
                categoryFilter ?: "нет",
                query,
                shown.size,
                notes.size,
            ),
        )
    }

    if (settingsOpen) {
        SettingsScreen(
            store = store,
            accent = accent,
            onExport = onExport,
            onImport = onImport,
            onClose = {
                settingsOpen = false
                // Плотность стекла могла поменяться — перечитываем заметки, чтобы шапка и карточки
                // перерисовались с новыми значениями.
                revision++
            },
        )
        return
    }

    // Утреннее ревью: один раз в день, после времени из настроек (reviewShownFor, как в боевом).
    var reviewOpen by remember { mutableStateOf(false) }
    // Решаем один раз за запуск: иначе ревью всплывает повторно, пока список догружается.
    var reviewChecked by remember { mutableStateOf(false) }
    // Последний уже обработанный сигнал: без этого после любого изменения списка
    // (сохранил или удалил заметку) эффект перезапускался и ревью всплывало снова.
    var reviewHandled by remember { mutableStateOf(0) }
    LaunchedEffect(reviewSignal, notes.size) {
        if (reviewSignal > reviewHandled) {
            reviewHandled = reviewSignal
            // Тап по уведомлению: показываем ревью даже если сегодня уже смотрели
            // и даже на пустом списке — иначе нажатие на оповещение «ничего не делает».
            reviewOpen = true
            Log.i("GlasNotesDev", "утреннее ревью: открыто из уведомления (сигнал $reviewSignal)")
        } else if (!reviewChecked && notes.isNotEmpty()) {
            reviewChecked = true
            if (AppSettings.reviewDue(store)) {
                reviewOpen = true
                // Отметку ставим сразу при показе: раньше она писалась только по «Начать день»,
                // и закрытое крестиком ревью всплывало после каждой записи (#97).
                store.setKv(AppSettings.REVIEW_SHOWN_FOR, AppSettings.todayKey())
                Log.i("GlasNotesDev", "утреннее ревью: показываем список дел")
            }
        }
    }
    if (reviewOpen) {
        MorningReviewScreen(notes = notes, accent = accent, onClose = {
            reviewOpen = false
            store.setKv(AppSettings.REVIEW_SHOWN_FOR, AppSettings.todayKey())
            Log.i("GlasNotesDev", "утреннее ревью закрыто, до завтра не показываем")
        })
        return
    }

    val editing = editNote
    if (editing != null) {
        val note = editing
        BackHandler(enabled = true) { editNote = null }
        // Окно редактирования — как в боевом приложении (index.html #editOverlay):
        // «📝 Текст», «🏷 Категории», сворачиваемые «📅 Дата» и «⏰ Время» с плитками
        // (Сегодня / Завтра / Послезавтра / 🕐 Выбрать / 🗑 Очистить) и системными пикерами.
        var dateOpen by remember { mutableStateOf(false) }
        var timeOpen by remember { mutableStateOf(false) }
        var datePickerOpen by remember { mutableStateOf(false) }
        var timePickerOpen by remember { mutableStateOf(false) }

        // Раскрытый раздел даты/времени прокручиваем внутрь видимой части: раньше он уходил
        // за нижний край, и до него приходилось скроллить вручную.
        val settingsScroll = rememberScrollState()
        val editScope = rememberCoroutineScope()
        val dateRequester = remember { BringIntoViewRequester() }
        val timeRequester = remember { BringIntoViewRequester() }

        fun timeText(): String = editTime?.let { "%02d:%02d".format(it.first, it.second) } ?: "Не задано"
        fun dateText(): String =
            editDate?.let { d ->
                val today = LocalDate.now()
                when (d) {
                    today -> "Сегодня, " + WhenInput.display(
                        d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), true,
                    )
                    today.plusDays(1) -> "Завтра"
                    today.plusDays(2) -> "Послезавтра"
                    else -> WhenInput.display(
                        d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), true,
                    )
                }
            } ?: "Не задано"

        // Редактор — та же стеклянная панель, что и просмотр: адаптивная высота, блюр подложки,
        // одинаковый вид у всех трёх окон (просьба владельца 12.11.2026).
        GlassPanel(accent = accent, onDismiss = { editNote = null }, maxHeightFraction = 0.9f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "✏️ Редактирование",
                        color = Design.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (aiBusy) "…" else "✨",
                        color = if (aiBusy) Design.textSecondary else accent,
                        fontSize = 18.sp,
                        modifier =
                            Modifier
                                .clickable(enabled = !aiBusy) {
                                    // Ключ живёт в Android Keystore; открытая копия в базе
                                    // могла остаться от версий до 0.106 — стираем после переноса.
                                    val (key, keyMoved) =
                                        ApiKeyStore.takeOver(context, DeepSeek.KEY, store.getKv(DeepSeek.KEY))
                                    if (keyMoved) store.setKv(DeepSeek.KEY, "")
                                    if (key.isEmpty()) {
                                        Ui.toast(context, "Ключ DeepSeek — в настройках")
                                    } else if (editText.isBlank()) {
                                        Ui.toast(context, "Пустая заметка — нечего улучшать")
                                    } else {
                                        Haptics.tick(screenView)
                                        aiSource = editText
                                        aiReply = null
                                        aiError = null
                                        aiChanges = ""
                                        aiChangesCost = null
                                        aiChangesBusy = false
                                        aiOpen = true
                                        aiBusy = true
                                        editScope.launch {
                                            // Сеть — не в главном потоке: иначе «замирает» вся панель.
                                            val outcome =
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    runCatching { DeepSeek.clean(key, editText) }
                                                }
                                            aiBusy = false
                                            outcome.onSuccess { aiReply = it }
                                                .onFailure { aiError = it.message ?: "Не получилось" }
                                        }
                                    }
                                }.padding(6.dp),
                    )
                    Text(
                        text = "✕",
                        color = Design.textSecondary,
                        fontSize = 18.sp,
                        modifier = Modifier.clickable { editNote = null }.padding(6.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))

                Text("📝 Текст", color = Design.textSecondary.copy(alpha = 0.85f), fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                // Поле тянется на всё свободное место: раньше оно было min 64 dp и текст не влезал.
                var textLines by remember { mutableStateOf(1) }
                val fieldHeight = (textLines * 26 + 34).dp.coerceIn(150.dp, 430.dp)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(fieldHeight)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Design.glassRgb.copy(alpha = 0.55f))
                            .border(1.dp, Design.border, RoundedCornerShape(16.dp)),
                ) {
                    BasicTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        onTextLayout = { textLines = it.lineCount.coerceAtLeast(1) },
                        textStyle = TextStyle(color = Design.textPrimary, fontSize = 17.sp, lineHeight = 26.sp),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))

                // Категории, дата и время — вторая зона с весом: обе делят экран, ничего не вылезает.
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // Без weight: панель измеряется по содержимому, вес её распирал.
                            .heightIn(max = 320.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Design.glassRgb.copy(alpha = 0.35f))
                            .border(1.dp, Design.border, RoundedCornerShape(16.dp))
                            .verticalScroll(settingsScroll)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                Text("🏷 Категории", color = Design.textSecondary.copy(alpha = 0.85f), fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Categories.names.forEach { pair ->
                        val id = pair.first
                        val on = id in editCats
                        EditTile(
                            text = pair.second,
                            active = on,
                            bg = Categories.colors[id],
                            accent = accent,
                            onClick = {
                                Haptics.tick(screenView)
                                editCats = if (on) editCats - id else editCats + id
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                // 📅 Дата — сворачиваемый блок: заголовок показывает выбранное значение.
                EditSectionHeader(
                    title = "📅 Дата",
                    value = dateText(),
                    open = dateOpen,
                    modifier = Modifier.bringIntoViewRequester(dateRequester),
                    onClick = {
                        Haptics.tick(screenView)
                        dateOpen = !dateOpen
                        if (dateOpen) editScope.launch { dateRequester.bringIntoView() }
                    },
                )
                if (dateOpen) {
                    // Выбранная дата подсвечивается — раньше чипы не показывали выбор.
                    val today = LocalDate.now()
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EditTile("Сегодня", accent = accent, active = editDate == today) {
                            Haptics.tick(screenView)
                            editDate = today
                        }
                        EditTile("Завтра", accent = accent, active = editDate == today.plusDays(1)) {
                            Haptics.tick(screenView)
                            editDate = today.plusDays(1)
                        }
                        EditTile("Послезавтра", accent = accent, active = editDate == today.plusDays(2)) {
                            Haptics.tick(screenView)
                            editDate = today.plusDays(2)
                        }
                        // «Выбрать» подсвечен, когда дата пришла из календаря, а не из быстрых чипов.
                        EditTile(
                            "📅 Выбрать",
                            accent = accent,
                            active = editDate != null && editDate != today &&
                                editDate != today.plusDays(1) && editDate != today.plusDays(2),
                        ) {
                            Haptics.tick(screenView)
                            datePickerOpen = true
                        }
                        EditTile("🗑 Очистить", danger = true, accent = accent) {
                            Haptics.tick(screenView)
                            editDate = null
                            editTime = null
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // ⏰ Время — как в боевом: шесть готовых времён из настроек + выбор + очистка.
                EditSectionHeader(
                    title = "⏰ Время",
                    value = timeText(),
                    open = timeOpen,
                    modifier = Modifier.bringIntoViewRequester(timeRequester),
                    onClick = {
                        Haptics.tick(screenView)
                        timeOpen = !timeOpen
                        if (timeOpen) editScope.launch { timeRequester.bringIntoView() }
                    },
                )
                if (timeOpen) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DEFAULT_TIMES.forEach { (emoji, hour, minute) ->
                            EditTile(
                                "$emoji %02d:%02d".format(hour, minute),
                                accent = accent,
                                active = editTime == (hour to minute),
                            ) {
                                Haptics.tick(screenView)
                                editTime = hour to minute
                                if (editDate == null) editDate = LocalDate.now()
                            }
                        }
                        EditTile(
                            "🕐 Выбрать",
                            accent = accent,
                            active = editTime != null &&
                                DEFAULT_TIMES.none { it.second == editTime?.first && it.third == editTime?.second },
                        ) {
                            Haptics.tick(screenView)
                            timePickerOpen = true
                        }
                        EditTile("🗑 Очистить", danger = true, accent = accent) {
                            Haptics.tick(screenView)
                            editTime = null
                        }
                    }
                }
                }

                Spacer(Modifier.height(10.dp))

                // Кнопки редактора — один ряд во всю ширину панели: «Отмена» слева, «Сохранить» справа.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(50.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Design.glassRgb.copy(alpha = 0.55f))
                                .border(1.dp, Design.border, RoundedCornerShape(16.dp))
                                .clickable {
                                    Haptics.tick(screenView)
                                    editNote = null
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "Отмена", color = Design.textPrimary, fontSize = 15.sp)
                    }
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(50.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(accent.copy(alpha = 0.92f))
                                .clickable {
                                    Haptics.tick(screenView)
                                    val value = editText.trim()
                                    if (value.isNotEmpty()) {
                                        val date = editDate
                                        val time = editTime
                                        // Дата без времени = «весь день» (09:00, как при диктовке).
                                        val at: Long? =
                                            date?.let { d ->
                                                val h = time?.first ?: WhenInput.ALL_DAY_HOUR
                                                val m = time?.second ?: 0
                                                d.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                            }
                                        val allDay = time == null
                                        store.setText(note.id, value)
                                        store.setCategories(note.id, editCats.toList())
                                        store.setDatetime(note.id, at, allDay)
                                        Reminders.cancel(context, note.id)
                                        if (at != null && at > System.currentTimeMillis()) {
                                            Reminders.schedule(context, note.id, at, value, editCats.firstOrNull(), allDay)
                                        }
                                        revision++
                                        Ui.toast(context, "Заметка сохранена")
                                        Log.i(
                                            "GlasNotesDev",
                                            "заметка #%d изменена: текст %d симв., дата %s, категорий %d".format(
                                                note.id,
                                                value.length,
                                                WhenInput.display(at, allDay).ifEmpty { "нет" },
                                                editCats.size,
                                            ),
                                        )
                                    }
                                    editNote = null
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "Сохранить", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
        }

        // #117/#118: результат DeepSeek — рядом с редактором, с «Применить» и списком правок.
        if (aiOpen) {
            Dialog(onDismissRequest = { if (!aiBusy) aiOpen = false }) {
                GlassPanel(accent = accent, onDismiss = { if (!aiBusy) aiOpen = false }, maxHeightFraction = 0.85f) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "✨ Улучшенный текст",
                            color = Design.textPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "✕",
                            color = Design.textSecondary,
                            fontSize = 18.sp,
                            modifier = Modifier.clickable(enabled = !aiBusy) { aiOpen = false }.padding(6.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        if (aiBusy) {
                            Text(
                                text = "Отправляю в DeepSeek…",
                                color = Design.textSecondary,
                                fontSize = 15.sp,
                            )
                        } else if (aiError != null) {
                            Text(
                                text = aiError.orEmpty(),
                                color = Color(0xFFFF6B6B),
                                fontSize = 15.sp,
                            )
                        } else {
                            val reply = aiReply
                            if (reply != null) {
                                Text("Было", color = Design.textSecondary.copy(alpha = 0.85f), fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(text = aiSource, color = Design.textPrimary.copy(alpha = 0.55f), fontSize = 15.sp)
                                Spacer(Modifier.height(12.dp))
                                Text("Станет", color = Design.textSecondary.copy(alpha = 0.85f), fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(text = reply.text, color = Design.textPrimary, fontSize = 17.sp, lineHeight = 24.sp)
                                // Стоимость показанного запроса: по usage из ответа, тариф flash.
                                reply.cost?.let { cost ->
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Запрос: $cost",
                                        color = Design.textSecondary.copy(alpha = 0.7f),
                                        fontSize = 11.5.sp,
                                    )
                                }
                                if (aiChanges.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = "Что изменено" + (aiChangesCost?.let { " · " + it.money } ?: ""),
                                        color = Design.textSecondary.copy(alpha = 0.85f),
                                        fontSize = 12.sp,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(text = aiChanges, color = Design.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                                } else if (!aiBusy) {
                                    // Отдельный платный запрос: показываем только по просьбе.
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = if (aiChangesBusy) "Спрашиваю…" else "Что изменено?",
                                        color = if (aiChangesBusy) Design.textSecondary.copy(alpha = 0.6f) else accent,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier =
                                            Modifier
                                                .clickable(enabled = !aiChangesBusy) {
                                                    Haptics.tick(screenView)
                                                    val askKey = ApiKeyStore.load(context, DeepSeek.KEY).orEmpty()
                                                    val after = reply.text
                                                    aiChangesBusy = true
                                                    editScope.launch {
                                                        val res =
                                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                                runCatching { DeepSeek.explain(askKey, aiSource, after) }
                                                            }
                                                        aiChangesBusy = false
                                                        val explained = res.getOrNull()
                                                        if (explained == null) {
                                                            aiChanges = res.exceptionOrNull()?.message ?: "Не получилось"
                                                        } else {
                                                            aiChanges = explained.text
                                                            aiChangesCost = explained.cost
                                                        }
                                                    }
                                                }.padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Design.glassRgb.copy(alpha = 0.55f))
                                    .border(1.dp, Design.border, RoundedCornerShape(16.dp))
                                    .clickable { aiOpen = false },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "Закрыть", color = Design.textPrimary, fontSize = 15.sp)
                        }
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(accent)
                                    .clickable {
                                        val reply = aiReply ?: return@clickable
                                        Haptics.confirm(screenView)
                                        editText = reply.text
                                        aiOpen = false
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "Применить", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Системный календарь. DatePicker живёт в UTC, поэтому туда и обратно переводим явно.
        if (datePickerOpen) {
            val picker =
                rememberDatePickerState(
                    initialSelectedDateMillis =
                        (editDate ?: LocalDate.now())
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant()
                            .toEpochMilli(),
                )
            DatePickerDialog(
                onDismissRequest = { datePickerOpen = false },
                confirmButton = {
                    Text(
                        text = "Готово",
                        color = accent,
                        fontSize = 15.sp,
                        modifier =
                            Modifier.clickable {
                                picker.selectedDateMillis?.let { ms ->
                                    editDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                                }
                                datePickerOpen = false
                            }.padding(8.dp),
                    )
                },
                dismissButton = {
                    Text(
                        text = "Отмена",
                        color = Design.textSecondary,
                        fontSize = 15.sp,
                        modifier = Modifier.clickable { datePickerOpen = false }.padding(8.dp),
                    )
                },
            ) {
                DatePicker(state = picker)
            }
        }

        // Системные часы.
        if (timePickerOpen) {
            val now = editTime
            val picker =
                rememberTimePickerState(
                    initialHour = now?.first ?: WhenInput.ALL_DAY_HOUR,
                    initialMinute = now?.second ?: 0,
                    is24Hour = true,
                )
            Dialog(onDismissRequest = { timePickerOpen = false }) {
                Column(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(Design.glassRgb)
                            .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TimePicker(state = picker)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Отмена",
                            color = Design.textSecondary,
                            fontSize = 15.sp,
                            modifier = Modifier.clickable { timePickerOpen = false }.padding(10.dp),
                        )
                        Text(
                            text = "Готово",
                            color = accent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier =
                                Modifier.clickable {
                                    editTime = picker.hour to picker.minute
                                    if (editDate == null) editDate = LocalDate.now()
                                    timePickerOpen = false
                                }.padding(10.dp),
                        )
                    }
                }
            }
        }
    }


    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = Design.padSide, end = Design.padSide, top = Design.padTop),
        ) {
        HeaderCard(
            notes = notes,
            accent = accent,
            filtersOpen = filtersOpen,
            searchOpen = searchOpen,
            dateFilter = dateFilter,
            categoryFilter = categoryFilter,
            tab = tab,
            onToggleFilters = { filtersOpen = !filtersOpen },
            onToggleSearch = { searchOpen = !searchOpen },
            onOpenSettings = { settingsOpen = true },
            onDateFilter = {
                dateFilter = if (dateFilter == it) null else it
                filtersOpen = false
            },
            onCategoryFilter = {
                categoryFilter = if (categoryFilter == it) null else it
                filtersOpen = false
            },
            onClearFilter = {
                dateFilter = null
                categoryFilter = null
            },
            onTab = { tab = it },
        )

        if (searchOpen) {
            SearchField(query = query, onQuery = { query = it })
        }

        Spacer(Modifier.height(8.dp))

        if (shown.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                Text(
                    text =
                        if (notes.isEmpty()) {
                            "Заметок пока нет — надиктуйте что-нибудь на экране записи."
                        } else {
                            "По этому отбору ничего нет."
                        },
                    color = Design.textSecondary.copy(alpha = 0.8f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Нижний отступ больше: под списком стоит кнопка микрофона.
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Раздел «Разработка» — не отдельная вкладка, а последний блок списка «Активные»
                // (просьба владельца 12.11.2026): все заметки категории dev собраны в нём,
                // из обычных разделов по датам они исключены, чтобы не дублироваться.
                val bySection =
                    if (tab == "active") {
                        val dev = shown.filter { NoteFilters.DEVELOPMENT in it.categoryIds }
                        val rest = shown.filter { NoteFilters.DEVELOPMENT !in it.categoryIds }
                        rest.groupBy { NoteFilters.bucket(it) } +
                            (if (dev.isEmpty()) emptyMap() else mapOf(NoteFilters.DEVELOPMENT to dev))
                    } else {
                        shown.groupBy { NoteFilters.bucket(it) }
                    }
                val sectionOrder =
                    if (tab == "active") NoteFilters.sectionOrder + NoteFilters.DEVELOPMENT else NoteFilters.sectionOrder
                sectionOrder.forEach { section ->
                    val group = bySection[section] ?: return@forEach
                    if (group.isEmpty()) return@forEach
                    item(key = "section-" + section) {
                        Text(
                            text =
                                if (section == NoteFilters.DEVELOPMENT) {
                                    "Разработка · " + group.size
                                } else {
                                    NoteFilters.sectionName(section)
                                },
                            color = Design.textSecondary.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                    }
                    items(group, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        accent = accent,
                        onToggle = {
                            store.setStatus(note.id, if (note.done) "active" else "done")
                            if (!note.done) Reminders.cancel(context, note.id)
                            revision++
                            Ui.toast(context, if (note.done) "Снова активна" else "Отмечено выполненной")
                        },
                        onCategory = { category ->
                            store.setCategory(note.id, category)
                            revision++
                        },
                        onOpen = { sheetNote = note },
                        onEdit = { openEditor(note) },
                        holdDoneSec =
                            AppSettings.num(store, AppSettings.HOLD_DONE_SEC, AppSettings.DEFAULT_HOLD_DONE_SEC.toFloat(), 0f..10f),
                        holdDeleteSec =
                            AppSettings.num(store, AppSettings.HOLD_DELETE_SEC, AppSettings.DEFAULT_HOLD_DELETE_SEC.toFloat(), 0f..10f),
                        onDelete = {
                            // Предохранитель — удержание пальца на карточке (holdDeleteSec),
                            // отдельного таймера отмены после удаления нет.
                            store.remove(note.id)
                            Reminders.cancel(context, note.id)
                            revision++
                            Ui.toast(context, "Заметка удалена")
                            Log.i("GlasNotesDev", "удаление #%d".format(note.id))
                        },
                    )
                    }
                }
            }
        }
        }

        // Кнопка записи: по ней открывается окно записи. Положение — из настроек.
        val fabPos = store.getKv("fabPos") ?: "right"
        RecordButton(
            accent = accent,
            onClick = onRecord,
            modifier =
                Modifier
                    .align(
                        when (fabPos) {
                            "left" -> Alignment.BottomStart
                            "center" -> Alignment.BottomCenter
                            else -> Alignment.BottomEnd
                        },
                    ).padding(start = Design.padSide, end = Design.padSide, bottom = 20.dp),
        )

        // Страховка: лист закрылся любым путём — голос замолкает.
        LaunchedEffect(sheetNote, speaker.speaking) {
            if (sheetNote == null && speaker.speaking) speaker.stop()
        }

        // Лист просмотра — карточка поверх списка, как noteSheet боевого приложения.
        sheetNote?.let { note ->
            NoteSheet(
                note = note,
                accent = accent,
                speaking = speaker.speaking,
                onSpeak = {
                    if (speaker.speaking) {
                        speaker.stop()
                    } else {
                        speaker.speak(speakableText(note.text, WhenInput.display(note.datetime, note.allDay)))
                    }
                },
                holdDeleteSec =
                    AppSettings.num(store, AppSettings.HOLD_DELETE_SEC, AppSettings.DEFAULT_HOLD_DELETE_SEC.toFloat(), 0f..10f),
                // Закрытие листа гасит озвучку: раньше TTS продолжал читать после закрытия,
                // и остановить его было нечем (жалоба владельца, #82).
                onClose = {
                    speaker.stop()
                    sheetNote = null
                },
                onEdit = {
                    speaker.stop()
                    sheetNote = null
                    openEditor(note)
                },
                onDelete = {
                    speaker.stop()
                    Haptics.triple(screenView)
                    Reminders.cancel(context, note.id)
                    store.remove(note.id)
                    revision++
                    sheetNote = null
                    Ui.toast(context, "Заметка удалена")
                    Log.i("GlasNotesDev", "заметка #%d удалена из листа просмотра".format(note.id))
                },
            )
        }
    }
}

/**
 * Кнопка записи — как в боевом приложении (`.fab-btn`): стеклянный круг 54 px, иконка 24 px,
 * граница rgba(255,255,255,0.10) и тень 0 8px 32px rgba(0,0,0,0.3) + кольцо 4 px.
 * Положение задаётся настройкой «Кнопка записи»: слева, по центру или справа.
 */
@Composable
private fun RecordButton(
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Box(
        modifier =
            modifier
                .size(46.dp)
                .then(
                    Modifier
                        .androidxShadow()
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Design.glassRgb.copy(alpha = 0.55f))
                        .border(
                            width = 1.dp,
                            color = Design.border,
                            shape = androidx.compose.foundation.shape.CircleShape,
                        ).clickable {
                            Haptics.longPress(view)
                            onClick()
                        },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = "Записать",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Design.textPrimary),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Тень кнопки: отдельная функция, чтобы не возиться с импортами в этом файле. */
private fun Modifier.androidxShadow(): Modifier =
    shadow(
        elevation = 10.dp,
        shape = androidx.compose.foundation.shape.CircleShape,
        ambientColor = Color.Black,
        spotColor = Color.Black,
    )

/**
 * Карточка заголовка — числа сняты с боевого `.header` (index.html), а не на глаз:
 * стекло 0.75 плотности, рамка 1px surface-border, радиус 22, поля 0.15rem/0.6rem,
 * логотип 28 с эмодзи 15 и кольцом 2.5, название 1.6rem/700/-0.3, кнопки 28 с иконками 16,
 * вкладки — нижняя полоса карточки заподлицо с её краями.
 * FlowRow (облако чипов) в Compose пока экспериментальный — отсюда OptIn.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderCard(
    notes: List<NoteRow>,
    accent: Color,
    filtersOpen: Boolean,
    searchOpen: Boolean,
    dateFilter: String?,
    categoryFilter: String?,
    tab: String,
    onToggleFilters: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onDateFilter: (String) -> Unit,
    onCategoryFilter: (String) -> Unit,
    onClearFilter: () -> Unit,
    onTab: (String) -> Unit,
) {
    val active = notes.count { !it.done }
    val done = notes.count { it.done }
    // Надпись применённого отбора: бейджем в строке заголовка, ✕ сбрасывает.
    val filterLabel: String? =
        when {
            dateFilter != null && categoryFilter != null ->
                NoteFilters.sectionName(dateFilter) + " · " + Categories.title(categoryFilter)
            dateFilter != null -> NoteFilters.sectionName(dateFilter)
            categoryFilter != null -> "🏷 " + Categories.title(categoryFilter)
            else -> null
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Design.glassRgb.copy(alpha = Design.cardAlpha * 0.75f))
                .border(1.dp, Design.border, RoundedCornerShape(22.dp)),
    ) {
        // .header-main: поля 0.6rem по бокам, 8px сверху, зазор 7px между элементами.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 9.6.dp, end = 9.6.dp, top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoBadge(done = done, total = notes.size, accent = accent)
            // .title: 1.6rem, 700, межбуквенный -0.3; внутри — счётчик .badge (зазор 10).
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(accent.copy(alpha = 0.2f))
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = active.toString(),
                        color = Design.chipText.copy(alpha = 0.82f),
                        fontSize = 9.6.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (filterLabel != null) {
                    FilterBadge(filterLabel, accent, onClearFilter)
                }
            }
            // Порядок кнопок боевого: фильтр → поиск → настройки.
            HeaderButton(R.drawable.ic_filter, filtersOpen || filterLabel != null, onToggleFilters)
            HeaderButton(R.drawable.ic_search, searchOpen, onToggleSearch)
            HeaderButton(R.drawable.ic_settings, false, onOpenSettings)
        }

        // Чипы отбора появляются только при открытом отборе — как вторая строка шапки.
        if (filtersOpen) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 9.6.dp, end = 9.6.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((id, name) in NoteFilters.dateOptions) {
                    FilterChip(name, id == dateFilter, accent) { onDateFilter(id) }
                }
                for ((id, name) in Categories.names) {
                    // «Важное» — это отдельный отбор «🔥 Важные»; второй чип с тем же смыслом
                    // сбивал с толку (владелец: «зачем в важных два чипа Важные?»).
                    if (id == "important") continue
                    FilterChip("🏷 $name", id == categoryFilter, accent) { onCategoryFilter(id) }
                }
            }
        }

        // .tabs: полоса стекла 0.55 на всю ширину карточки, зазор 1px, низ повторяет радиус 21.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(Design.glassRgb.copy(alpha = 0.55f)),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            TabButton("Активные", active, tab == "active", accent, Modifier.weight(1f)) { onTab("active") }
            TabButton("Выполнено", done, tab == "done", accent, Modifier.weight(1f), doneTab = true) {
                onTab("done")
            }
        }
    }
}

/**
 * .app-logo: круг 28, стекло 0.06, рамка 1px, эмодзи 🎙️ 15 и кольцо прогресса (stroke 2.5).
 * Кольцо показывает долю выполненных дел — как в боевом приложении (updateLogoRing).
 */
@Composable
private fun LogoBadge(
    done: Int,
    total: Int,
    accent: Color,
) {
    val fraction = if (total == 0) 0f else done.toFloat() / total
    Box(
        modifier =
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(50))
                .background(Design.tint(0.06f))
                .border(1.dp, Design.border, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "🎙️", fontSize = 15.sp)
        Canvas(modifier = Modifier.size(28.dp)) {
            val stroke = 2.5.dp.toPx()
            val radius = (size.minDimension - stroke) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = Design.textSecondary.copy(alpha = 0.25f),
                radius = radius,
                center = center,
                style = Stroke(width = stroke),
            )
            if (fraction > 0f) {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** .header-btn: круг 28, стекло 0.06 (выбранный — 0.16), рамка 1px, иконка SVG 16. */
@Composable
private fun HeaderButton(
    icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier =
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(50))
                .background(Design.tint(if (selected) 0.16f else 0.06f))
                .border(1.dp, Design.border, RoundedCornerShape(50))
                .clickable {
                    Haptics.tick(view)
                    onClick()
                },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Design.textPrimary.copy(alpha = 0.9f)),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Бейдж применённого отбора: показывает, что именно отфильтровано; тап — сброс. */
@Composable
private fun FilterBadge(
    label: String,
    accent: Color,
    onClear: () -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(accent.copy(alpha = 0.28f))
                .clickable {
                    Haptics.tick(view)
                    onClear()
                }.padding(start = 7.dp, end = 6.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f, fill = false),
            color = Design.chipText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "✕",
            color = Design.chipText.copy(alpha = 0.85f),
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Text(
        text = label,
        color = if (selected) Design.textPrimary else Design.textSecondary.copy(alpha = 0.8f),
        fontSize = 12.sp,
        modifier =
            Modifier
                .clip(RoundedCornerShape(Design.chipRadius))
                .background(if (selected) accent.copy(alpha = 0.35f) else Design.glassRgb.copy(alpha = 0.5f))
                .clickable {
                    Haptics.tick(view)
                    onClick()
                }.padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun TabButton(
    title: String,
    count: Int,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    doneTab: Boolean = false,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    // .tab: padding 11x4, 0.76rem/600, без своего радиуса (низ карточки задаёт радиус),
    // активная — сплошной акцент, «Выполнено» — зелёный #1f8f4d под белый текст.
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(0.dp))
                .background(
                    when {
                        !selected -> Color.Transparent
                        doneTab -> Color(0xFF1F8F4D)
                        // «Активные» — всегда синим (#108), «Выполнено» — зелёным, независимо от акцента темы.
                        else -> Color(0xFF2E7CF6)
                    },
                )
                .clickable {
                    Haptics.tick(view)
                    onClick()
                }.padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = Design.textPrimary,
            fontSize = 12.16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = count.toString(),
            color = Design.textPrimary.copy(alpha = if (selected) 0.85f else 0.6f),
            fontSize = 11.2.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

/** Поиск — надпись-подсказка как в боевом приложении. */
@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Design.glassRgb.copy(alpha = Design.cardAlpha))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Design.textSecondary.copy(alpha = 0.9f)),
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Поиск по напоминаниям...",
                    color = Design.textSecondary.copy(alpha = 0.6f),
                    fontSize = 15.sp,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = Design.textPrimary, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Image(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Очистить",
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Design.textSecondary.copy(alpha = 0.8f)),
                modifier =
                    Modifier
                        .size(15.dp)
                        .clickable { onQuery("") }
                        .padding(start = 6.dp),
            )
        }
    }
}

/**
 * Карточка заметки — «парящее стекло»: градиент плотности (верхняя грань светлее),
 * светлая кромка в рамке, тень под карточкой и оттенок категории ПОВЕРХ стекла.
 * Раньше цветная карточка была просто заливкой 0.22 без стекла — текст терялся.
 *
 * Свайп: вправо — галочка «выполнено», влево — урна «удалить». Значки лежат под карточкой
 * и проявляются по мере сдвига; отпустили — пружина возвращает карточку.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun NoteCard(
    note: NoteRow,
    accent: Color,
    onToggle: () -> Unit,
    onCategory: (String) -> Unit,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    holdDoneSec: Float = 2f,
    holdDeleteSec: Float = 3f,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // Свайп с УДЕРЖАНИЕМ. Держим палец за порогом: кольцо вокруг значка заполняется.
    // Отпустили раньше времени — карточка возвращается, ничего не происходит.
    // Додержали — тройная вибрация и действие: вправо «выполнено», влево «удалить».
    // Время удержания у выполнения и удаления своё (настройки), иначе удалить можно случайно.
    var holdDir by remember { mutableIntStateOf(0) } // 1 — вправо, -1 — влево, 0 — нет
    var holdTick by remember { mutableIntStateOf(0) } // перезапуск отсчёта
    val holdRing = remember { Animatable(0f) }
    val density = LocalDensity.current
    // Порог срабатывания и предел хода. Порог 60 dp владелец назвал слишком близким к краю:
    // действие начиналось от лёгкого движения. 96 dp — примерно ширина подушечки пальца.
    val swipeThreshold = with(density) { 96.dp.toPx() }
    val swipeMax = with(density) { 120.dp.toPx() }
    val view = LocalView.current
    var wasAtEdge by remember { mutableStateOf(false) }

    // Пальцем тянем мгновенно (snap), после отпускания — пружина назад.
    val offsetX by animateFloatAsState(
        targetValue = dragX,
        animationSpec = if (dragging) snap() else spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "cardOffset",
    )

    // Отсчёт удержания: пока палец за порогом, кольцо заполняется за holdDoneSec / holdDeleteSec.
    LaunchedEffect(holdDir, holdTick) {
        val dir = holdDir
        if (dir == 0) {
            holdRing.snapTo(0f)
            return@LaunchedEffect
        }
        val need = if (dir > 0) holdDoneSec else holdDeleteSec
        if (need <= 0f) {
            holdRing.snapTo(1f)
        } else {
            holdRing.snapTo(0f)
            holdRing.animateTo(1f, tween(durationMillis = (need * 1000).toInt(), easing = LinearEasing))
        }
        if (holdDir == dir) {
            holdDir = 0
            holdTick++
            Haptics.triple(view)
            if (dir > 0) onToggle() else onDelete()
        }
    }

    val categoryColor = note.categoryIds.firstOrNull()?.let { Categories.colors[it] }
    val shape = RoundedCornerShape(14.dp)

    // Парящее стекло: верх плотнее, низ прозрачнее — плитка «играет» на свету.
    val topAlpha = (Design.cardAlpha + 0.08f).coerceIn(0.05f, 0.98f)
    val bottomAlpha = (Design.cardAlpha - 0.12f).coerceIn(0.02f, 0.95f)

    val progressRight = (offsetX / swipeThreshold).coerceIn(0f, 1f)
    val progressLeft = (-offsetX / swipeThreshold).coerceIn(0f, 1f)

    Box(modifier = Modifier.fillMaxWidth()) {
        // Подсказка «выполнено» под карточкой — слева.
        Box(
            modifier = Modifier.matchParentSize().padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (progressRight > 0.02f) {
                // Значок растёт вместе со сдвигом: 26 dp в начале → 40 dp на пороге.
                Box(
                    modifier =
                        Modifier
                            .size((20f + 44f * progressRight).dp)
                            .offset(x = (-6f * progressRight).dp)
                            .clip(CircleShape)
                            .background(Design.done.copy(alpha = 0.18f + 0.22f * progressRight)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = "Выполнено",
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Design.done.copy(alpha = 0.45f + 0.55f * progressRight)),
                        modifier = Modifier.size((10f + 26f * progressRight).dp),
                    )
                    // Кольцо удержания: заполнится — заметка отметится выполненной.
                    if (holdRing.value > 0f) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stroke = 3.dp.toPx()
                            drawArc(
                                color = Design.done,
                                startAngle = -90f,
                                sweepAngle = 360f * holdRing.value,
                                useCenter = false,
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(size.width - stroke, size.height - stroke),
                                style =
                                    androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = stroke,
                                        cap = StrokeCap.Round,
                                    ),
                            )
                        }
                    }
                }
            }
        }
        // Подсказка «удалить» под карточкой — справа.
        Box(
            modifier = Modifier.matchParentSize().padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (progressLeft > 0.02f) {
                val ringSize = (20f + 44f * progressLeft).dp
                Box(
                    modifier =
                        Modifier
                            .size(ringSize)
                            .offset(x = (6f * progressLeft).dp)
                            .clip(CircleShape)
                            .background(Design.stopRed.copy(alpha = 0.18f + 0.22f * progressLeft)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_trash),
                        contentDescription = "Удалить",
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Design.stopRed.copy(alpha = 0.5f + 0.5f * progressLeft)),
                        modifier = Modifier.size((10f + 26f * progressLeft).dp),
                    )
                    // Кольцо удержания вокруг урны: заполнится — тройная вибрация и удаление.
                    if (holdRing.value > 0f) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stroke = 3.dp.toPx()
                            drawArc(
                                color = Design.stopRed,
                                startAngle = -90f,
                                sweepAngle = 360f * holdRing.value,
                                useCenter = false,
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(size.width - stroke, size.height - stroke),
                                style =
                                    androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = stroke,
                                        cap = StrokeCap.Round,
                                    ),
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .shadow(
                        elevation = if (Design.light) 6.dp else 12.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.40f),
                        spotColor = Color.Black.copy(alpha = 0.50f),
                    ).clip(shape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Design.glassRgb.copy(alpha = topAlpha),
                                Design.glassRgb.copy(alpha = bottomAlpha),
                            ),
                        ),
                    ).then(
                        if (categoryColor != null) {
                            // «Разработка» — синий #4D6BFE: при 0.14/0.18 на стекле он почти не
                            // читается (в отличие от красного «Важного»), поэтому у dev плотнее.
                            val catAlpha =
                                if (note.categoryIds.firstOrNull() == "dev") {
                                    if (Design.light) 0.26f else 0.30f
                                } else {
                                    if (Design.light) 0.14f else 0.18f
                                }
                            Modifier.background(categoryColor.copy(alpha = catAlpha))
                        } else {
                            Modifier
                        },
                    ).border(
                        1.dp,
                        Brush.verticalGradient(listOf(Design.highlight, Design.border)),
                        shape,
                    ).pointerInput(note.id) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragging = true
                                Haptics.tick(view)
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                // За порогом карточка почти не идёт: палец «упирается», идёт отсчёт.
                                // Дальше предела карточка не уезжает: значок и так открыт целиком.
                                dragX = (dragX + dragAmount).coerceIn(-swipeMax, swipeMax)
                                val atEdge = abs(dragX) > swipeThreshold
                                if (atEdge != wasAtEdge) {
                                    wasAtEdge = atEdge
                                    Haptics.threshold(view, atEdge)
                                }
                                val dirNow =
                                    when {
                                        dragX > swipeThreshold -> 1
                                        dragX < -swipeThreshold -> -1
                                        else -> 0
                                    }
                                if (dirNow != holdDir) {
                                    holdDir = dirNow
                                    holdTick++
                                }
                            },
                            onDragEnd = {
                                // Отпустили раньше конца отсчёта — просто возвращаем карточку.
                                dragging = false
                                dragX = 0f
                                wasAtEdge = false
                                if (holdDir != 0) {
                                    holdDir = 0
                                    holdTick++
                                }
                            },
                            onDragCancel = {
                                dragging = false
                                dragX = 0f
                                wasAtEdge = false
                                if (holdDir != 0) {
                                    holdDir = 0
                                    holdTick++
                                }
                            },
                        )
                    }.combinedClickable(
                        onClick = {
                            Haptics.tick(view)
                            // Как в боевом приложении: нажатие открывает лист заметки.
                            // Тап — просмотр (как noteSheet боевого); редактор — из меню.
                            onOpen()
                        },
                        onLongClick = {
                            Haptics.longPress(view)
                            menuOpen = true
                        },
                    ).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Копировать текст", fontSize = 15.sp) },
                    onClick = {
                        Haptics.longPress(view)
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(note.text))
                        Ui.toast(context, "Текст скопирован")
                        menuOpen = false
                    },
                )
                MenuDivider()
                Text(
                    text = "Категория",
                    color = Design.textSecondary.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
                )
                Categories.names.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name, fontSize = 15.sp) },
                        onClick = {
                            Haptics.tick(view)
                            onCategory(id)
                            menuOpen = false
                        },
                    )
                }
                MenuDivider()
                DropdownMenuItem(
                    text = { Text(if (note.status == "done") "Вернуть в активные" else "Отметить выполненным", fontSize = 15.sp) },
                    onClick = {
                        Haptics.tick(view)
                        onToggle()
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Изменить текст", fontSize = 15.sp) },
                    onClick = {
                        Haptics.tick(view)
                        onEdit()
                        menuOpen = false
                    },
                )
                MenuDivider()
                DropdownMenuItem(
                    text = { Text("Удалить заметку", fontSize = 15.sp) },
                    onClick = {
                        Haptics.longPress(view)
                        onDelete()
                        menuOpen = false
                    },
                )
            }

            Text(
                text = note.text,
                color = Design.textPrimary.copy(alpha = if (note.done) 0.5f else 1f),
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(6.dp))
            // Бейджи карточки — как в боевом index.html (.result-datetime, .category-label,
            // .status-label, .important-chip): плашки, а не мелкий серый текст.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val whenLabel = whenText(note).ifBlank { note.time }
                if (whenLabel.isNotBlank()) {
                    Badge(
                        text = whenLabel,
                        fg = if (note.done) Design.done else Design.textSecondary,
                        bg = if (note.done) Design.done.copy(alpha = 0.14f) else Design.tint(0.10f),
                        bold = true,
                    )
                }
                note.categoryIds.forEach { id ->
                    // «🔥 важное» рисуется отдельной плотной плашкой ниже — иначе на карточке
                    // висели две плашки про важное («Важное» + «🔥 важное»).
                    if (id == "important" && !note.done) return@forEach
                    Badge(
                        text = Categories.title(id) ?: id,
                        fg = Design.chipText.copy(alpha = if (note.done) 0.75f else 0.95f),
                        bg = (Categories.colors[id] ?: Design.textSecondary).copy(alpha = 0.38f),
                        bold = true,
                    )
                }
                if (note.categoryIds.contains("important") && !note.done) {
                    // .important-chip боевого — плотнее остальных.
                    Badge(text = "🔥 важное", fg = Color.White, bg = Color(0xFFFF6B4A).copy(alpha = 0.72f), bold = true)
                }
                Badge(
                    text = statusLabel(note),
                    fg = if (note.done) Design.done else Design.textSecondary.copy(alpha = 0.9f),
                    bg = Design.tint(0.07f),
                )
            }
        }
    }
}


/**
 * Подпись даты в карточке — как formatDateTimeDisplay в боевом приложении: «Сегодня», «Завтра»,
 * «Послезавтра», иначе «9 нояб.» (с годом, если год другой). У дела на весь день часа нет,
 * поэтому показываем только день; у дела с часом — день и время.
 */
private fun whenText(note: NoteRow): String {
    val at = note.datetime ?: return ""
    fun midnight(ms: Long): Long =
        java.util.Calendar.getInstance().apply {
            timeInMillis = ms
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    val days = ((midnight(at) - midnight(System.currentTimeMillis())) / 86400000L).toInt()
    val label =
        when (days) {
            0 -> "Сегодня"
            1 -> "Завтра"
            2 -> "Послезавтра"
            else -> {
                val sameYear =
                    java.util.Calendar.getInstance().apply { timeInMillis = at }.get(java.util.Calendar.YEAR) ==
                        java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                val fmt = if (sameYear) "d MMM" else "d MMM yyyy"
                java.text.SimpleDateFormat(fmt, java.util.Locale("ru")).format(java.util.Date(at)).replace(".", "")
            }
        }
    if (note.allDay) return label
    return label + " " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(at))
}


/** Плашка бейджа карточки: скругление 8 dp, поля 8×3 dp — размеры из index.html. */
@Composable
private fun Badge(
    text: String,
    fg: Color,
    bg: Color,
    bold: Boolean = false,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(bg)
                .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** Статус словами — как `sl` в renderCard боевого. */
private fun statusLabel(note: NoteRow): String {
    if (note.done) return "✓ выполнено"
    val at = note.datetime
    if (at == null || at <= 0L) return "📝 без даты"
    return if (at < System.currentTimeMillis()) "⏰ просрочено" else "🟢 активное"
}


/** Готовые времена — как DEFAULT_TIME_SETTINGS боевого: эмодзи, часы, минуты. */
private val DEFAULT_TIMES =
    listOf(
        Triple("🌅", 9, 0),
        Triple("☀️", 11, 0),
        Triple("🍽", 13, 0),
        Triple("🌇", 15, 0),
        Triple("🌆", 18, 0),
        Triple("🌙", 21, 0),
    )

/** Заголовок сворачиваемого блока: название, текущее значение, стрелка. */
@Composable
private fun EditSectionHeader(
    title: String,
    value: String,
    open: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onClick() }
                .padding(vertical = 8.dp),
    ) {
        Text(title, color = Design.textSecondary.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            color = Design.textPrimary.copy(alpha = 0.9f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(text = if (open) "▾" else "▸", color = Design.textSecondary, fontSize = 13.sp)
    }
}


/**
 * Чип редактора (категория, дата, время, «Выбрать», «Очистить»).
 * Активный всегда плотный, с белым текстом — в светлой теме прежний полупрозрачный фон
 * и тёмная подпись сливались с панелью (#107).
 */
@Composable
private fun EditTile(
    text: String,
    accent: Color,
    active: Boolean = false,
    danger: Boolean = false,
    bg: Color? = null,
    onClick: () -> Unit,
) {
    val activeBg = (bg ?: accent).copy(alpha = 0.92f)
    val idleBg =
        if (Design.light) Color.White.copy(alpha = 0.75f) else Design.glassRgb.copy(alpha = 0.45f)
    val color: Color =
        when {
            active -> Color.White
            danger -> Color(0xFFFF6B6B)
            else -> Design.textPrimary.copy(alpha = 0.85f)
        }
    val edge: Color =
        when {
            active -> (bg ?: accent).copy(alpha = 0.95f)
            danger -> Color(0xFFFF6B6B).copy(alpha = 0.55f)
            Design.light -> Color(0xFF9AA7BD)
            else -> Design.border
        }
    Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (active) activeBg else idleBg)
                .border(1.dp, edge, RoundedCornerShape(14.dp))
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Тонкий разделитель внутри меню карточки (#116). */
@Composable
private fun MenuDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(1.dp)
                .background(Design.border),
    )
}

/**
 * Кнопка удаления в ряду панели просмотра. Удаление — только удержанием (случайный тап ничего
 * не делает), и пока палец на кнопке, идёт обратный отсчёт «3.0 … 0.0» и полоска прогресса (#104, #114).
 */
@Composable
private fun HoldDeleteButton(
    sec: Float,
    modifier: Modifier = Modifier,
    onFire: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(pressed, sec) {
        if (!pressed) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        val result =
            progress.animateTo(
                1f,
                tween((sec.coerceAtLeast(0.5f) * 1000).toInt(), easing = LinearEasing),
            )
        if (result.endReason == AnimationEndReason.Finished) onFire()
    }
    val left = ((1f - progress.value) * sec).coerceAtLeast(0f)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Design.stopRed.copy(alpha = 0.12f))
                .border(1.dp, Design.stopRed.copy(alpha = 0.40f), RoundedCornerShape(18.dp))
                .pointerInput(sec) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                    )
                },
    ) {
        Text(
            text = if (pressed) "%.1f".format(left) else "Удалить",
            color = Design.stopRed.copy(alpha = 0.95f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        if (progress.value > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                drawLine(
                    color = Design.stopRed.copy(alpha = 0.75f),
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width * progress.value, size.height - stroke / 2f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}


/**
 * Лист просмотра заметки — стеклянная панель **адаптивной высоты** (просьба владельца 12.11.2026):
 * растёт по содержимому, но не на весь экран; подложка размыта системным блюром.
 * Кнопки широкие, в нижней части панели: «🔊 Озвучить», «✏️ Изменить», «Отмена».
 * Удаление — удержанием маленькой кнопки, чтобы не сработало случайно.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteSheet(
    note: NoteRow,
    accent: Color,
    speaking: Boolean,
    onSpeak: () -> Unit,
    holdDeleteSec: Float,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val view = LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    GlassPanel(accent = accent, onDismiss = onClose, maxHeightFraction = 0.86f) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = WhenInput.display(note.datetime, note.allDay).ifEmpty { "Без даты" },
                color = if (note.datetime == null) Design.textSecondary else Design.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            // #101: озвучка — компактной иконкой в заголовке, без текста и широкой кнопки.
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(50))
                        .background(accent.copy(alpha = if (speaking) 0.28f else 0.12f))
                        .clickable {
                            Haptics.tick(view)
                            onSpeak()
                        },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(if (speaking) R.drawable.ic_stop else R.drawable.ic_volume),
                    contentDescription = if (speaking) "Остановить озвучку" else "Озвучить",
                    colorFilter =
                        androidx.compose.ui.graphics.ColorFilter.tint(
                            if (speaking) accent else Design.textPrimary,
                        ),
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Design.tint(0.07f))
                        .clickable {
                            Haptics.tick(view)
                            onClose()
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", color = Design.textSecondary, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(10.dp))

        // Текст прокручивается внутри и имеет потолок: панель растёт по содержимому,
        // но длинная заметка не распирает её на весь экран.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // #116: долгое нажатие на текст — сразу копирует в буфер и даёт вибрацию.
                Text(
                    text = note.text,
                    color = Design.textPrimary.copy(alpha = if (note.done) 0.6f else 1f),
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    modifier =
                        Modifier.pointerInput(note.id, note.text) {
                            detectTapGestures(
                                onLongPress = {
                                    Haptics.longPress(view)
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(note.text))
                                    Ui.toast(context, "Текст скопирован")
                                },
                            )
                        },
                )
                if (note.categoryIds.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        note.categoryIds.forEach { id ->
                            Badge(
                                text = Categories.title(id) ?: id,
                                fg = Design.chipText.copy(alpha = 0.95f),
                                bg = (Categories.colors[id] ?: Design.textSecondary).copy(alpha = 0.38f),
                                bold = true,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // #104: «Изменить», «Удалить» и «Отмена» — в одном ряду, одной высоты.
        // #114: пока держишь «Удалить», на кнопке идёт обратный отсчёт — видно, когда сработает.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("✏️ Изменить", accent, Modifier.weight(1f)) {
                Haptics.tick(view)
                onEdit()
            }
            HoldDeleteButton(sec = holdDeleteSec, modifier = Modifier.weight(1f), onFire = onDelete)
            PanelButton("Отмена", accent, Modifier.weight(1f)) {
                Haptics.tick(view)
                onClose()
            }
        }
    }
}
