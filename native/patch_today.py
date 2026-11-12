import io, os

def edit(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    for old, new, label in pairs:
        assert s.count(old) == 1, f'{label}: найденo {s.count(old)}'
        s = s.replace(old, new, 1)
        print('ok:', label)
    io.open(path + '.tmp', 'wb').write(s.encode('utf-8'))
    os.replace(path + '.tmp', path)

BASE = 'C:/Users/drts/Documents/glass-notes/native/app/src/main/java/com/glassnotes/dev/'

# 1. Design.chipText
edit(BASE + 'Design.kt', [
("""    val textSecondary: Color
        get() = if (light) Color(0xFF2C3E66) else Color(0xE6E6EBFF)""",
"""    val textSecondary: Color
        get() = if (light) Color(0xFF2C3E66) else Color(0xE6E6EBFF)

    /**
     * Текст поверх цветной плашки (бейдж категории, активный чип). В светлой теме белый
     * на полупрозрачном цвете не читался (#107) — там берём основной цвет текста.
     */
    val chipText: Color
        get() = if (light) Color(0xFF1A1C2C) else Color.White""",
"Design.chipText"),
])

P = BASE + 'NotesScreen.kt'
edit(P, [
# #101: озвучка компактной иконкой в заголовке панели
("""                modifier = Modifier.weight(1f),
            )
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Design.tint(0.07f))
                        .clickable {
                            Haptics.tick(view)
                            onClose()
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", color = Design.textSecondary, fontSize = 15.sp)
            }""",
"""                modifier = Modifier.weight(1f),
            )
            // #101: озвучка — компактной иконкой в заголовке, без текста и широкой кнопки.
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(50))
                        .background(accent.copy(alpha = if (speaking) 0.28f else 0.12f))
                        .clickable {
                            Haptics.tick(view)
                            onSpeak()
                        },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(if (speaking) R.drawable.ic_stop else R.drawable.ic_volume),
                    contentDescription = if (speaking) "Остановить озвучку" else "Озвучить",
                    colorFilter =
                        androidx.compose.ui.graphics.ColorFilter.tint(
                            if (speaking) accent else Design.textPrimary,
                        ),
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Design.tint(0.07f))
                        .clickable {
                            Haptics.tick(view)
                            onClose()
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", color = Design.textSecondary, fontSize = 15.sp)
            }""",
"#101 иконка озвучки"),

# #116: выделение и копирование текста системным меню
("""            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = note.text,
                    color = Design.textPrimary.copy(alpha = if (note.done) 0.6f else 1f),
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                )""",
"""            Column(modifier = Modifier.fillMaxWidth()) {
                // #116: выделение и «Копировать» — системным контекстным меню.
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text = note.text,
                        color = Design.textPrimary.copy(alpha = if (note.done) 0.6f else 1f),
                        fontSize = 20.sp,
                        lineHeight = 28.sp,
                    )
                }""",
"#116 SelectionContainer"),

# #104/#114: ряд кнопок, удаление с отсчётом
("""        // Нижняя часть панели — широкие кнопки во всю ширину карточки.
        PanelButton(
            text = if (speaking) "⏹ Остановить" else "🔊 Озвучить",
            accent = accent,
        ) {
            Haptics.tick(view)
            onSpeak()
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("✏️ Изменить", accent, Modifier.weight(1f)) {
                Haptics.tick(view)
                onEdit()
            }
            PanelButton("Отмена", accent, Modifier.weight(1f)) {
                Haptics.tick(view)
                onClose()
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "удалить — удерживать",
                color = Design.textSecondary.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            HoldToDelete(sec = holdDeleteSec, onFire = onDelete)
        }""",
"""        // #104: «Изменить», «Удалить» и «Отмена» — в одном ряду, одной высоты.
        // #114: пока держишь «Удалить», на кнопке идёт обратный отсчёт — видно, когда сработает.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("✏️ Изменить", accent, Modifier.weight(1f)) {
                Haptics.tick(view)
                onEdit()
            }
            HoldDeleteButton(sec = holdDeleteSec, modifier = Modifier.weight(1f), onFire = onDelete)
            PanelButton("Отмена", accent, Modifier.weight(1f)) {
                Haptics.tick(view)
                onClose()
            }
        }""",
"#104/#114 ряд кнопок"),
])

# HoldToDelete -> HoldDeleteButton (замена функции целиком)
s = io.open(P, encoding='utf-8').read()
start = s.index('private fun HoldToDelete(')
cstart = s.rindex('/**', 0, start)
body_start = s.index('    ) {\n', start)
end = s.index('\n}\n', body_start) + 3
new = '''/**
 * Кнопка удаления в ряду панели просмотра. Удаление — только удержанием (случайный тап ничего
 * не делает), и пока палец на кнопке, идёт обратный отсчёт «3.0 … 0.0» и полоска прогресса (#104, #114).
 */
@Composable
private fun HoldDeleteButton(
    sec: Float,
    modifier: Modifier = Modifier,
    onFire: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(pressed, sec) {
        if (!pressed) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        val result =
            progress.animateTo(
                1f,
                tween((sec.coerceAtLeast(0.5f) * 1000).toInt(), easing = LinearEasing),
            )
        if (result.endReason == AnimationEndReason.Finished) onFire()
    }
    val left = ((1f - progress.value) * sec).coerceAtLeast(0f)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Design.stopRed.copy(alpha = 0.12f))
                .border(1.dp, Design.stopRed.copy(alpha = 0.40f), RoundedCornerShape(18.dp))
                .pointerInput(sec) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                    )
                },
    ) {
        Text(
            text = if (pressed) "%.1f".format(left) else "Удалить",
            color = Design.stopRed.copy(alpha = 0.95f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        if (progress.value > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                drawLine(
                    color = Design.stopRed.copy(alpha = 0.75f),
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width * progress.value, size.height - stroke / 2f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
'''
s = s[:cstart] + new + s[end:]
io.open(P + '.tmp', 'wb').write(s.encode('utf-8'))
os.replace(P + '.tmp', P)
print('ok: HoldDeleteButton')

# NoteSheet: holdDeleteSec Float
s = io.open(P, encoding='utf-8').read()
for old, new, label in [
    ("    holdDeleteSec: Int,\n    onClose: () -> Unit,", "    holdDeleteSec: Float,\n    onClose: () -> Unit,", "NoteSheet: float sec"),
]:
    assert s.count(old) == 1, f'{label}: {s.count(old)}'
    s = s.replace(old, new, 1)
    print('ok:', label)
io.open(P + '.tmp', 'wb').write(s.encode('utf-8'))
os.replace(P + '.tmp', P)
