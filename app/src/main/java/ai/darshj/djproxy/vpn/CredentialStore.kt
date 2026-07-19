package ai.darshj.djproxy.vpn

import android.os.Build
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

    /**
     * True when the AndroidKeyStore AES-GCM path is usable. `KeyGenParameterSpec.Builder` and
     * `KeyProperties.KEY_ALGORITHM_AES` were added in API 23 (M); below that they throw
     * NoClassDefFoundError (an Error, not an Exception). Pure — unit-tested.
     */
    fun keystoreAvailable(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.M

    /**
     * Encrypts [plaintext] and returns Base64(iv || ciphertext), or null on any Keystore failure OR
     * when the Keystore path is unavailable (API 21-22) — in which case the caller degrades to
     * session-only credentials (the password simply is not persisted for headless auto-reconnect),
     * NEVER stored in the clear. Catches [Throwable] so a linkage Error also degrades to null.
     */
    fun encrypt(plaintext: String): String? {
        if (!keystoreAvailable(Build.VERSION.SDK_INT)) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(ct, 0, out, iv.size, ct.size)
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    /** Decrypts a blob produced by [encrypt], or null if it cannot be decrypted / Keystore absent. */
    fun decrypt(blob: String): String? {
        if (!keystoreAvailable(Build.VERSION.SDK_INT)) return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return null
            val iv = raw.copyOfRange(0, IV_LEN)
            val ct = raw.copyOfRange(IV_LEN, raw.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("NewApi") // guarded by keystoreAvailable() at every call site.
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
