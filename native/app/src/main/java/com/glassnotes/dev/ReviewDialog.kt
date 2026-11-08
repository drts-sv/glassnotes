package com.glassnotes.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Утреннее ревью — экран со списком дел на сегодня, как `#reviewSplash` в боевом приложении:
 * карточка 420 max, радиус 28, поля 1.4/1.2rem, зазор 10; группы «Просрочено · N» с ⚠️,
 * «На сегодня · N» и «Без срока · N» с •; закрытие — крестик или «Начать день».
 *
 * Правила отбора те же, что в `reviewItems()` боевого: активные заметки; без даты — «без срока»;
 * с датой на сегодня — «на сегодня»; с датой раньше сейчас (и не на сегодня) — «просрочено».
 */
@Composable
fun MorningReviewScreen(
    notes: List<NoteRow>,
    accent: Color,
    onClose: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val dayStart = remember(now) { startOfDay(now) }
    val dayEnd = dayStart + 24L * 60 * 60 * 1000

    val active = notes.filter { !it.done }
    val overdue =
        active.filter { note ->
            val at = note.datetime ?: return@filter false
            at < now && startOfDay(at) != dayStart
        }.sortedBy { it.datetime }
    val today =
        active.filter { note ->
            val at = note.datetime ?: return@filter false
            at >= dayStart && at < dayEnd
        }.sortedBy { it.datetime }
    val noDate = active.filter { it.datetime == null }

    val total = overdue.size + today.size
    val dateText =
        buildString {
            append(dayText(now))
            append(", дел на день: ").append(total)
            if (noDate.isNotEmpty()) append(" (+").append(noDate.size).append(" без срока)")
        }

    val view = LocalView.current

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    // Тап по фону закрывает окно — как в боевом приложении.
                    .clickable {
                        Haptics.tick(view)
                        onClose()
                    },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(18.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .heightIn(max = 640.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Design.glassRgb.copy(alpha = 0.94f))
                        .border(1.dp, Design.border, RoundedCornerShape(28.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(start = 19.2.dp, end = 19.2.dp, top = 22.4.dp, bottom = 22.4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "🌅 Утреннее ревью",
                        color = Design.textPrimary,
                        fontSize = 18.4.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 44.dp),
                    )
                    Text(text = dateText, color = Design.textSecondary, fontSize = 13.6.sp)

                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (overdue.isEmpty() && today.isEmpty() && noDate.isEmpty()) {
                            Text(
                                text = "На сегодня дел нет — можно выдохнуть 🙂",
                                color = Design.textSecondary,
                                fontSize = 14.4.sp,
                            )
                        }
                        reviewGroup("Просрочено", overdue, "⚠️")
                        reviewGroup("На сегодня", today, "•")
                        reviewGroup("Без срока", noDate, "•")
                    }

                    // «Начать день» — как .note-sheet-btn.edit: акцент 10 % и его рамка 35 %.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(40.dp))
                                .background(accent.copy(alpha = 0.10f))
                                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(40.dp))
                                .clickable {
                                    Haptics.confirm(view)
                                    onClose()
                                }.padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "Начать день", color = accent, fontSize = 16.sp)
                    }
                }

                // Крестик .note-sheet-x: 38, круг, стекло 0.06, в правом верхнем углу.
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 14.dp, end = 14.dp)
                            .size(38.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.06f))
                            .clickable {
                                Haptics.tick(view)
                                onClose()
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "✕", color = Design.textPrimary, fontSize = 16.sp)
                }
            }
        }
    }
}

/** Одна группа списка: заголовок «Просрочено · N» и строки с меткой. */
@Composable
private fun reviewGroup(
    title: String,
    items: List<NoteRow>,
    mark: String,
) {
    if (items.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            text = "${title.uppercase()} · ${items.size}",
            color = Design.textSecondary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
        Spacer(Modifier.size(4.dp))
        for (note in items) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(text = mark, fontSize = 14.7.sp, modifier = Modifier.padding(end = 8.dp))
                Text(
                    text = note.text.ifBlank { "(без текста)" },
                    color = Design.textPrimary,
                    fontSize = 14.7.sp,
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

private fun startOfDay(ms: Long): Long =
    java.util.Calendar.getInstance().apply {
        timeInMillis = ms
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

/** Дата ревью: «8 ноября» — как `renderReview` в боевом приложении. */
private fun dayText(ms: Long): String {
    val months =
        listOf(
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря",
        )
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "${cal.get(java.util.Calendar.DAY_OF_MONTH)} ${months[cal.get(java.util.Calendar.MONTH)]}"
}
