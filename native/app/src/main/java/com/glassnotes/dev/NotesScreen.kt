package com.glassnotes.dev

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log

/**
 * Список заметок (начало этапа 3): читаем ту же базу, что пишет экран записи.
 *
 * Пока только чтение и показ — правка, категории и «выполнено» будут следующим шагом.
 * Вид взят из боевого приложения: карточка на полупрозрачной подложке, время серым, важные заметки
 * помечены цветом своей категории.
 */
@Composable
fun NotesScreen(store: NotesStore, accent: androidx.compose.ui.graphics.Color) {
    var notes by remember { mutableStateOf<List<NoteRow>>(emptyList()) }

    // Нужен для отмены напоминаний при «выполнено» и удалении.
    val context = LocalContext.current

    // Пересборка списка после правки: меняем счётчик — LaunchedEffect перечитывает базу.
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(revision) {
        val rows = store.recent(100).mapNotNull { parseNote(it) }
        notes = rows
        // Логируем содержимое: в Compose список снаружи не прочитать, а проверять надо данными.
        Log.i("GlasNotesDev", "список заметок: %d штук".format(rows.size))
        rows.take(10).forEach { row ->
            val category = row.categoryIds.firstOrNull()
            val style =
                if (category != null) {
                    "оттенок %s: заливка %.2f, без рамки".format(category, CATEGORY_TINT_ALPHA)
                } else {
                    "без категории: стекло %.2f, без рамки".format(Design.cardAlpha)
                }
            Log.i("GlasNotesDev", "  #%d %s [%s] %s · %s".format(row.id, row.time, row.status, style, row.text))
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = Design.padSide, end = Design.padSide, top = Design.padTop),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "GlasNote",
                color = Design.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "заметок %d".format(notes.size),
                color = Design.textSecondary.copy(alpha = 0.8f),
                fontSize = 14.sp,
            )
        }

        if (notes.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp)) {
                Text(
                    text = "Заметок пока нет — надиктуйте что-нибудь на экране записи.",
                    color = Design.textSecondary.copy(alpha = 0.8f),
                    fontSize = 15.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(notes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        accent = accent,
                        onToggle = {
                            store.setStatus(note.id, if (note.done) "active" else "done")
                            // Отметили выполненным — напоминание больше не нужно.
                            if (!note.done) Reminders.cancel(context, note.id)
                            revision++
                        },
                        onCategory = { category ->
                            store.setCategory(note.id, category)
                            revision++
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteRow,
    accent: androidx.compose.ui.graphics.Color,
    onToggle: () -> Unit,
    onCategory: (String) -> Unit,
    onDelete: () -> Unit,
) {
    // Меню правки открывается ДОЛГИМ нажатием и рисуется вне карточки — вид карточки не меняется.
    var menuOpen by remember { mutableStateOf(false) }

    // Свайп: вправо — выполнено, влево — удалить (как в боевом приложении). Карточка при этом просто
    // сдвигается, никаких цветных подложек за ней не появляется.
    var offsetX by remember { mutableFloatStateOf(0f) }
    val swipeThreshold = with(LocalDensity.current) { 60.dp.toPx() }
    val view = LocalView.current
    var wasAtEdge by remember { mutableStateOf(false) }
    // Оттенок карточки — по категории заметки. Непрозрачность заливки (0.22) и рамки (0.9)
    // оставлены такими же, как были у «важной» заметки в примере: стиль и прозрачность не меняем,
    // меняется только цвет — он берётся у категории.
    val categoryColor = note.categoryIds.firstOrNull()?.let { CATEGORY_COLORS[it] }
    val tinted = categoryColor != null

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                // Рамок нет намеренно: стиль карточек владелец одобрил без них, менять его нельзя —
                // по его просьбе меняется только цвет заливки (оттенок категории).
                .background(
                    if (tinted) {
                        categoryColor!!.copy(alpha = CATEGORY_TINT_ALPHA)
                    } else {
                        Design.glassRgb.copy(alpha = Design.cardAlpha)
                    },
                ).offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(note.id) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            // Один короткий отклик в начале сдвига. Пошаговой трещотки быть не должно:
                            // владелец сказал «слишком часто брянчит», и системные жесты делают так же.
                            Haptics.tick(view)
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount
                            // Единственный отклик по ходу сдвига — на переходе порога и обратно.
                            val atEdge = kotlin.math.abs(offsetX) > swipeThreshold
                            if (atEdge != wasAtEdge) {
                                wasAtEdge = atEdge
                                Haptics.threshold(view, atEdge)
                                Log.i(
                                    "GlasNotesDev",
                                    "свайп #%d: %.0f px — порог %s".format(
                                        note.id,
                                        offsetX,
                                        if (atEdge) "пройден" else "не пройден",
                                    ),
                                )
                            }
                        },
                        onDragEnd = {
                            val dx = offsetX
                            offsetX = 0f
                            wasAtEdge = false
                            Log.i("GlasNotesDev", "свайп #%d закончен: %.0f px".format(note.id, dx))
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
                        onToggle()
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
            CATEGORY_NAMES.forEach { (id, name) ->
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
            if (note.categoryIds.isNotEmpty()) {
                Text(
                    text = note.categoryIds.joinToString(" · "),
                    color = accent.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                )
            }
            if (note.done) {
                Text(text = "выполнено", color = Design.done, fontSize = 12.sp)
            }
        }
    }
}

/** Названия категорий — те же, что в боевом приложении. */
private val CATEGORY_NAMES =
    listOf(
        "important" to "Важное",
        "work" to "Работа",
        "personal" to "Личное",
        "shopping" to "Покупки",
        "other" to "Другое",
        "dev" to "Разработка",
    )

/** Непрозрачность оттенка — как в примере. */
private const val CATEGORY_TINT_ALPHA = 0.22f

/** Цвета категорий — те же, что в боевом приложении (DEFAULT_CATEGORIES). */
private val CATEGORY_COLORS =
    mapOf(
        "important" to androidx.compose.ui.graphics.Color(0xFFFF3B30),
        "work" to androidx.compose.ui.graphics.Color(0xFF54A0FF),
        "personal" to androidx.compose.ui.graphics.Color(0xFFFF6B81),
        "shopping" to androidx.compose.ui.graphics.Color(0xFFFF9F43),
        "other" to androidx.compose.ui.graphics.Color(0xFF888EA8),
        "dev" to androidx.compose.ui.graphics.Color(0xFF4D6BFE),
    )
