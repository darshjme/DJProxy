package ai.darshj.djproxy.vpn

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest protection for the one secret this app stores: the proxy password (needed to re-establish
 * the tunnel on an always-on / process restart). The password is encrypted with an AES-256-GCM key
 * that lives in the AndroidKeyStore — the key material never leaves the TEE/StrongBox and is not
 * extractable, so a rooted device or a forensic image of `shared_prefs` yields only ciphertext.
 *
 * Fail-closed for secrets: if the Keystore is unavailable or a round-trip fails, we return null
 * rather than fall back to plaintext. A dropped password simply means the tunnel will not silently
 * auto-reconnect with a recovered credential — never that the credential is stored in the clear.
 */
internal object CredentialStore {

    private const val KEY_ALIAS = "djproxy_credential_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    /** Encrypts [plaintext] and returns Base64(iv || ciphertext), or null on any Keystore failure. */
    fun encrypt(plaintext: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        Base64.encodeToString(out, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    /** Decrypts a blob produced by [encrypt], or null if it cannot be decrypted. */
    fun decrypt(blob: String): String? {
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return null
            val iv = raw.copyOfRange(0, IV_LEN)
            val ct = raw.copyOfRange(IV_LEN, raw.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No setUserAuthenticationRequired: the tunnel must re-establish headless on boot.
                .build(),
        )
        return generator.generateKey()
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
}
