package com.glassnotes.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
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
 * Утреннее ревью — НА ВЕСЬ ЭКРАН (требование владельца: раньше это была карточка 420×640
 * по центру, то есть всплывающее окно, а не экран). Содержание то же, что у `#reviewSplash`
 * боевого приложения: «Просрочено · N» с ⚠️, «На сегодня · N» и «Без срока · N»; блоки
 * разделены линией.
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

    // Разделитель рисуется только между непустыми блоками: пустых групп в списке нет.
    val sections =
        listOf(
            ReviewSection("Просрочено", overdue, "⚠️"),
            ReviewSection("На сегодня", today, "•"),
            ReviewSection("Без срока", noDate, "•"),
        ).filter { it.items.isNotEmpty() }

    val view = LocalView.current

    Dialog(
        onDismissRequest = onClose,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        // Фон приложения — тот же градиент, что за списком заметок: это экран, а не карточка.
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Design.appBackground()),
        ) {
            // Высота окна — по содержимому, но не больше экрана минус системные вставки:
            // раньше вес списка распирал колонку, и «Начать день» уезжала под полосу жестов.
            val columnLimit = maxHeight
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .heightIn(max = columnLimit)
                        .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 12.dp)
                        .align(Alignment.Center),
            ) {
                Text(
                    text = "🌅 Утреннее ревью",
                    color = Design.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 48.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(text = dateText, color = Design.textSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(18.dp))

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // Длинный список прокручивается внутри, оставляя место кнопке.
                            .heightIn(max = columnLimit * 0.62f)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (sections.isEmpty()) {
                        Text(
                            text = "На сегодня дел нет — можно выдохнуть 🙂",
                            color = Design.textSecondary,
                            fontSize = 15.sp,
                        )
                    }
                    sections.forEachIndexed { index, section ->
                        if (index > 0) sectionDivider()
                        reviewGroup(section.title, section.items, section.mark)
                    }
                }

                Spacer(Modifier.height(12.dp))

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
                            }.padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "Начать день", color = accent, fontSize = 16.sp)
                }
            }

            // Крестик .note-sheet-x: 38, круг, стекло, в правом верхнем углу — но под строкой
            // состояния, иначе кнопка оказывалась вне экрана (заметка #70).
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(top = 10.dp, end = 18.dp)
                        .size(38.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Design.tint(0.10f))
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

/** Блок ревью: заголовок, метка и строки дел. */
private data class ReviewSection(
    val title: String,
    val items: List<NoteRow>,
    val mark: String,
)

/** Линия между блоками ревью. */
@Composable
private fun sectionDivider() {
    Spacer(Modifier.height(2.dp))
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Design.border.copy(alpha = 0.9f)),
    )
    Spacer(Modifier.height(2.dp))
}

/** Одна группа списка: заголовок «Просрочено · N» и строки с меткой. */
@Composable
private fun reviewGroup(
    title: String,
    items: List<NoteRow>,
    mark: String,
) {
    if (items.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${title.uppercase()} · ${items.size}",
            color = Design.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
        Spacer(Modifier.height(6.dp))
        for (note in items) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(text = mark, fontSize = 15.sp, modifier = Modifier.width(22.dp))
                Text(
                    text = note.text.ifBlank { "(без текста)" },
                    color = Design.textPrimary,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
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
