package com.joeysretro.joey64.launcher.settings

import com.joeysretro.joey64.JLog
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A RetroAchievements sign-in: the user name and the token the login returned (never the password). */
data class SignIn(val user: String, val token: String)

private val Context.signInStore: DataStore<Preferences> by preferencesDataStore(name = "signin")

/**
 * Keeps the sign-in key encrypted with the Android Keystore (plan, Phase 0): an AES-256/GCM key
 * that never leaves the device's keystore encrypts the token, and only the ciphertext is stored.
 * Its own DataStore file, so it can be left out of backups on its own.
 *
 * A token restored onto another device (or after the app's keys were wiped) can't be decrypted;
 * [load] then answers null and the user signs in again.
 */
class SignInStore(context: Context) {
    private val store = context.applicationContext.signInStore

    suspend fun save(signIn: SignIn) {
        val sealed = KeystoreBox.seal(signIn.token.toByteArray(Charsets.UTF_8))
        store.edit {
            it[USER] = signIn.user
            it[TOKEN] = sealed
        }
    }

    suspend fun load(): SignIn? {
        val p = store.data.first()
        val user = p[USER] ?: return null
        val token = p[TOKEN]?.let(KeystoreBox::open) ?: return null
        return SignIn(user, String(token, Charsets.UTF_8))
    }

    suspend fun clear() = store.edit { it.clear() }

    private companion object {
        val USER = stringPreferencesKey("user")
        val TOKEN = stringPreferencesKey("token_sealed")
    }
}

/** AES/GCM with a key held by the Android Keystore. Sealed form: base64 of version byte, 12-byte IV, ciphertext + tag. */
internal object KeystoreBox {
    private const val ALIAS = "joey64_signin"
    private const val VERSION: Byte = 1
    private const val TAG = "joey64signin"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())   // the keystore picks a fresh IV
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        val out = ByteArray(1 + iv.size + body.size)
        out[0] = VERSION
        iv.copyInto(out, 1)
        body.copyInto(out, 1 + iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun open(sealed: String): ByteArray? = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        require(raw.size > 13 && raw[0] == VERSION)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 1, 12))
        cipher.doFinal(raw, 13, raw.size - 13)
    } catch (e: Exception) {
        JLog.w(TAG, "stored sign-in can't be read (restored from another device?): $e")
        null
    }
}
