package dev.networkstorage.data.credential

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.networkstorage.domain.Credential
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

interface CredentialStore {
    fun put(connectionId: String, password: CharArray)
    fun get(connectionId: String): Credential?
    fun remove(connectionId: String)
}

@Singleton
class KeystoreCredentialStore @Inject constructor(@ApplicationContext context: Context) : CredentialStore {
    private val preferences = context.getSharedPreferences("protected_credentials", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    override fun put(connectionId: String, password: CharArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val plaintext = String(password).toByteArray(StandardCharsets.UTF_8)
        try {
            val encoded = cipher.iv + cipher.doFinal(plaintext)
            preferences.edit().putString(connectionId, Base64.encodeToString(encoded, Base64.NO_WRAP)).apply()
        } finally { plaintext.fill(0) }
    }

    override fun get(connectionId: String): Credential? {
        val value = preferences.getString(connectionId, null) ?: return null
        return runCatching {
            val encoded = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encoded.copyOfRange(0, IV_BYTES))) }
            Credential(String(cipher.doFinal(encoded.copyOfRange(IV_BYTES, encoded.size)), StandardCharsets.UTF_8).toCharArray())
        }.getOrNull()
    }

    override fun remove(connectionId: String) { preferences.edit().remove(connectionId).apply() }

    private companion object { const val KEY_ALIAS = "network-storage-credentials-v1"; const val TRANSFORMATION = "AES/GCM/NoPadding"; const val IV_BYTES = 12 }
}
