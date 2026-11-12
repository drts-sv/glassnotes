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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    // Озвучка ревью (заметка #82): читаем те же группы, что на экране.
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    val reviewSpeech =
        buildString {
            if (overdue.isNotEmpty()) append("Просрочено: ").append(overdue.joinToString(". ") { it.text }).append(". ")
            if (today.isNotEmpty()) append("На сегодня: ").append(today.joinToString(". ") { it.text }).append(". ")
            if (noDate.isNotEmpty()) append("Без срока: ").append(noDate.joinToString(". ") { it.text })
        }
    GlassPanel(accent = accent, onDismiss = onClose, maxHeightFraction = 0.9f) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "🌅 Утреннее ревью",
                color = Design.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            // Озвучка ревью (заметка #82): читает те же группы, что на экране.
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(accent.copy(alpha = 0.12f))
                        .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .clickable {
                            Haptics.tick(view)
                            if (speaker.speaking) speaker.stop() else speaker.speak(reviewSpeech)
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = if (speaker.speaking) "⏹" else "🔊", fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(text = dateText, color = Design.textSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))

        // Список прокручивается внутри панели: она растёт по содержимому, но не на весь экран.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
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

        Spacer(Modifier.height(14.dp))

        // «Начать день» — одна широкая кнопка во всю панель: лишняя «Отмена» не нужна,
        // закрыть можно крестиком или тапом по фону.
        PanelButton("Начать день", accent, filled = true) {
            Haptics.confirm(view)
            onClose()
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