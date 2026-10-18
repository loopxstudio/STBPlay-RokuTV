package com.stbplay.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-install data key wrapped by a non-exportable Keystore key; each value has a fresh GCM nonce. */
object SecureValues {
    private const val ALIAS = "stbplay.local.v1"
    private const val PREFIX = "stb.enc.v1:"
    private lateinit var appContext: Context
    fun initialize(context: Context) { appContext = context.applicationContext }
    @Volatile private var cachedKey: SecretKey? = null
    @Synchronized private fun key(): SecretKey {
        cachedKey?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        val master = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        }.generateKey()
        val prefs = appContext.getSharedPreferences("stbplay_key_material", Context.MODE_PRIVATE)
        val stored = prefs.getString("wrapped", null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val bytes = if (stored == null) {
            val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, master)
            check(prefs.edit().putString("wrapped", Base64.encodeToString(cipher.iv + cipher.doFinal(fresh), Base64.NO_WRAP)).commit())
            fresh
        } else {
            val wrapped = Base64.decode(stored, Base64.NO_WRAP)
            require(wrapped.size >= 28)
            cipher.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
            cipher.doFinal(wrapped.copyOfRange(12, wrapped.size))
        }
        return javax.crypto.spec.SecretKeySpec(bytes, "AES").also { cachedKey = it; bytes.fill(0) }
    }
    fun encrypted(value: String) = value.startsWith(PREFIX)
    fun encrypt(value: String): String {
        if (value.isEmpty()) return value
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return PREFIX + Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun decrypt(value: String): String {
        if (!encrypted(value)) return value // Legacy rows are migrated on database open.
        val bytes = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
        require(bytes.size >= 28) { "Invalid protected data" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}

/** Uses the existing preference file, migrating legacy strings without losing profiles. */
class SecurePreferences(context: Context, name: String) {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun sensitive(key: String) = key in setOf("stalker_server", "stalker_mac", "xtream_server", "xtream_username", "xtream_password", "playlist_url") || key.startsWith("favorite_items_") || key.startsWith("player_history_")
    init {
        val edit = prefs.edit()
        var changed = false
        prefs.all.forEach { (key, value) ->
            if (value is String && SecureValues.encrypted(value)) SecureValues.decrypt(value)
            else if (value is String && sensitive(key) && value.isNotEmpty()) {
                edit.putString(key, SecureValues.encrypt(value)); changed = true
            }
        }
        if (changed) check(edit.commit()) { "Could not protect saved preferences" }
    }
    fun getString(key: String, default: String?): String? = prefs.getString(key, null)?.let(SecureValues::decrypt) ?: default
    fun getStringSet(key: String, default: Set<String>?): Set<String>? = prefs.getStringSet(key, default)?.toSet()
    fun edit() = Editor(prefs.edit(), ::sensitive)
    fun removeMatching(predicate: (String) -> Boolean) { val e = prefs.edit(); prefs.all.keys.filter(predicate).forEach(e::remove); e.commit() }
    class Editor(private val editor: android.content.SharedPreferences.Editor, private val sensitive: (String) -> Boolean) {
        fun putString(key: String, value: String?) = apply { editor.putString(key, if (value != null && sensitive(key)) SecureValues.encrypt(value) else value) }
        fun putStringSet(key: String, value: Set<String>?) = apply { editor.putStringSet(key, value) }
        fun remove(key: String) = apply { editor.remove(key) }
        fun clear() = apply { editor.clear() }
        fun apply() = editor.apply()
        fun commit() = editor.commit()
    }
}
