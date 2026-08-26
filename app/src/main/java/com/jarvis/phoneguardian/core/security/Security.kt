package com.jarvis.phoneguardian.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random

/** Small, dependency-free Keystore wrapper used for LAN pairing secrets. */
class SecureTokenStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("secure_secrets", Context.MODE_PRIVATE)
    private val keyAlias = "phone_guardian_server_key"

    @Synchronized
    fun getOrCreateServerToken(): String {
        preferences.getString("server_token", null)?.let { encrypted ->
            runCatching { return decrypt(encrypted) }
        }
        val token = buildString {
            repeat(32) { append("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random()) }
        }
        preferences.edit().putString("server_token", encrypt(token)).apply()
        return token
    }

    fun revokeServerToken() {
        preferences.edit().remove("server_token").apply()
    }

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12) { "Invalid encrypted secret" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getKey(),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12))
        )
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(StandardCharsets.UTF_8)
    }
}

object PrivacyDefaults {
    const val AI_MODE_OFF = "off"
    const val AI_MODE_LOCAL = "local"
    const val AI_MODE_CLOUD = "cloud"
}
