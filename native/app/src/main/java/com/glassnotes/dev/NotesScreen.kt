package com.glassnotes.dev

import android.util.Log
import androidx.compose.foundation.Canvas
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
    val context = LocalContext.current
    // Отклик в диалоге: LocalView читаем здесь — внутри clickable его вызывать нельзя.
    val screenView = LocalView.current

    // Правка текста заметки: открывается из меню по долгому нажатию.
    var editNote by remember { mutableStateOf<NoteRow?>(null) }
    var editText by remember { mutableStateOf("") }

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
        SettingsScreen(store = store, accent = accent, onClose = { settingsOpen = false })
        return
    }

    editNote?.let { note ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editNote = null },
            title = { Text("Текст заметки", color = Design.textPrimary, fontSize = 16.sp) },
            text = {
                BasicTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    textStyle = TextStyle(color = Design.textPrimary, fontSize = 16.sp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Design.glassRgb.copy(alpha = 0.5f))
                            .padding(10.dp),
                )
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
                            if (value.isNotEmpty() && value != note.text) {
                                store.setText(note.id, value)
                                revision++
                                Log.i("GlasNotesDev", "текст заметки #%d изменён".format(note.id))
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
                            editNote = note
                        },
                        onDelete = {
                            store.remove(note.id)
                            Reminders.cancel(context, note.id)
                            revision++
                        },
                    )
                    }
                }
            }
        }
        }

        // Кнопка записи — в правом нижнем углу: по ней открывается окно записи.
        RecordButton(
            accent = accent,
            onClick = onRecord,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = Design.padSide, bottom = 20.dp),
        )
    }
}

/** Кнопка микрофона: красная, круглая, в правом нижнем углу. */
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
                .size(Design.fab * 0.62f)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Design.stopRed.copy(alpha = 0.9f))
                .clickable {
                    Haptics.longPress(view)
                    onClick()
                },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = "Записать",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
            modifier = Modifier.size(26.dp),
        )
    }
}

/** Карточка заголовка: кольцо прогресса, название, три кнопки, отборы и вкладки.
 *  FlowRow (облако чипов) в Compose пока экспериментальный — отсюда OptIn. */
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
                .clip(RoundedCornerShape(14.dp))
                .background(Design.glassRgb.copy(alpha = Design.cardAlpha))
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoRing(done = done, total = notes.size, accent = accent)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "GlasNote",
                color = Design.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(6.dp))
            // Счётчик активных дел — как значок рядом с названием в боевом приложении.
            Text(
                text = active.toString(),
                color = Design.textSecondary.copy(alpha = 0.8f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            HeaderButton(R.drawable.ic_search, searchOpen, onToggleSearch)
            HeaderButton(R.drawable.ic_filter, filtersOpen, onToggleFilters)
            HeaderButton(R.drawable.ic_settings, false, onOpenSettings)
        }

        // Вкладки и отборы — только когда открыт отбор: в боевом вторая строка шапки свёрнута,
        // поэтому карточка заголовка компактная.
        if (filtersOpen) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
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

        // Как в боевом приложении: две ровные половины на всю ширину, без «таблеток»,
        // поля 11 px и шрифт 0.76rem (12 px) полужирный — поэтому вкладки невысокие.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            TabButton("Активные", active, tab == "active", accent, Modifier.weight(1f)) { onTab("active") }
            TabButton("Выполнено", done, tab == "done", accent, Modifier.weight(1f)) { onTab("done") }
        }
    }
}

/** Кольцо прогресса: доля выполненных дел (как logo-ring в боевом приложении). */
@Composable
private fun LogoRing(
    done: Int,
    total: Int,
    accent: Color,
) {
    val fraction = if (total == 0) 0f else done.toFloat() / total
    Canvas(modifier = Modifier.size(22.dp)) {
        val stroke = 2.6.dp.toPx()
        val inset = stroke / 2
        drawArc(
            color = Design.textSecondary.copy(alpha = 0.35f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (fraction > 0f) {
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

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
                .padding(start = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) Design.textPrimary.copy(alpha = 0.12f) else Color.Transparent)
                .clickable {
                    Haptics.tick(view)
                    onClick()
                }.padding(horizontal = 7.dp, vertical = 6.dp),
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Design.textPrimary.copy(alpha = 0.9f)),
            modifier = Modifier.size(19.dp),
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
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) accent.copy(alpha = Design.cardAlpha) else Design.glassRgb.copy(alpha = 0.4f))
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
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = count.toString(),
            color = if (selected) Design.textPrimary else Design.textSecondary.copy(alpha = 0.8f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
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

/** Карточка заметки — вид одобрен владельцем, не меняется. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteRow,
    accent: Color,
    onToggle: () -> Unit,
    onCategory: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 60.dp.toPx() }
    val view = LocalView.current
    var wasAtEdge by remember { mutableStateOf(false) }

    val categoryColor = note.categoryIds.firstOrNull()?.let { Categories.colors[it] }
    val tinted = categoryColor != null

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (tinted) {
                        categoryColor!!.copy(alpha = 0.22f)
                    } else {
                        Design.glassRgb.copy(alpha = Design.cardAlpha)
                    },
                ).offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(note.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { Haptics.tick(view) },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount
                            val atEdge = abs(offsetX) > swipeThreshold
                            if (atEdge != wasAtEdge) {
                                wasAtEdge = atEdge
                                Haptics.threshold(view, atEdge)
                            }
                        },
                        onDragEnd = {
                            val dx = offsetX
                            offsetX = 0f
                            wasAtEdge = false
                            when {
                                dx > swipeThreshold -> {
                                    Haptics.confirm(view)
                                    onToggle()
                                }

                                dx < -swipeThreshold -> {
                                    Haptics.destructive(view)
                                    onDelete()
                                }
                            }
                        },
                    )
                }.combinedClickable(
                    onClick = {
                        Haptics.tick(view)
                        // Как в боевом приложении: нажатие открывает лист заметки.
                        // «Выполнено» осталось на свайпе вправо и в меню по долгому нажатию.
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
                text = note.time.ifBlank { "—" },
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
