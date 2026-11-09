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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NotesScreen(
    store: NotesStore,
    accent: Color,
    onRecord: () -> Unit = {},
) {
    var notes by remember { mutableStateOf<List<NoteRow>>(emptyList()) }
    var tab by remember { mutableStateOf("active") }
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

    // Удаление с отменой (showUndoFab в боевом): заметка исчезает сразу, а её JSON
    // держим в памяти, пока идёт таймер undoTimerSec из настроек. 0 с — без плашки.
    var undo by remember { mutableStateOf<Deleted?>(null) }
    var undoTick by remember { mutableIntStateOf(0) }
    // Сколько секунд дано на отмену — ровно то, что стоит ползунком в настройках (undoTimerSec).
    var undoSeconds by remember { mutableIntStateOf(AppSettings.DEFAULT_UNDO_SEC) }
    // Кольцо-таймер на плашке: 1 → 0 за undoSeconds. Один отсчёт и гасит плашку, и рисует кольцо.
    val undoProgress = remember { Animatable(0f) }
    LaunchedEffect(undoTick) {
        val pending = undo ?: return@LaunchedEffect
        undoProgress.snapTo(1f)
        undoProgress.animateTo(0f, tween(durationMillis = undoSeconds * 1000, easing = LinearEasing))
        if (undo == pending) undo = null
    }
    val context = LocalContext.current
    // Отклик в диалоге: LocalView читаем здесь — внутри clickable его вызывать нельзя.
    val screenView = LocalView.current

    // Лист заметки: открывается нажатием на карточку. Меняются текст, дата/время, категория —
    // как в боевом приложении (там это openNoteSheet + openEdit).
    var editNote by remember { mutableStateOf<NoteRow?>(null) }
    var editText by remember { mutableStateOf("") }
    var editWhen by remember { mutableStateOf("") }
    var editCats by remember { mutableStateOf(emptySet<String>()) }
    val whenFormat = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.US)

    /** «Сегодня»/«Завтра» в том же формате, что поле даты. */
    fun dayText(plusDays: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_MONTH, plusDays)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 9)
        cal.set(java.util.Calendar.MINUTE, 0)
        return whenFormat.format(cal.time)
    }

    LaunchedEffect(revision) {
        notes = store.recent(200).mapNotNull { parseNote(it) }
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
        SettingsScreen(store = store, accent = accent, onClose = {
            settingsOpen = false
            // Плотность стекла могла поменяться — перечитываем заметки, чтобы шапка и карточки
            // перерисовались с новыми значениями.
            revision++
        })
        return
    }

    // Утреннее ревью: один раз в день, после времени из настроек (reviewShownFor, как в боевом).
    var reviewOpen by remember { mutableStateOf(false) }
    // Решаем один раз за запуск: иначе ревью всплывает повторно, пока список догружается.
    var reviewChecked by remember { mutableStateOf(false) }
    LaunchedEffect(notes.size) {
        if (!reviewChecked && notes.isNotEmpty()) {
            reviewChecked = true
            if (AppSettings.reviewDue(store)) {
                reviewOpen = true
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

    editNote?.let { note ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editNote = null },
            title = { Text("Текст заметки", color = Design.textPrimary, fontSize = 16.sp) },
            text = {
                Column {
                    Text("Когда", color = Design.textSecondary.copy(alpha = 0.8f), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    BasicTextField(
                        value = editWhen,
                        onValueChange = { editWhen = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Design.textPrimary, fontSize = 15.sp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Design.glassRgb.copy(alpha = 0.5f))
                                .padding(10.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Сегодня" to 0, "Завтра" to 1).forEach { (title, days) ->
                            Text(
                                text = title,
                                color = accent.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Design.glassRgb.copy(alpha = 0.45f))
                                        .clickable {
                                            Haptics.tick(screenView)
                                            editWhen = dayText(days)
                                        }.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        Text(
                            text = "Без даты",
                            color = Design.textSecondary.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Design.glassRgb.copy(alpha = 0.45f))
                                    .clickable {
                                        Haptics.tick(screenView)
                                        editWhen = ""
                                    }.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("Текст", color = Design.textSecondary.copy(alpha = 0.8f), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    BasicTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        textStyle = TextStyle(color = Design.textPrimary, fontSize = 16.sp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Design.glassRgb.copy(alpha = 0.5f))
                                .padding(10.dp),
                    )

                    Spacer(Modifier.height(10.dp))
                    Text("Категория", color = Design.textSecondary.copy(alpha = 0.8f), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Categories.names.forEach { pair ->
                            val id = pair.first
                            val on = id in editCats
                            Text(
                                text = pair.second,
                                color = Design.textPrimary.copy(alpha = if (on) 0.98f else 0.8f),
                                fontSize = 12.sp,
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            (Categories.colors[id] ?: Design.glassRgb)
                                                .copy(alpha = if (on) 0.45f else 0.14f),
                                        ).clickable {
                                            Haptics.tick(screenView)
                                            editCats = if (on) editCats - id else editCats + id
                                        }.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Text(
                    text = "Сохранить",
                    color = accent,
                    fontSize = 15.sp,
                    modifier =
                        Modifier.clickable {
                            Haptics.tick(screenView)
                            val value = editText.trim()
                            if (value.isNotEmpty()) {
                                // Дата: сначала точный формат, затем разбор естественной речи.
                                // parseDateNlu().datetime — это Long (миллисекунды), не Calendar.
                                val whenText = editWhen.trim()
                                var at: Long? = null
                                var allDay = false
                                if (whenText.isNotEmpty()) {
                                    val parsed =
                                        runCatching { whenFormat.parse(whenText)?.time }.getOrNull()
                                            ?: parseDateNlu(whenText).datetime
                                    if (parsed != null) {
                                        at = parsed
                                        val c = java.util.Calendar.getInstance()
                                        c.timeInMillis = parsed
                                        allDay =
                                            c.get(java.util.Calendar.HOUR_OF_DAY) == 0 &&
                                                c.get(java.util.Calendar.MINUTE) == 0
                                    }
                                }
                                store.setText(note.id, value)
                                store.setCategories(note.id, editCats.toList())
                                store.setDatetime(note.id, at, allDay)
                                Reminders.cancel(context, note.id)
                                if (at != null && at > System.currentTimeMillis()) {
                                    Reminders.schedule(context, note.id, at, value, editCats.firstOrNull(), allDay)
                                }
                                revision++
                                Log.i(
                                    "GlasNotesDev",
                                    "заметка #%d изменена: текст %d симв., дата %s, категорий %d".format(
                                        note.id,
                                        value.length,
                                        at?.let { whenFormat.format(java.util.Date(it)) } ?: "нет",
                                        editCats.size,
                                    ),
                                )
                            }
                            editNote = null
                        }.padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "Отмена",
                    color = Design.textSecondary.copy(alpha = 0.85f),
                    fontSize = 15.sp,
                    modifier =
                        Modifier.clickable {
                            Haptics.tick(screenView)
                            editNote = null
                        }.padding(8.dp),
                )
            },
        )
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
            onDateFilter = { dateFilter = if (dateFilter == it) null else it },
            onCategoryFilter = { categoryFilter = if (categoryFilter == it) null else it },
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
                val bySection = shown.groupBy { NoteFilters.bucket(it) }
                NoteFilters.sectionOrder.forEach { section ->
                    val group = bySection[section] ?: return@forEach
                    if (group.isEmpty()) return@forEach
                    item(key = "section-" + section) {
                        Text(
                            text = NoteFilters.sectionName(section),
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
                        },
                        onCategory = { category ->
                            store.setCategory(note.id, category)
                            revision++
                        },
                        onEdit = {
                            editText = note.text
                            editCats = note.categoryIds.toSet()
                            editWhen = note.datetime?.let { whenFormat.format(java.util.Date(it)) } ?: ""
                            editNote = note
                        },
                        holdDoneSec =
                            AppSettings.int(store, AppSettings.HOLD_DONE_SEC, AppSettings.DEFAULT_HOLD_DONE_SEC, 0..10),
                        holdDeleteSec =
                            AppSettings.int(store, AppSettings.HOLD_DELETE_SEC, AppSettings.DEFAULT_HOLD_DELETE_SEC, 0..10),
                        onDelete = {
                            val row = store.rawRow(note.id)
                            store.remove(note.id)
                            Reminders.cancel(context, note.id)
                            revision++
                            val seconds =
                                AppSettings.int(store, AppSettings.UNDO_SEC, AppSettings.DEFAULT_UNDO_SEC, 0..15)
                            if (seconds > 0 && row != null) {
                                undoSeconds = seconds
                                undo = Deleted(note.id, row.first, row.second)
                                undoTick++
                                Log.i("GlasNotesDev", "удаление #%d: отмена %d с".format(note.id, seconds))
                            }
                        },
                    )
                    }
                }
            }
        }
        }

        // Плашка «Отменить» над кнопкой записи — как showUndoFab в боевом приложении.
        undo?.let { pending ->
            val pillShape = RoundedCornerShape(14.dp)
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 92.dp)
                        .shadow(
                            elevation = 10.dp,
                            shape = pillShape,
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.40f),
                            spotColor = Color.Black.copy(alpha = 0.45f),
                        ).clip(pillShape)
                        .background(Design.glassRgb.copy(alpha = 0.97f))
                        .border(1.dp, Design.border, pillShape)
                        .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Кольцо обратного отсчёта: сколько осталось до окончательного удаления.
                Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 2.5.dp.toPx()
                        val ring = Size(size.width - stroke, size.height - stroke)
                        drawArc(
                            color = Design.border,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = ring,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                        )
                        drawArc(
                            color = accent,
                            startAngle = -90f,
                            sweepAngle = 360f * undoProgress.value,
                            useCenter = false,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = ring,
                            style =
                                androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = stroke,
                                    cap = StrokeCap.Round,
                                ),
                        )
                    }
                    Text(
                        text = "%d".format(kotlin.math.ceil(undoProgress.value * undoSeconds).toInt().coerceIn(0, undoSeconds)),
                        color = Design.textSecondary,
                        fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(text = "Заметка удалена", color = Design.textSecondary, fontSize = 14.sp)
                Text(
                    text = "Отменить",
                    color = accent,
                    fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                Haptics.confirm(screenView)
                                store.restore(pending.id, pending.json, pending.ts)
                                undo = null
                                revision++
                            }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
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
                .size(54.dp)
                .then(
                    Modifier
                        .androidxShadow()
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Design.glassRgb.copy(alpha = 0.55f))
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.10f),
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
            modifier = Modifier.size(24.dp),
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
    onTab: (String) -> Unit,
) {
    val active = notes.count { !it.done }
    val done = notes.count { it.done }

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
                    .padding(start = 9.6.dp, end = 9.6.dp, top = 10.4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoBadge(done = done, total = notes.size, accent = accent)
            // .title: 1.6rem, 700, межбуквенный -0.3; внутри — счётчик .badge (зазор 10).
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "GlasNote",
                    color = Design.textPrimary,
                    fontSize = 25.6.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(accent.copy(alpha = 0.2f))
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = active.toString(),
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 9.6.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            // Порядок кнопок боевого: фильтр → поиск → настройки.
            HeaderButton(R.drawable.ic_filter, filtersOpen, onToggleFilters)
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
                    FilterChip("🏷 $name", id == categoryFilter, accent) { onCategoryFilter(id) }
                }
            }
        }

        // .tabs: полоса стекла 0.55 на всю ширину карточки, зазор 1px, низ повторяет радиус 21.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
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
                        else -> accent
                    },
                )
                .clickable {
                    Haptics.tick(view)
                    onClick()
                }.padding(horizontal = 4.dp, vertical = 11.dp),
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

