package com.nuvio.app.features.bingers

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Each profile's Bingers token, encrypted with an Android Keystore key (same scheme as MDBList's). */
internal actual object PlatformBingersTokenPersistence {
    private const val KEY_ALIAS = "com.nuvio.media.bingers.credentials.v1"
    private val lock = Any()
    @Volatile private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("nuvio_bingers_auth", Context.MODE_PRIVATE)
    }

    actual fun isReady(): Boolean = preferences != null

    actual fun read(profileId: Int): String? = synchronized(lock) {
        val prefs = preferences ?: return@synchronized null
        val key = "profile.$profileId"
        val value = prefs.getString(key, null) ?: return@synchronized null
        try {
            val parts = value.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, decode(parts[0])))
            cipher.updateAAD("$KEY_ALIAS:$key".toByteArray(Charsets.UTF_8))
            cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            // Unreadable (e.g. the Keystore key was reset by a restore): forget it, the user reconnects.
            if (!prefs.edit().remove(key).commit()) throw IOException("Unable to clear protected credentials")
            null
        }
    }

    actual fun write(profileId: Int, value: String?) = synchronized(lock) {
        val prefs = preferences ?: throw IOException("Bingers storage is not ready")
        val key = "profile.$profileId"
        val editor = prefs.edit()
        if (value == null) {
            editor.remove(key)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD("$KEY_ALIAS:$key".toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            editor.putString(key, "${encode(cipher.iv)}.${encode(encrypted)}")
        }
        if (!editor.commit()) throw IOException("Unable to save protected credentials")
    }

    actual fun clear() = synchronized(lock) {
        val prefs = preferences ?: return@synchronized
        if (!prefs.edit().clear().commit()) throw IOException("Unable to clear protected credentials")
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
