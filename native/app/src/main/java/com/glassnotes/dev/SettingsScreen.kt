package com.glassnotes.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log

/**
 * Настройки. Перенесены из боевого приложения те, что имеют смысл на телефоне:
 * время утреннего ревью, число потоков распознавания, определение категории по словам.
 *
 * Параметры полос (шкала, порог, отзывчивость) остались на экране записи: их подбирают,
 * глядя на развёртку, и там же они применяются сразу.
 */
@Composable
fun SettingsScreen(
    store: NotesStore,
    accent: Color,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current

    var reviewTime by remember { mutableStateOf(store.getKv(Reminders.REVIEW_TIME_KEY) ?: Reminders.DEFAULT_REVIEW_TIME) }
    var threads by remember { mutableFloatStateOf(store.getKv("threads")?.toFloatOrNull() ?: 1f) }
    var voiceCategory by remember { mutableStateOf(store.getKv("voiceCategory") ?: "вкл") }
    var silenceSec by remember { mutableFloatStateOf(store.getKv("silenceSec")?.toFloatOrNull() ?: 3f) }
    var notesCount by remember { mutableStateOf(store.count()) }

    // Любое изменение пишем в базу сразу: настройки должны переживать перезапуск.
    LaunchedEffect(reviewTime, voiceCategory) {
        store.setKv(Reminders.REVIEW_TIME_KEY, reviewTime)
        store.setKv("voiceCategory", voiceCategory)
        Reminders.scheduleReview(context)
        Log.i("GlasNotesDev", "настройки: время ревью «%s», категория по словам %s".format(reviewTime, voiceCategory))
    }
    LaunchedEffect(threads) {
        store.setKv("threads", threads.toString())
        Log.i("GlasNotesDev", "настройки: потоков распознавания %d".format(threads.toInt()))
    }
    LaunchedEffect(silenceSec) {
        store.setKv("silenceSec", silenceSec.toString())
        Log.i("GlasNotesDev", "настройки: авто-остановка по тишине %.0f с".format(silenceSec))
    }
    LaunchedEffect(Unit) {
        notesCount = store.count()
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = Design.padSide, end = Design.padSide, top = Design.padTop)
                .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "← Назад",
                color = accent,
                fontSize = 15.sp,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            Haptics.tick(view)
                            onClose()
                        }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Настройки",
                color = Design.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard("🌅 Утреннее ревью") {
            val off = reviewTime.isBlank()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (off) "выключено" else reviewTime,
                    color = Design.textPrimary,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f),
                )
                Stepper("−") {
                    reviewTime = shiftTime(reviewTime, -15)
                }
                Stepper("+") {
                    reviewTime = shiftTime(reviewTime, 15)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    text = if (off) "включить" else "выключить",
                    color = accent.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                Haptics.tick(view)
                                reviewTime = if (off) Reminders.DEFAULT_REVIEW_TIME else ""
                            }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                text = "Одно уведомление со сводкой дел: просрочено, на сегодня, без срока.",
                color = Design.textSecondary.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        SettingsCard("🎙 Распознавание") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Потоков: %d".format(threads.toInt()),
                    color = Design.textPrimary,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                Stepper("−") {
                    threads = (threads - 1f).coerceAtLeast(1f)
                }
                Stepper("+") {
                    threads = (threads + 1f).coerceAtMost(8f)
                }
            }
            Text(
                text = "Быстрее всего на этом телефоне — один поток (замеры: 322 мс против 61183 мс на семи).",
                color = Design.textSecondary.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Категория по словам",
                    color = Design.textPrimary,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = voiceCategory,
                    color = accent.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                Haptics.tick(view)
                                voiceCategory = if (voiceCategory == "вкл") "выкл" else "вкл"
                            }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                text = "«купить хлеб» → категория «Покупки», как в боевом приложении.",
                color = Design.textSecondary.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        SettingsCard("⏹ Окончание записи") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (silenceSec <= 0f) "авто-остановка выключена" else "Тишина: %.0f с".format(silenceSec),
                    color = Design.textPrimary,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                Stepper("−") {
                    silenceSec = (silenceSec - 1f).coerceAtLeast(0f)
                }
                Stepper("+") {
                    silenceSec = (silenceSec + 1f).coerceAtMost(10f)
                }
            }
            Text(
                text = "Если после речи тишина дольше этого времени, запись останавливается сама, " +
                    "заметка сохраняется и окно записи закрывается. 0 — только вручную.",
                color = Design.textSecondary.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        SettingsCard("📊 Полосы записи") {
            Text(
                text = "Шкала, порог тишины и отзывчивость подбираются на экране записи — под кнопкой «⚙ подбор уровня»: их смотрят по развёртке и там же меняют.",
                color = Design.textSecondary.copy(alpha = 0.85f),
                fontSize = 13.sp,
            )
        }

        SettingsCard("ℹ️ О приложении") {
            Text(text = "GlasNote, сборка " + BuildConfig.VERSION_NAME, color = Design.textPrimary, fontSize = 15.sp)
            Text(
                text = "заметок: %d · категорий: %d".format(notesCount, Categories.names.size),
                color = Design.textSecondary.copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "Распознавание: GigaAM v3 на устройстве, без сети.",
                color = Design.textSecondary.copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Design.glassRgb.copy(alpha = Design.cardAlpha))
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            color = Design.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Stepper(
    label: String,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Text(
        text = label,
        color = Design.textPrimary,
        fontSize = 18.sp,
        modifier =
            Modifier
                .padding(start = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Design.glassRgb.copy(alpha = 0.6f))
                .clickable {
                    Haptics.tick(view)
                    onClick()
                }.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Сдвиг времени «ЧЧ:ММ» на заданное число минут по кругу суток. */
private fun shiftTime(
    value: String,
    minutes: Int,
): String {
    val parts = value.split(":")
    val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 8
    val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    var total = (hour * 60 + minute + minutes) % (24 * 60)
    if (total < 0) total += 24 * 60
    return "%02d:%02d".format(total / 60, total % 60)
}
