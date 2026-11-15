package com.glassnotes.dev

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import ru.gigapisar.model.ModelManager

/**
 * Первый запуск: четыре шага, каждый — с объяснением, зачем нужно и что будет без него.
 *
 * Разрешения не «выпрашиваются» пачкой при старте: человек видит причину, потом системный
 * диалог. Отказ ничего не ломает — приложение работает, шаг помечается «не разрешено»,
 * повторить можно тут же (и позже в настройках).
 *
 * Флаг `onboarded` в kv: экран показывается один раз.
 */
@Composable
fun OnboardingScreen(
    store: NotesStore,
    accent: Color,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current

    var step by remember { mutableIntStateOf(0) }

    var mic by remember { mutableStateOf(has(context, Manifest.permission.RECORD_AUDIO)) }
    var notifications by remember { mutableStateOf(notificationsGranted(context)) }
    var contacts by remember { mutableStateOf(Contacts.hasAccess(context)) }
    var modelReady by remember {
        mutableStateOf(runCatching { ModelManager(context).isInstalled() }.getOrDefault(false))
    }
    var downloadState by remember { mutableStateOf(ModelDownloadService.state(context)) }
    var progress by remember { mutableIntStateOf(ModelDownloadService.progress(context)) }
    var downloadError by remember { mutableStateOf(ModelDownloadService.message(context)) }
    var partial by remember {
        mutableStateOf(runCatching { ModelManager(context).partialBytes() }.getOrDefault(0L))
    }

    val micLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            mic = ok
            android.util.Log.i("GlasNotesDev", "онбординг: микрофон %s".format(if (ok) "разрешён" else "отклонён"))
            step = 1
        }
    val notifLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            notifications = ok
            android.util.Log.i("GlasNotesDev", "онбординг: уведомления %s".format(if (ok) "разрешены" else "отклонены"))
            step = 2
        }
    val contactsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            contacts = ok
            Contacts.invalidate()
            android.util.Log.i("GlasNotesDev", "онбординг: контакты %s".format(if (ok) "разрешены" else "отклонены"))
            step = 3
        }

    // Прогресс докачки модели: сервис пишет его в SharedPreferences, читаем раз в полсекунды.
    LaunchedEffect(Unit) {
        android.util.Log.i(
            "GlasNotesDev",
            "онбординг: показан (микрофон=%s, уведомления=%s, контакты=%s, модель=%s)".format(
                mic, notifications, contacts,
                runCatching { ModelManager(context).isInstalled() }.getOrDefault(false),
            ),
        )
        while (true) {
            downloadState = ModelDownloadService.state(context)
            progress = ModelDownloadService.progress(context)
            downloadError = ModelDownloadService.message(context)
            modelReady = runCatching { ModelManager(context).isInstalled() }.getOrDefault(false)
            if (downloadState != ModelDownloadService.STATE_RUNNING) {
                partial = runCatching { ModelManager(context).partialBytes() }.getOrDefault(0L)
            }
            delay(500)
        }
    }

    fun finish() {
        store.setKv("onboarded", "yes")
        android.util.Log.i("GlasNotesDev", "онбординг: пройден (микрофон=%s, уведомления=%s, контакты=%s, модель=%s)"
            .format(mic, notifications, contacts, modelReady))
        onDone()
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Text(
            text = "GlasNote",
            color = Design.textPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "Настроим за минуту — дальше всё будет работать само",
            color = Design.textSecondary.copy(alpha = 0.85f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.size(14.dp))
        StepDots(count = 4, current = step, accent = accent)
        Spacer(Modifier.size(18.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                0 ->
                    StepCard(
                        emoji = "🎙",
                        title = "Микрофон",
                        lines =
                            listOf(
                                "GlasNote записывает голос и превращает его в заметку: нажал, сказал «позвонить Шиденко завтра в 10», отпустил — в списке появилось дело с датой и человеком.",
                                "Запись распознаёт модель прямо на телефоне: звук никуда не отправляется, интернет для заметок не нужен.",
                                "Если не разрешать: запись не запустится. Заметки можно будет вводить текстом.",
                            ),
                        granted = mic,
                        accent = accent,
                        primary = if (mic) "Продолжить" else "Разрешить микрофон",
                        onPrimary = { if (mic) step = 1 else micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onSkip = if (mic) null else ({ step = 1 }),
                        skipLabel = "Пропустить",
                    )

                1 ->
                    StepCard(
                        emoji = "🔔",
                        title = "Уведомления",
                        lines =
                            listOf(
                                "По ним приходят напоминания о делах с датой и утреннее ревью: что просрочено, что на сегодня, что без срока.",
                                "Напоминание — обычное уведомление: его видно на экране блокировки, тап открывает заметку.",
                                "Если не разрешать: заметки и список работают как обычно, но напоминания молча не покажутся — приложение не сможет тебя позвать.",
                            ),
                        granted = notifications,
                        accent = accent,
                        primary = if (notifications) "Продолжить" else "Разрешить уведомления",
                        onPrimary = {
                            if (notifications) {
                                step = 2
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                step = 2
                            }
                        },
                        onSkip = if (notifications) null else ({ step = 2 }),
                        skipLabel = "Пропустить",
                    )

                2 ->
                    StepCard(
                        emoji = "👤",
                        title = "Контакты",
                        lines =
                            listOf(
                                "Книга контактов нужна, чтобы понимать фамилии в речи: «позвонить шиденко» превращается в «Шиденко» с правильным написанием, а человек одним касанием прикрепляется к заметке.",
                                "Читаются только имя и номер. Наружу ничего не уходит: подбор идёт на телефоне.",
                                "Если не разрешать: заметка запишется, но имя останется как услышано — без телефона и фото, прикрепить человека можно будет вручную.",
                            ),
                        granted = contacts,
                        accent = accent,
                        primary = if (contacts) "Продолжить" else "Разрешить контакты",
                        onPrimary = { if (contacts) step = 3 else contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
                        onSkip = if (contacts) null else ({ step = 3 }),
                        skipLabel = "Пропустить",
                    )

                else -> ModelStep(
                    accent = accent,
                    ready = modelReady,
                    downloading = downloadState == ModelDownloadService.STATE_RUNNING,
                    progress = progress,
                    error = downloadError,
                    partialBytes = partial,
                    onDownload = {
                        ModelDownloadService.clearResult(context)
                        downloadError = null
                        ModelDownloadService.start(context)
                        downloadState = ModelDownloadService.STATE_RUNNING
                        progress = 0
                    },
                    onStop = { ModelDownloadService.stop(context) },
                    onBattery = { SettingsScreenHelpers.requestIgnoreBattery(context) {} },
                )
            }
        }

        Spacer(Modifier.size(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (step > 0) "Назад" else "",
                color = Design.textSecondary.copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = step > 0) {
                            Haptics.tick(view)
                            if (step > 0) step -= 1
                        }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                text = if (step < 3) "Дальше" else "Начать пользоваться",
                color = accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            Haptics.tick(view)
                            if (step < 3) step += 1 else finish()
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Один шаг: значок, заголовок, объяснение и кнопки. */
@Composable
private fun StepCard(
    emoji: String,
    title: String,
    lines: List<String>,
    granted: Boolean,
    accent: Color,
    primary: String,
    onPrimary: () -> Unit,
    onSkip: (() -> Unit)?,
    skipLabel: String,
) {
    val view = LocalView.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Design.tint(0.06f))
                .border(1.dp, Design.border, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = emoji, fontSize = 26.sp)
            Spacer(Modifier.size(10.dp))
            Text(text = title, color = Design.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            if (granted) {
                Text(text = "✓ разрешено", color = Design.done, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            }
        }
        lines.forEachIndexed { index, line ->
            Text(
                text = line,
                color = if (index == lines.lastIndex) Design.textSecondary.copy(alpha = 0.75f) else Design.textPrimary.copy(alpha = 0.9f),
                fontSize = 13.6.sp,
                lineHeight = 19.sp,
            )
        }
        Spacer(Modifier.size(2.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accent.copy(alpha = if (granted) 0.16f else 0.9f))
                    .clickable {
                        Haptics.tick(view)
                        onPrimary()
                    },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = primary,
                color = if (granted) accent else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (onSkip != null) {
            Text(
                text = skipLabel,
                color = Design.textSecondary.copy(alpha = 0.8f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            Haptics.tick(view)
                            onSkip()
                        }.padding(vertical = 8.dp),
            )
        }
    }
}

/** Последний шаг — модель распознавания: размер, прогресс, докачка, Wi-Fi. */
@Composable
private fun ModelStep(
    accent: Color,
    ready: Boolean,
    downloading: Boolean,
    progress: Int,
    error: String?,
    partialBytes: Long,
    onDownload: () -> Unit,
    onStop: () -> Unit,
    onBattery: () -> Unit,
) {
    val view = LocalView.current
    val partialMb = partialBytes / (1024 * 1024)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Design.tint(0.06f))
                .border(1.dp, Design.border, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "🧠", fontSize = 26.sp)
            Spacer(Modifier.size(10.dp))
            Text(text = "Модель распознавания", color = Design.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            if (ready) {
                Text(text = "✓ готова", color = Design.done, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            }
        }
        Text(
            text = "GigaAM v3 — 214 МБ. Она превращает речь в текст прямо на телефоне: без интернета и без отправки звука куда-либо.",
            color = Design.textPrimary.copy(alpha = 0.9f),
            fontSize = 13.6.sp,
            lineHeight = 19.sp,
        )
        Text(
            text = "Качается по Wi-Fi, с докачкой: если связь оборвётся, продолжим с того же места. Файл проверяется по SHA-256 — битая загрузка не установится.",
            color = Design.textPrimary.copy(alpha = 0.9f),
            fontSize = 13.6.sp,
            lineHeight = 19.sp,
        )
        Text(
            text = "Если отложить: список, правка и напоминания работают, а запись голосом — нет. Скачать можно позже в настройках, там же виден прогресс.",
            color = Design.textSecondary.copy(alpha = 0.75f),
            fontSize = 13.6.sp,
            lineHeight = 19.sp,
        )

        if (downloading) {
            Text(
                text = if (progress >= 0) "%d%% · %d из 214 МБ".format(progress, 214L * progress / 100) else "готовим загрузку…",
                color = accent,
                fontSize = 13.sp,
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Design.border),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth((progress.coerceIn(0, 100)) / 100f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(accent),
                )
            }
            Text(
                text = "Загрузка идёт в фоне — можно свернуть приложение и заниматься делами.",
                color = Design.textSecondary.copy(alpha = 0.8f),
                fontSize = 12.5.sp,
            )
        } else if (!ready && partialMb > 0) {
            Text(
                text = "уже скачано %d МБ из 214 — продолжим с этого места".format(partialMb),
                color = accent.copy(alpha = 0.9f),
                fontSize = 12.5.sp,
            )
        }
        if (error != null && !downloading) {
            Text(text = error, color = Design.stopRed, fontSize = 12.5.sp, lineHeight = 17.sp)
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (ready) accent.copy(alpha = 0.16f) else accent.copy(alpha = 0.9f))
                    .clickable {
                        Haptics.tick(view)
                        when {
                            ready -> Unit
                            downloading -> onStop()
                            else -> onDownload()
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    ready -> "Модель на месте"
                    downloading -> "Остановить загрузку"
                    partialMb > 0 -> "Продолжить загрузку"
                    else -> "Скачать 214 МБ"
                },
                color = if (ready) accent else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = "Разрешить работу в фоне (чтобы загрузка не прервалась)",
            color = Design.textSecondary.copy(alpha = 0.8f),
            fontSize = 12.5.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        Haptics.tick(view)
                        onBattery()
                    }.padding(vertical = 8.dp),
        )
    }
}

/** Точки прогресса: пройденные — акцентом. */
@Composable
private fun StepDots(
    count: Int,
    current: Int,
    accent: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            Box(
                modifier =
                    Modifier
                        .size(width = if (index == current) 22.dp else 8.dp, height = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (index <= current) accent else Design.border),
            )
        }
    }
}

private fun has(
    context: Context,
    permission: String,
): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun notificationsGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        has(context, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        true
    }
