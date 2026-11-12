package com.glassnotes.dev

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * #117/#118 — «Улучшить текст» через DeepSeek.
 *
 * Промт из заметки #118 намеренно короткий: ответ в 1–5 пунктов, без вступлений,
 * `max_tokens = 1200` и `temperature = 0.4` — так расход токенов минимален.
 * Ключ хранится в таблице kv (`deepseek_key`), а не в APK: подпись/сборка не содержит секретов.
 */
object DeepSeek {
    const val KEY = "deepseek_key"
    const val MODEL = "deepseek-chat"
    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MAX_TOKENS = 1200
    private const val TEMP = 0.4

    /** Промт редактора — ровно из заметки #118, без лишних пояснений модели. */
    const val SYSTEM =
        "Редактор заметок. Улучши текст: исправь ошибки, убери повторы и канцелярит, " +
            "сделай компактнее и яснее. Смысл и тон не меняй. Обрывки фраз сведи в связные предложения.\n\n" +
            "Ответ строго в формате:\n" +
            "1) Улучшенный текст (без пояснений внутри).\n" +
            "2) «Что изменено» — 1–5 кратких пунктов.\n" +
            "Без вступлений и комментариев."

    /** Результат: готовый текст и список правок (может быть пустым). */
    data class Reply(val text: String, val changes: String)

    class Failure(
        message: String,
    ) : Exception(message)

    /**
     * Блокирующий запрос — вызывать строго вне главного потока (`withContext(Dispatchers.IO)`).
     * Бросает [Failure] с человекочитаемой причиной: их показываем в редакторе.
     */
    fun improve(
        key: String,
        text: String,
    ): Reply {
        val secret = key.trim()
        if (secret.isEmpty()) throw Failure("Нет ключа DeepSeek — впишите его в настройках")
        if (text.isBlank()) throw Failure("Пустая заметка — нечего улучшать")

        val body =
            JSONObject()
                .put("model", MODEL)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", text.trim())))
                .put("temperature", TEMP)
                .put("max_tokens", MAX_TOKENS)
                .toString()

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $secret")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            if (code !in 200..299) {
                val hint =
                    when (code) {
                        401 -> "ключ не принят (401)"
                        402 -> "на счёте DeepSeek нет средств (402)"
                        429 -> "слишком частые запросы (429)"
                        in 500..599 -> "сервис DeepSeek недоступен ($code)"
                        else -> "HTTP $code"
                    }
                throw Failure("DeepSeek: $hint")
            }

            val content =
                JSONObject(raw)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content")
                    .trim()
            if (content.isEmpty()) throw Failure("DeepSeek вернул пустой ответ")
            return split(content)
        } catch (e: Failure) {
            throw e
        } catch (e: Exception) {
            throw Failure("Сеть: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Делит ответ на текст и «что изменено». Модель может пронумеровать пункты по-разному,
     * поэтому ищем последний маркер «что изменено» и всё после него считаем списком правок.
     */
    fun split(answer: String): Reply {
        val clean = answer.replace("\r\n", "\n").trim()
        val markers = listOf("2)", "что изменено", "что изменено:", "изменения:")
        var cut = -1
        for (m in markers) {
            val at = clean.lowercase().indexOf(m)
            if (at > 0 && (cut < 0 || at < cut)) cut = at
        }
        if (cut <= 0) return Reply(stripNumbering(clean), "")
        val head = stripNumbering(clean.substring(0, cut))
        val tail = clean.substring(cut)
            .substringAfter('\n', "")
            .ifBlank { clean.substring(cut) }
            .trim()
            .removePrefix("«Что изменено»").removePrefix("Что изменено").removePrefix(":")
            .trim()
        return Reply(head, tail)
    }

    private fun stripNumbering(s: String): String =
        s.trim().removePrefix("1)").removePrefix("1.").trim()
}
