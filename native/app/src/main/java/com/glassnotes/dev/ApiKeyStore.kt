package com.glassnotes.dev

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ключи сервисов (DeepSeek) — по образцу `KeyVault` из giga-pisar-android: AES/GCM,
 * ключ шифрования живёт в Android Keystore и никогда его не покидает. В SharedPreferences
 * остаётся только шифротекст.
 *
 * Зачем: раньше ключ лежал открытым текстом в таблице `kv` рабочей базы — он попадал
 * в любую выгрузку БД (`adb pull`, «поделиться» файлом базы, отладка) и читался глазами.
 * Заметки к секрету отношения не имеют, поэтому ключ вынесен из базы вовсе.
 */
object ApiKeyStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "glassnotes_api_keys"
    private const val PREFS = "glassnotes_keys"
    private const val PREFIX = "k_"
    private const val TAG = "GlasNotesDev"
    private const val GCM_TAG_BITS = 128

    /** Сохранить ключ. Пустое значение стирает запись. */
    fun save(
        context: Context,
        name: String,
        value: String,
    ) {
        val plain = value.trim()
        if (plain.isEmpty()) {
            clear(context, name)
            return
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            prefs(context).edit()
                .putString(PREFIX + name, Base64.encodeToString(sealed, Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            // Keystore может отказать (сброс блокировки экрана, смена ключа). Ключ тогда
            // живёт только на экране настроек до перезапуска — молчать нельзя, пишем в лог.
            Log.w(TAG, "ключ %s не сохранён: %s".format(name, e.message))
        }
    }

    fun load(
        context: Context,
        name: String,
    ): String? {
        val stored = prefs(context).getString(PREFIX + name, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size <= 12) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, raw, 0, 12))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "ключ %s не прочитан: %s".format(name, e.message))
            null
        }
    }

    fun clear(
        context: Context,
        name: String,
    ) {
        prefs(context).edit().remove(PREFIX + name).apply()
    }

    fun has(
        context: Context,
        name: String,
    ): Boolean = !load(context, name).isNullOrBlank()

    /**
     * Действующий ключ + разовый перенос из старого места (kv в базе).
     * Возвращает пару «ключ» и «был перенесён»: вызывающий стирает открытую копию
     * из базы, когда второй элемент true.
     */
    fun takeOver(
        context: Context,
        name: String,
        legacy: String?,
    ): Pair<String, Boolean> {
        val stored = load(context, name).orEmpty().trim()
        if (stored.isNotEmpty()) return stored to false
        val old = legacy.orEmpty().trim()
        if (old.isEmpty()) return "" to false
        save(context, name, old)
        return old to true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Ключ нужен и без разблокировки: запрос к DeepSeek идёт из редактора,
                // а не из защищённой операции с биометрией.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
