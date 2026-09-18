package com.trevor.assistant

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecureApiKeyStore {

    private const val KEYSTORE_NAME = "AndroidKeyStore"
    private const val KEY_ALIAS = "TREVOR_GEMINI_KEY"

    private const val PREFS_NAME = "trevor_secure_storage"
    private const val API_KEY_PREF = "gemini_api_key"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    private fun getKeyStore(): KeyStore {
        return KeyStore.getInstance(KEYSTORE_NAME).apply {
            load(null)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {

        val keyStore = getKeyStore()

        val existingKey = keyStore.getKey(KEY_ALIAS, null)

        if (existingKey is SecretKey) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            "AES",
            KEYSTORE_NAME
        )

        keyGenerator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(
                    android.security.keystore.KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setRandomizedEncryptionRequired(true)
                .build()
        )

        return keyGenerator.generateKey()
    }

    fun save(
        context: Context,
        apiKey: String
    ) {
        require(apiKey.isNotBlank()) {
            "API key cannot be empty."
        }

        val secretKey = getOrCreateSecretKey()

        val cipher = Cipher.getInstance(TRANSFORMATION)

        cipher.init(
            Cipher.ENCRYPT_MODE,
            secretKey
        )

        val encryptedBytes = cipher.doFinal(
            apiKey.toByteArray(Charsets.UTF_8)
        )

        val combined = ByteBuffer
            .allocate(cipher.iv.size + encryptedBytes.size)
            .put(cipher.iv)
            .put(encryptedBytes)
            .array()

        val encoded = Base64.encodeToString(
            combined,
            Base64.NO_WRAP
        )

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(API_KEY_PREF, encoded)
            .apply()
    }

    fun load(
        context: Context
    ): String? {

        val encoded = context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .getString(API_KEY_PREF, null)
            ?: return null

        return try {

            val combined = Base64.decode(
                encoded,
                Base64.NO_WRAP
            )

            val buffer = ByteBuffer.wrap(combined)

            val iv = ByteArray(12)
            buffer.get(iv)

            val encryptedBytes = ByteArray(
                buffer.remaining()
            )

            buffer.get(encryptedBytes)

            val secretKey = getOrCreateSecretKey()

            val cipher = Cipher.getInstance(TRANSFORMATION)

            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(
                    GCM_TAG_LENGTH,
                    iv
                )
            )

            String(
                cipher.doFinal(encryptedBytes),
                Charsets.UTF_8
            )

        } catch (_: Exception) {
            null
        }
    }

    fun clear(
        context: Context
    ) {
        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .remove(API_KEY_PREF)
            .apply()
    }

    fun exists(
        context: Context
    ): Boolean {
        return !load(context).isNullOrBlank()
    }
}
