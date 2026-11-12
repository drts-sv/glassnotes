import io, os

BASE = 'C:/Users/drts/Documents/glass-notes/native/app/src/main/java/com/glassnotes/dev/'

def edit(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    for old, new, label in pairs:
        assert s.count(old) == 1, f'{label}: найдено {s.count(old)}'
        s = s.replace(old, new, 1)
        print('ok:', label)
    io.open(path + '.tmp', 'wb').write(s.encode('utf-8'))
    os.replace(path + '.tmp', path)

# ---- #100: таймер сплэша категорий тоже дробный ----
edit(BASE + 'SettingsScreen.kt', [
("""        mutableFloatStateOf(AppSettings.int(store, AppSettings.CAT_SHEET_SEC, AppSettings.DEFAULT_CAT_SHEET_SEC, 0..15).toFloat())""",
"""        mutableFloatStateOf(AppSettings.num(store, AppSettings.CAT_SHEET_SEC, AppSettings.DEFAULT_CAT_SHEET_SEC.toFloat(), 0f..15f))""",
"catSheet float"),
("""                            store.setKv(AppSettings.CAT_SHEET_SEC, catSheetSec.roundToInt().toString())""",
"""                            store.setKv(AppSettings.CAT_SHEET_SEC, "%.1f".format(catSheetSec))""",
"catSheet сохранение 0.5"),
])

edit(BASE + 'RecordingScreen.kt', [
("""        val sec = store.getKv(AppSettings.CAT_SHEET_SEC)?.toIntOrNull() ?: 14""",
"""        val sec = (store.getKv(AppSettings.CAT_SHEET_SEC)?.toFloatOrNull() ?: AppSettings.DEFAULT_CAT_SHEET_SEC.toFloat()).toInt()""",
"catSheet чтение дробного"),

# ---- #102: кольцо показывает остаток до предела записи ----
("""    LaunchedEffect(recording) {
        seconds = 0
        while (recording) {
            delay(1000)
            seconds++
        }
    }""",
"""    // #102: один кусок записи ограничен (по умолчанию 10 с) — кольцо показывает, сколько осталось.
    val recordMaxSec = remember {
        (store.getKv("recordMaxSec")?.toFloatOrNull() ?: 10f).coerceIn(3f, 60f)
    }
    LaunchedEffect(recording) {
        seconds = 0
        while (recording) {
            delay(1000)
            seconds++
            if (seconds >= recordMaxSec) {
                Log.i(TAG, "предел записи %.0f с — сохраняем заметку".format(recordMaxSec))
                stopAndSave()
                break
            }
        }
    }""",
"#102 предел записи"),
("""                    // Кольцо таймера: в боевом приложении смещено на −6px и повёрнуто на −90°.
                    val ringInset = 6.dp.toPx()
                    val ringTopLeft = Offset(ringInset * 0.5f, ringInset * 0.5f)
                    drawArc(
                        color = accent.copy(alpha = 0.85f),
                        startAngle = -90f,
                        sweepAngle = 360f * ((seconds % 60) / 60f),
                        useCenter = false,""",
"""                    // Кольцо — обратный отсчёт остатка записи (#102): полное кольцо в начале,
                    // к нулю — предел сегмента. Цвет гуще по мере приближения к концу.
                    val ringInset = 6.dp.toPx()
                    val ringTopLeft = Offset(ringInset * 0.5f, ringInset * 0.5f)
                    val leftFrac = (1f - (seconds / recordMaxSec)).coerceIn(0f, 1f)
                    drawArc(
                        color = accent.copy(alpha = 0.85f),
                        startAngle = -90f,
                        sweepAngle = 360f * leftFrac,
                        useCenter = false,""",
"#102 кольцо обратного отсчёта"),
])
