package ai.darshj.djproxy.store

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CRUD + crypto-round-trip + persistence tests for [SharedPreferencesProxyStore], using an in-memory
 * [KeyValueStore] and a reversible fake [SecretCipher] (the real one needs the AndroidKeyStore,
 * which is stubbed under `unitTests.isReturnDefaultValues=true`). No device, JVM-only.
 */
class ProxyStoreTest {

    /** In-memory KV standing in for the `djproxy_vault` SharedPreferences file. */
    private class MemKv(val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    /** Reversible fake cipher; [available]=false models API 21-22 (encrypt→null, never plaintext). */
    private class FakeCipher(val available: Boolean = true) : SecretCipher {
        // Reverses the plaintext so the ciphertext never contains the plaintext substring —
        // mirrors the real AES-GCM CredentialStore's no-plaintext-at-rest property under the
        // "no cleartext in persistence" assertions below.
        override fun encrypt(plaintext: String): String? =
            if (available) "enc(" + plaintext.reversed() + ")" else null
        override fun decrypt(blob: String): String? =
            if (available && blob.startsWith("enc(") && blob.endsWith(")"))
                blob.substring(4, blob.length - 1).reversed() else null
    }

    private fun config(
        host: String = "1.2.3.4",
        port: Int = 1080,
        type: ProxyType = ProxyType.SOCKS5,
        username: String = "",
        password: String = "",
    ) = ProxyConfig(type = type, host = host, port = port, username = username, password = password)

    private fun newStore(kv: MemKv = MemKv(), cipher: SecretCipher = FakeCipher()) =
        SharedPreferencesProxyStore(kv, cipher)

    @Test
    fun `save assigns id order and appears in flow`() = runBlocking {
        val store = newStore()
        val saved = store.save("home", config())
        assertTrue(saved.id.isNotEmpty())
        assertEquals(0, saved.order)
        assertEquals(listOf(saved.id), store.proxies.value.map { it.id })
        val second = store.save("work", config(host = "5.6.7.8"))
        assertEquals(1, second.order)
    }

    @Test
    fun `password is encrypted at rest never plaintext and resolves back`() = runBlocking {
        val kv = MemKv()
        val store = newStore(kv)
        val saved = store.save("auth", config(username = "u", password = "s3cret"))
        assertTrue(saved.hasPassword)
        // No plaintext anywhere in persistence.
        assertFalse(kv.map.values.any { it.contains("s3cret") })
        // Ciphertext stored under pw.<id>.
        assertEquals("enc(terc3s)", kv.map["pw.${saved.id}"])
        // resolve decrypts.
        val resolved = store.resolve(saved.id)!!
        assertEquals("s3cret", resolved.password)
        assertEquals("u", resolved.username)
    }

    @Test
    fun `api21-22 degrades to no stored password never plaintext`() = runBlocking {
        val kv = MemKv()
        val store = newStore(kv, FakeCipher(available = false))
        val saved = store.save("auth", config(username = "u", password = "s3cret"))
        assertFalse(saved.hasPassword)
        assertNull(kv.map["pw.${saved.id}"])
        assertFalse(kv.map.values.any { it.contains("s3cret") })
        // resolve returns config with empty password (fail-closed).
        assertEquals("", store.resolve(saved.id)!!.password)
    }

    @Test
    fun `update re-encrypts new password and clears when emptied`() = runBlocking {
        val kv = MemKv()
        val store = newStore(kv)
        val saved = store.save("p", config(username = "u", password = "old"))
        store.update(saved.id, "p2", config(username = "u", password = "new"))
        assertEquals("enc(wen)", kv.map["pw.${saved.id}"])
        assertEquals("p2", store.proxies.value.first().name)
        assertEquals("new", store.resolve(saved.id)!!.password)
        // Emptying the password clears the stored blob.
        store.update(saved.id, "p2", config(username = "", password = ""))
        assertNull(kv.map["pw.${saved.id}"])
        assertFalse(store.proxies.value.first().hasPassword)
    }

    @Test
    fun `delete removes entry and its ciphertext and clears default`() = runBlocking {
        val kv = MemKv()
        val store = newStore(kv)
        val a = store.save("a", config(username = "u", password = "x"))
        store.setDefault(a.id)
        assertEquals(a.id, store.defaultId.value)
        store.delete(a.id)
        assertTrue(store.proxies.value.isEmpty())
        assertNull(kv.map["pw.${a.id}"])
        assertNull(store.defaultId.value)
        assertNull(store.resolve(a.id))
    }

    @Test
    fun `reorder persists new order`() = runBlocking {
        val store = newStore()
        val a = store.save("a", config())
        val b = store.save("b", config())
        val c = store.save("c", config())
        store.reorder(listOf(c.id, a.id, b.id))
        assertEquals(listOf(c.id, a.id, b.id), store.proxies.value.map { it.id })
        assertEquals(listOf(0, 1, 2), store.proxies.value.map { it.order })
    }

    @Test
    fun `setDefault flags entry and null clears`() = runBlocking {
        val store = newStore()
        val a = store.save("a", config())
        store.setDefault(a.id)
        assertTrue(store.proxies.value.first { it.id == a.id }.isDefault)
        assertEquals(a.id, store.defaultId.value)
        store.setDefault(null)
        assertFalse(store.proxies.value.first { it.id == a.id }.isDefault)
        assertNull(store.defaultId.value)
    }

    @Test
    fun `setDefault ignores unknown id`() = runBlocking {
        val store = newStore()
        store.save("a", config())
        store.setDefault("nope")
        assertNull(store.defaultId.value)
    }

    @Test
    fun `state survives reconstruction from same persistence`() = runBlocking {
        val kv = MemKv()
        val store1 = newStore(kv)
        val a = store1.save("a", config(username = "u", password = "pw"))
        store1.save("b", config(host = "9.9.9.9"))
        store1.setDefault(a.id)

        // Simulate process restart: brand-new store over the same kv + cipher.
        val store2 = newStore(kv)
        assertEquals(listOf("a", "b"), store2.proxies.value.map { it.name })
        assertEquals(a.id, store2.defaultId.value)
        assertTrue(store2.proxies.value.first { it.id == a.id }.hasPassword)
        assertTrue(store2.proxies.value.first { it.id == a.id }.isDefault)
        assertEquals("pw", store2.resolve(a.id)!!.password)
    }

    @Test
    fun `free public origin is preserved`() = runBlocking {
        val store = newStore()
        val saved = store.save("free1", config(), origin = ProxyOrigin.FREE_PUBLIC)
        assertEquals(ProxyOrigin.FREE_PUBLIC, saved.origin)
        assertEquals(ProxyOrigin.FREE_PUBLIC, store.proxies.value.first().origin)
    }

    @Test
    fun `resolve unknown id is null`() = runBlocking {
        assertNull(newStore().resolve("ghost"))
    }
}
