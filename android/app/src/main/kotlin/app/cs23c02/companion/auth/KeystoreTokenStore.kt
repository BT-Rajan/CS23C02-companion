package app.cs23c02.companion.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Tokens encrypted with AES-256-GCM under a non-exportable Android Keystore key, stored in private SharedPreferences.
 * Backups and device transfer are disabled in the manifest. If the key is lost (e.g. the user cleared credentials),
 * decryption fails, the blob is wiped and the student simply signs in again.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun load(): Tokens? {
        val blob = prefs.getString(KEY, null) ?: return null
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LEN))
            json.decodeFromString(Tokens.serializer(), String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN)))
        } catch (e: Exception) {
            clear()
            null
        }
    }

    @Synchronized
    override fun save(tokens: Tokens) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(json.encodeToString(Tokens.serializer(), tokens).toByteArray())
        prefs.edit().putString(KEY, Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEY = "tokens"
        const val ALIAS = "cs23c02.session.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
