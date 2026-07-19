package ai.darshj.djproxy.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the API 21-22 degradation contract: the AndroidKeyStore AES-GCM path (KeyGenParameterSpec /
 * KEY_ALGORITHM_AES) only exists on API 23+, so on 21-22 [CredentialStore] must degrade to
 * session-only (return null) instead of throwing a NoClassDefFoundError that fails tunnel bring-up.
 */
class CredentialStoreTest {

    @Test
    fun keystoreAvailable_onlyOnApi23Plus() {
        assertFalse(CredentialStore.keystoreAvailable(21))
        assertFalse(CredentialStore.keystoreAvailable(22))
        assertTrue(CredentialStore.keystoreAvailable(23))
        assertTrue(CredentialStore.keystoreAvailable(35))
    }

    @Test
    fun encryptDecrypt_returnNull_whenKeystoreUnavailable_onHostJvm() {
        // On the host JVM (unit test) Build.VERSION.SDK_INT defaults to 0, so both must degrade to
        // null rather than propagate a Keystore linkage Error.
        assertNull(CredentialStore.encrypt("secret"))
        assertNull(CredentialStore.decrypt("blob"))
    }
}
