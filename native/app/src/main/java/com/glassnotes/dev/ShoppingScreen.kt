package com.glassnotes.dev

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val SHOPPING_TAG = "GlasNotesDev"
private const val SHOPPING_DONE_KV = "shopping_done"

/** Один пункт объединённого списка покупок. */
internal data class ShoppingItem(
    val key: String,
    val text: String,
)

/**
 * Пункты покупок из всех активных заметок категории «Покупки».
 *
 * Заметки #126 и #132: несколько покупок записываются в одну заметку через разделители, а разные
 * заметки объединяются в один список. Порядок — как в базе: свежие заметки сверху, внутри заметки
 * порядок слов сохраняется. Дубли (одно и то же название в разных заметках) показываем один раз.
 */
internal fun readShoppingItems(store: NotesStore): List<ShoppingItem> {
    val separators = charArrayOf(',', ';', '·', '•', '\n', '\r')
    val result = ArrayList<ShoppingItem>()
    val seen = HashSet<String>()
    for (line in store.recent(1000)) {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
        // Статус заметки здесь не важен: покупка отмечается чекбоксом на этом экране, а заметку
        // владелец может закрыть как выполненную — пункты из неё всё равно нужны в магазине.
        val cats = json.optJSONArray("categoryIds") ?: continue
        var isShopping = false
        for (i in 0 until cats.length()) {
            if (cats.optString(i) == "shopping") isShopping = true
        }
        if (!isShopping) continue
        for (part in json.optString("text").split(*separators)) {
            val text = part.trim().trim('.', '!', '-', '—')
            if (text.length < 2) continue
            val key = text.lowercase()
            if (!seen.add(key)) continue
            result.add(ShoppingItem(key, text))
        }
    }
    return result
}

internal fun readDone(store: NotesStore): Set<String> {
    val raw = store.getKv(SHOPPING_DONE_KV)
    if (raw.isNullOrBlank()) return emptySet()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptySet()
    val set = HashSet<String>()
    for (i in 0 until array.length()) {
        val key = array.optString(i)
        if (key.isNotBlank()) set.add(key)
    }
    return set
}

private fun writeDone(store: NotesStore, keys: Set<String>) {
    store.setKv(SHOPPING_DONE_KV, JSONArray(keys.toList()).toString())
}

/**
 * Экран «Покупки» — открывается корзинкой в заголовке.
 *
 * Отметка пункта не меняет заметки: купленное хранится в kv как список ключей, поэтому список
 * можно сбросить одной кнопкой, а сами записи остаются нетронутыми.
 */
@Composable
fun ShoppingScreen(
    store: NotesStore,
    accent: Color,
    onClose: () -> Unit,
) {
    val view = LocalView.current
    var items by remember { mutableStateOf<List<ShoppingItem>>(emptyList()) }
    var done by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reload by remember { mutableIntStateOf(0) }

    // Перечитываем заметки при открытии и по кнопке «↻»: пока экран открыт, могли записать новую.
    LaunchedEffect(reload) {
        val loaded = withContext(Dispatchers.IO) {
            readShoppingItems(store) to readDone(store)
        }
        items = loaded.first
        done = loaded.second
        Log.i(SHOPPING_TAG, "покупки: пунктов %d, отмечено %d".format(items.size, done.size))
    }

    // Купленное уходит вниз: в магазине нужен список того, что ещё взять.
    val ordered = items.sortedBy { done.contains(it.key) }
    val boughtCount = items.count { done.contains(it.key) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(16.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .heightIn(max = 700.dp)
                        .clip(RoundedCornerShape(40.dp))
                        .background(Design.glassRgb.copy(alpha = 0.94f))
                        .border(1.dp, Design.border, RoundedCornerShape(40.dp))
                        .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "🛒 Покупки",
                        color = Design.textPrimary,
                        fontSize = 17.6.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "$boughtCount из ${items.size}",
                            color = Design.textSecondary,
                            fontSize = 12.sp,
                        )
                        Box(
                            modifier =
                                Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Design.tint(0.06f))
                                    .clickable {
                                        Haptics.tick(view)
                                        reload++
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("↻", color = Design.textSecondary, fontSize = 15.sp)
                        }
                        Box(
                            modifier =
                                Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Design.tint(0.06f))
                                    .clickable {
                                        Haptics.tick(view)
                                        onClose()
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✕", color = Design.textSecondary, fontSize = 15.sp)
                        }
                    }
                }

                if (items.isEmpty()) {
                    Text(
                        text = "Список пуст. Запиши покупки голосом с тегом «Покупки» — " +
                            "перечисляй через запятую, всё попадёт в один список.",
                        color = Design.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(ordered, key = { it.key }) { item ->
                            val isDone = done.contains(item.key)
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Design.tint(if (isDone) 0.02f else 0.06f))
                                        .clickable {
                                            Haptics.tick(view)
                                            val next = if (isDone) done - item.key else done + item.key
                                            done = next
                                            writeDone(store, next)
                                        }.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(20.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(if (isDone) accent.copy(alpha = 0.85f) else Design.tint(0.06f))
                                            .border(1.dp, if (isDone) accent else Design.border, RoundedCornerShape(50)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isDone) {
                                        Text(text = "✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(
                                    text = item.text,
                                    color = if (isDone) Design.textSecondary else Design.textPrimary,
                                    fontSize = 14.sp,
                                    textDecoration = if (isDone) TextDecoration.LineThrough else null,
                                )
                            }
                        }
                    }
                }

                if (boughtCount > 0) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Design.tint(0.06f))
                                .clickable {
                                    Haptics.tick(view)
                                    done = emptySet()
                                    writeDone(store, emptySet())
                                    Log.i(SHOPPING_TAG, "покупки: отметки сброшены")
                                }.padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "Сбросить отметки",
                            color = Design.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
