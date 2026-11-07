package com.glassnotes.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

    LaunchedEffect(Unit) {
        val rows = store.recent(100).mapNotNull { parseNote(it) }
        notes = rows
        // Логируем содержимое: в Compose список снаружи не прочитать, а проверять надо данными.
        Log.i("GlasNotesDev", "список заметок: %d штук".format(rows.size))
        rows.take(10).forEach { row ->
            val category = row.categoryIds.firstOrNull()
            val style =
                if (category != null) {
                    "оттенок %s: заливка %.2f, рамка %.1f".format(category, CATEGORY_TINT_ALPHA, CATEGORY_BORDER_ALPHA)
                } else {
                    "без категории: стекло %.2f, рамка 1 dp".format(Design.cardAlpha)
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
                    NoteCard(note, accent)
                }
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: NoteRow,
    accent: androidx.compose.ui.graphics.Color,
) {
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
                .background(
                    if (tinted) {
                        categoryColor!!.copy(alpha = CATEGORY_TINT_ALPHA)
                    } else {
                        Design.glassRgb.copy(alpha = Design.cardAlpha)
                    },
                ).border(
                    width = if (tinted) 2.dp else 1.dp,
                    color = if (tinted) categoryColor!!.copy(alpha = CATEGORY_BORDER_ALPHA) else Design.border,
                    shape = RoundedCornerShape(14.dp),
                ).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
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

/** Непрозрачность оттенка и рамки — как в примере (у «важной» заметки). */
private const val CATEGORY_TINT_ALPHA = 0.22f
private const val CATEGORY_BORDER_ALPHA = 0.9f

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