/** Удалённая заметка, которую ещё можно вернуть: id, JSON и метка времени — как были. */
private data class Deleted(
    val id: Long,
    val json: String,
    val ts: Long,
)

/**
 * Карточка заметки — «парящее стекло»: градиент плотности (верхняя грань светлее),
 * светлая кромка в рамке, тень под карточкой и оттенок категории ПОВЕРХ стекла.
 * Раньше цветная карточка была просто заливкой 0.22 без стекла — текст терялся.
 *
 * Свайп: вправо — галочка «выполнено», влево — урна «удалить». Значки лежат под карточкой
 * и проявляются по мере сдвига; отпустили — пружина возвращает карточку.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteRow,
    accent: Color,
    onToggle: () -> Unit,
    onCategory: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    holdDoneSec: Int = 2,
    holdDeleteSec: Int = 3,
) {
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
    val swipeThreshold = with(density) { 60.dp.toPx() }
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
        if (need <= 0) {
            holdRing.snapTo(1f)
        } else {
            holdRing.snapTo(0f)
            holdRing.animateTo(1f, tween(durationMillis = need * 1000, easing = LinearEasing))
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
                            Modifier.background(categoryColor.copy(alpha = if (Design.light) 0.14f else 0.18f))
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
                                dragX += dragAmount
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
                            // «Выполнено» — удержание на свайпе вправо и пункт меню.
                            onEdit()
                        },
                        onLongClick = {
                            Haptics.longPress(view)
                            menuOpen = true
                        },
                    ).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = whenText(note).ifBlank { note.time.ifBlank { "—" } },
                    color = Design.textSecondary.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    note.categoryIds.forEach { id ->
                        Row(
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Categories.colors[id]?.copy(alpha = 0.35f) ?: Design.glassRgb.copy(alpha = 0.35f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = Categories.title(id) ?: id,
                                color = Design.textPrimary.copy(alpha = 0.92f),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                if (note.done) {
                    Text(text = "выполнено", color = Design.done, fontSize = 12.sp)
                }
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
