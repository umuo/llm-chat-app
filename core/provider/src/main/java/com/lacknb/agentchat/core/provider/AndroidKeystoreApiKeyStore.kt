package com.lacknb.agentchat.core.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreApiKeyStore(
    context: Context,
) : ApiKeyStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(SecretPrefsName, Context.MODE_PRIVATE)

    override fun save(ref: String, apiKey: String) {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val payload = listOf(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString(separator = ":")

        prefs.edit().putString(ref, payload).apply()
    }

    override fun load(ref: String): String? {
        val payload = prefs.getString(ref, null) ?: return null
        val parts = payload.split(":")
        if (parts.size != 2) return null

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GcmTagLengthBits, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    override fun delete(ref: String) {
        prefs.edit().remove(ref).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        val existing = keyStore.getKey(KeyAlias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        val spec = KeyGenParameterSpec.Builder(
            KeyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val AndroidKeyStore = "AndroidKeyStore"
        const val KeyAlias = "agentchat_provider_api_key"
        const val SecretPrefsName = "agentchat_secrets"
        const val Transformation = "AES/GCM/NoPadding"
        const val GcmTagLengthBits = 128
    }
}
