package com.example.helmet.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class RtkCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun readPassword(): CredentialReadResult {
        val stored = runCatching { preferences.all }
            .getOrElse { return CredentialReadResult.corrupted(it.javaClass.name) }
        if (KEY_CIPHERTEXT !in stored && KEY_IV !in stored && KEY_VERSION !in stored) {
            return CredentialReadResult.missing()
        }
        val version = stored[KEY_VERSION]
        val ciphertext = stored[KEY_CIPHERTEXT]
        val iv = stored[KEY_IV]
        if (version !is Int || version != FORMAT_VERSION || ciphertext !is String || iv !is String) {
            return CredentialReadResult.corrupted("INCOMPLETE_OR_INVALID_RECORD")
        }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val key = existingKey() ?: error("ANDROID_KEYSTORE_KEY_MISSING")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            CredentialReadResult.available(
                cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8),
            )
        }.getOrElse { CredentialReadResult.corrupted(it.javaClass.name) }
    }

    @Synchronized
    fun writePassword(value: String) {
        if (value.isBlank()) {
            check(preferences.edit().clear().commit()) { "failed to clear RTK credential" }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
                .putInt(KEY_VERSION, FORMAT_VERSION)
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit(),
        ) { "failed to persist encrypted RTK credential" }
    }

    @Synchronized
    fun snapshot(): SharedPreferencesSnapshot = preferences.snapshot()

    @Synchronized
    fun restore(snapshot: SharedPreferencesSnapshot) = preferences.restore(snapshot)

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun createKey(): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        KEYSTORE_PROVIDER,
    ).run {
        init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    companion object {
        internal const val PREFERENCES_NAME = "helmet_rtk_credentials"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "helmet_ntrip_password_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val FORMAT_VERSION = 1
        private const val KEY_VERSION = "format_version"
        private const val KEY_IV = "iv"
        private const val KEY_CIPHERTEXT = "ciphertext"
    }
}
