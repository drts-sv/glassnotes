package com.glassnotes.dev

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gigapisar.model.ModelManager
import ru.gigapisar.speech.GigaAmOnnxRecognizer
import java.io.File
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Точка входа dev-сборки нативного пути.
 *
 * Диагностические входы (без пальца и без микрофона):
 *  - `wave_test` — прогон алгоритма полос синтетическим сигналом (WaveSelfTest);
 *  - `asr_file` — распознать файл PCM16 16 кГц целиком и напечатать текст;
 *  - `asr_replay` — проиграть файл ЧЕРЕЗ ту же цепочку, что живую речь: куски по 20 мс →
 *    LiveTranscriber → нарезка по паузам → распознавание. Так проверяется распознавание «на ходу»
 *    без участия владельца. Вероятность речи в этом режиме берём по энергии: живого VAD тут нет;
 *  - `record` — начать запись сразу при запуске.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Полноэкранно, но строка состояния остаётся: прячем только панель навигации,
        // вернуть её можно свайпом от края.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Уведомления на Android 13+ требуют отдельного разрешения (оно не входит в микрофон).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7002)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)

        // Настройки из базы — сразу при запуске: плотность стекла, затемнение, размытие фона,
        // времена периодов дня, разбор фразы и число потоков распознавания.
        runCatching {
            val settingsStore = NotesStore(applicationContext)
            AppSettings.applyAll(settingsStore)
            // Тема: "auto" идёт за системной настройкой Android — ставим до первого кадра.
            Design.setTheme(
                AppSettings.themeMode(settingsStore),
                (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES,
            )
            NluOptions.voiceCategory = AppSettings.flag(settingsStore, AppSettings.VOICE_CATEGORY, true)
            NluOptions.fillerCleanup = AppSettings.flag(settingsStore, AppSettings.FILLER, true)
            settingsStore.getKv("threads")?.trim()?.toIntOrNull()?.let { if (it > 0) GigaAmOnnxRecognizer.intraOpThreads = it }
            // Диагностика: --es theme_test light|dark|auto — выставить тему и запомнить выбор.
            intent?.getStringExtra("theme_test")?.let { mode ->
                settingsStore.setKv(AppSettings.THEME, mode)
                Design.setTheme(
                    mode,
                    (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES,
                )
            }
            Log.i(
                TAG,
                "настройки загружены: стекло %.2f, затемнение %.2f, тема %s (%s)".format(
                    Design.cardAlpha,
                    Design.veil,
                    Design.themeMode,
                    if (Design.light) "светлая" else "тёмная",
                ),
            )
        }

        // Диагностика: --es done_ids "36,42,44" — отметить заметки выполненными.
        intent?.getStringExtra("done_ids")?.let { raw ->
            runCatching {
                val store = NotesStore(applicationContext)
                val ids = raw.split(',').mapNotNull { it.trim().toLongOrNull() }
                ids.forEach {
                    store.setStatus(it, "done")
                    Reminders.cancel(this, it)
                }
                Log.i(TAG, "отмечено выполненными: " + ids.joinToString())
            }
        }

        if (intent?.getBooleanExtra("wave_test", false) == true) {
            Log.i(TAG, "=== самопроверка полос запущена из интента ===")
            WaveSelfTest.run()
            Log.i(TAG, "=== самопроверка полос закончена ===")
        }

        intent?.getIntExtra("threads", 0)?.let { count ->
            if (count > 0) {
                GigaAmOnnxRecognizer.intraOpThreads = count
                Log.i(TAG, "потоки распознавания заданы из интента: %d".format(count))
            }
        }

        intent?.getStringExtra("asr_file")?.let { name -> thread(name = "AsrFile") { recognizeWhole(name) } }
        intent?.getStringExtra("asr_replay")?.let { name -> thread(name = "AsrReplay") { replayThroughLive(name) } }

        intent?.getStringExtra("review_time")?.let { value ->
            // Время ревью из настроек: «ЧЧ:ММ», пустая строка — выключить. Ключ тот же, что в боевом.
            NotesStore(applicationContext).setKv(Reminders.REVIEW_TIME_KEY, value)
            Log.i(TAG, "review_time: время ревью задано как «%s»".format(value))
        }

        // Ревью показывается раз в сутки: при каждом запуске проверяем, что будильник стоит.
        Reminders.scheduleReview(applicationContext)

        // Проверку ставим ПОСЛЕ ежедневной: иначе она затирается тем же запросом PendingIntent.
        if (intent?.getBooleanExtra("seed_test", false) == true) {
            // Проверочные заметки: по одной на каждый отбор главного экрана.
            val store = NotesStore(applicationContext)
            val now = System.currentTimeMillis()
            fun at(
                days: Int,
                hour: Int,
            ): Long =
                java.util.Calendar.getInstance(java.util.Locale("ru")).apply {
                    timeInMillis = now
                    add(java.util.Calendar.DAY_OF_MONTH, days)
                    set(java.util.Calendar.HOUR_OF_DAY, hour)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
            store.add("Вчерашнее дело", "work", at(-1, 10))
            store.add("Сегодня в обед", "personal", at(0, 13))
            store.add("Завтра в 10:00 утра позвонить маме", null, at(1, 10))
            store.add("Через три дня купить хлеб", "shopping", at(3, 9))
            store.add("Через десять дней отчёт", "work", at(10, 12))
            store.add("Важное: оплатить счета", "important", null)
            store.add("Без срока", null, null)
            val doneId = store.add("Выполненное дело", "work", null)
            store.setStatus(doneId, "done")
            Log.i(TAG, "seed_test: проверочных заметок добавлено, всего %d".format(store.count()))
        }

        if (intent?.getBooleanExtra("list_test", false) == true) {
            // Проверка отборов главного экрана на реальной базе: те же правила, что видит экран.
            val store = NotesStore(applicationContext)
            val all = store.recent(200).mapNotNull { parseNote(it) }
            Log.i(TAG, "list_test: всего заметок %d".format(all.size))
            for ((id, name) in NoteFilters.dateOptions) {
                val kept = NoteFilters.apply(all, "active", id, null, "")
                Log.i(TAG, "list_test: отбор «%s» (active) → %d".format(name, kept.size))
            }
            Log.i(TAG, "list_test: вкладка «Выполнено» → %d".format(NoteFilters.apply(all, "done", null, null, "").size))
            for (q in listOf("хлеб", "маме")) {
                Log.i(TAG, "list_test: поиск «%s» → %d".format(q, NoteFilters.apply(all, "active", null, null, q).size))
            }
        }

        if (intent?.getBooleanExtra("review_test", false) == true) {
            Reminders.scheduleReview(applicationContext, System.currentTimeMillis() + 15_000L)
            Log.i(TAG, "review_test: ревью будет показано через 15 с")
        }

        if (intent?.getBooleanExtra("remind_test", false) == true) {
            // Проверка напоминания: заметка на 20 секунд вперёд, категория «Работа» —
            // уведомление должно прийти с её цветом и бейджем.
            val store = NotesStore(applicationContext)
            val at = System.currentTimeMillis() + 20_000L
            val id = store.add("Проверка напоминания", "work", at, false)
            Reminders.schedule(applicationContext, id, at, "Проверка напоминания", "work", false)
            Log.i(TAG, "remind_test: заметка #%d, напоминание через 20 с".format(id))
        }

        if (intent?.getBooleanExtra("date_test", false) == true) {
            // Таблица разбора дат и времени: сверяем с эталоном, снятым с боевого приложения.
            dateSelfTest().lines().forEach { line -> Log.i(TAG, "date_test: $line") }
        }

        if (intent?.getBooleanExtra("cat_test", false) == true) {
            // Автоопределение категории по ключевым словам: что и куда попадёт.
            CategoryHints.selfTest().lines().forEach { line -> Log.i(TAG, "cat_test: $line") }
        }

        if (intent?.getBooleanExtra("when_test", false) == true) {
            // Поле «Когда» в листе заметки: дата без часа больше не уходит в «Без даты».
            val now =
                java.util.Calendar.getInstance().apply {
                    set(2026, java.util.Calendar.NOVEMBER, 9, 10, 0, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
            WhenInput.selfTest(now).lines().forEach { line -> Log.i(TAG, "when_test: $line") }
        }

        if (intent?.getBooleanExtra("when_save_test", false) == true) {
            // Сквозная проверка: заметка → дата БЕЗ часа из листа → что видит список и ревью.
            val store = NotesStore(applicationContext)
            val w = WhenInput.parse("10.11.2026")
            val id = store.add("проверка даты без часа", datetime = w?.ms, allDay = w?.allDay == true)
            store.setDatetime(id, w?.ms, w?.allDay == true)
            val row = store.rawRow(id)?.first?.let { parseNote(it) }
            val cnt = store.reviewCounts()
            Log.i(
                TAG,
                "when_save_test: в листе дата без часа → datetime=%s allDay=%s | список видит: %s | ревью: просроч %d, сегодня %d, без даты %d"
                    .format(
                        w?.ms,
                        w?.allDay,
                        if (row?.datetime == null) "БЕЗ ДАТЫ" else WhenInput.display(row.datetime, row.allDay),
                        cnt.overdue,
                        cnt.today,
                        cnt.noDate,
                    ),
            )
            // Убираем тестовую заметку: данные владельца не трогаем.
            store.remove(id)
        }

        if (intent?.getBooleanExtra("settings_probe", false) == true) {
            // Пишем набор значений и читаем его обратно — тем же кодом, что и экран записи.
            val store = NotesStore(applicationContext)
            store.setKv("dbWindow", "71.5")
            store.setKv("noiseGate", "0.19")
            store.setKv("curve", "0.61")
            store.setKv("threads", "3")
            Log.i(
                TAG,
                "settings_probe: шкала %s, порог %s, кривая %s, потоков %s".format(
                    store.getKv("dbWindow"),
                    store.getKv("noiseGate"),
                    store.getKv("curve"),
                    store.getKv("threads"),
                ),
            )
        }

        if (intent?.getBooleanExtra("edit_test", false) == true) {
            // Проверка правок без ручных нажатий: те же методы, что вызывают тап и меню.
            val store = NotesStore(applicationContext)
            val note = store.recent(1).firstOrNull()?.let { parseNote(it) }
            if (note == null) {
                Log.i(TAG, "edit_test: заметок нет")
            } else {
                Log.i(TAG, "edit_test до: #%d %s, категории %s".format(note.id, note.status, note.categoryIds))
                store.setStatus(note.id, "done")
                Log.i(TAG, "edit_test отметили выполненной: %s".format(store.recent(1).firstOrNull()?.let { parseNote(it) }?.status))
                // Ставим ИМЕННО другое значение: если бы метод не работал, категория осталась бы прежней.
                val wasCategory = note.categoryIds.firstOrNull() ?: "нет"
                val newCategory = if (wasCategory == "personal") "work" else "personal"
                store.setCategory(note.id, newCategory)
                Log.i(
                    TAG,
                    "edit_test смена категории: было %s, стало %s".format(
                        wasCategory,
                        store.recent(1).firstOrNull()?.let { parseNote(it) }?.categoryIds,
                    ),
                )
                store.setStatus(note.id, "active")
                val probe = store.add("Заметка для проверки удаления")
                store.remove(probe)
                Log.i(TAG, "edit_test удаление: добавили id=%d, удалили, заметок теперь %d".format(probe, store.count()))
            }
        }

        if (intent?.getBooleanExtra("notes_dump", false) == true) {
            val store = NotesStore(applicationContext)
            val total = store.count()
            Log.i(TAG, "=== заметок в базе: %d ===".format(total))
            store.recent(5).forEach { json -> Log.i(TAG, "заметка: $json") }
        }

        setContent {
            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Color(0xFF8AB4F8),
                        surface = Color(0xFF17181C),
                        background = Color(0xFF101114),
                    ),
            ) {
                val accent = remember { systemAccent(applicationContext) }
                val store = remember { NotesStore(applicationContext) }
                // Тестовых вкладок «Запись/Список» больше нет: главный экран — список заметок,
                // а запись открывается кнопкой микрофона в правом нижнем углу.
                var recordingOpen by remember {
                    mutableStateOf(intent?.getBooleanExtra("record", false) == true)
                }

                if (recordingOpen) {
                    RecordingScreen(autoStart = true, onClose = { recordingOpen = false })
                } else {
                    NotesScreen(store = store, accent = accent, onRecord = { recordingOpen = true })
                }
            }
        }
    }

    /** Распознать файл целиком: проверка самого движка. */
    private fun recognizeWhole(name: String) {
        try {
            val manager = ModelManager(applicationContext)
            val file = File(getDir("gigaam", MODE_PRIVATE), name)
            Log.i(
                TAG,
                "модель: %s (размер %d, установлена: %s), файл: %s (%d байт)".format(
                    manager.modelFile.absolutePath,
                    manager.modelFile.length(),
                    manager.isInstalled(),
                    file.absolutePath,
                    file.length(),
                ),
            )
            val pcm = pcmOf(file.readBytes())
            val started = System.currentTimeMillis()
            val recognizer = GigaAmOnnxRecognizer(applicationContext, manager)
            val text = recognizer.transcribe(pcm)
            recognizer.close()
            Log.i(
                TAG,
                "РАСПОЗНАНО за %d мс (%.1f с звука): %s".format(
                    System.currentTimeMillis() - started,
                    pcm.size / 16000.0,
                    text,
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "распознавание не удалось", t)
        }
    }

    /** Проиграть файл через распознавание «на ходу» — как живую речь, в реальном времени. */
    private fun replayThroughLive(name: String) {
        try {
            val manager = ModelManager(applicationContext)
            val file = File(getDir("gigaam", MODE_PRIVATE), name)
            val bytes = file.readBytes()
            Log.i(TAG, "=== воспроизведение %s (%.1f с) через распознавание на ходу ===".format(name, bytes.size / 32000.0))

            val phrases = Collections.synchronizedList(ArrayList<String>())
            val store = NotesStore(applicationContext)
            val transcriber =
                LiveTranscriber(applicationContext, manager) { text ->
                    phrases.add(text)
                    // Тот же путь записи, что на экране: проверяем и распознавание, и сохранение.
                    // Как на экране: разбираем дату и время из распознанной фразы.
                    // Категория из intent — это переопределение для проверок, она сильнее разбора.
                    val parsed = parseDateNlu(text)
                    val categoryOverride = intent?.getStringExtra("category")
                    val id =
                        store.add(
                            text = parsed.text,
                            categoryId = categoryOverride ?: parsed.categoryId,
                            datetime = parsed.datetime,
                            allDay = parsed.allDay,
                        )
                    Log.i(
                        TAG,
                        "заметка сохранена (воспроизведение): id=%d, дата %s, весь день %s, категория %s, всего %d".format(
                            id,
                            parsed.datetime?.let { java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(it)) } ?: "нет",
                            if (parsed.allDay) "да" else "нет",
                            categoryOverride ?: parsed.categoryId ?: "нет",
                            store.count(),
                        ),
                    )
                }

            val chunkBytes = 16000 / 50 * 2 // 20 мс
            var offset = 0
            var speechChunks = 0
            while (offset < bytes.size) {
                val take = minOf(chunkBytes, bytes.size - offset)
                val part = bytes.copyOfRange(offset, offset + take)
                val rms = rmsOf(part)
                // Энергетический признак речи вместо нейросетевого VAD: в воспроизведении VAD не участвует.
                val probability = if (rms > SPEECH_RMS) 0.9f else 0.05f
                if (rms > SPEECH_RMS) speechChunks++
                transcriber.onChunk(part, take, probability)
                offset += take
                Thread.sleep(20) // реальное время, как при живой записи
            }
            transcriber.finish()
            Thread.sleep(10000) // дать очереди дораспознать остаток

            Log.i(
                TAG,
                "ИТОГ ВОСПРОИЗВЕДЕНИЯ: фраз %d, речи во фрагментах %d: %s".format(
                    phrases.size,
                    speechChunks,
                    phrases.joinToString(" | "),
                ),
            )
            transcriber.close()
        } catch (t: Throwable) {
            Log.e(TAG, "воспроизведение не удалось", t)
        }
    }

    private fun pcmOf(bytes: ByteArray): ShortArray =
        ShortArray(bytes.size / 2) { i ->
            ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }

    private fun rmsOf(bytes: ByteArray): Int {
        val samples = bytes.size / 2
        if (samples == 0) return 0
        var sum = 0.0
        for (i in 0 until samples) {
            val sample = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            sum += sample.toDouble() * sample
        }
        return sqrt(sum / samples).toInt()
    }

    private companion object {
        const val TAG = "GlasNotesDev"
        const val SPEECH_RMS = 300
    }
}

/** Вкладка переключателя экранов: выбранная залита акцентом, как вкладки в боевом приложении. */
@androidx.compose.runtime.Composable
private fun ScreenTab(
    title: String,
    selected: Boolean,
    accent: Color,
    onSelect: () -> Unit,
) {
    // Один @Composable-вызов на уровне функции: внутри clickable читать LocalView нельзя.
    val view = LocalView.current
    Text(
        text = title,
        color = if (selected) onAccent(accent) else Design.textSecondary.copy(alpha = 0.75f),
        fontSize = 14.sp,
        modifier =
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) accent else Design.glassRgb.copy(alpha = Design.cardAlpha))
                .clickable {
                    Haptics.tick(view)
                    onSelect()
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
