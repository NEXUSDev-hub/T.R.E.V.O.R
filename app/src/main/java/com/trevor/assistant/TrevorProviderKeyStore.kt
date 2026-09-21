package com.trevor.assistant

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object TrevorProviderKeyStore {
    private const val PREFS = "trevor_provider_keys_v2"
    private const val ALIAS = "TREVOR_PROVIDER_KEYS_V2"

    fun save(context: Context, provider: TrevorProviderId, value: String) {
        require(value.isNotBlank())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(provider.name, encrypt(value.trim()))
            .apply()
    }

    fun load(context: Context, provider: TrevorProviderId): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(provider.name, null)?.let(::decrypt)

    fun clear(context: Context, provider: TrevorProviderId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(provider.name).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(ByteBuffer.allocate(cipher.iv.size + data.size).put(cipher.iv).put(data).array(), Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = runCatching {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val b = ByteBuffer.wrap(raw)
        val iv = ByteArray(12).also { b.get(it) }
        val data = ByteArray(b.remaining()).also { b.get(it) }
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }.doFinal(data).toString(Charsets.UTF_8)
    }.getOrNull()
}
