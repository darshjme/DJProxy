package ai.darshj.djproxy.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CRUD + persistence tests for [SharedPreferencesOvpnVault], using an in-memory [KeyValueStore]. No
 * device, JVM-only. Mirrors [ProxyStoreTest] — minus the crypto path (VPN Gate profiles are anonymous,
 * so the `.ovpn` text is stored verbatim, never encrypted).
 */
class OvpnVaultTest {

    /** In-memory KV standing in for the `djproxy_ovpn_vault` SharedPreferences file. */
    private class MemKv(val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private val sampleOvpn = "client\ndev tun\nproto udp\nremote 1.2.3.4 1194\n"

    private fun newVault(kv: MemKv = MemKv()) = SharedPreferencesOvpnVault(kv)

    @Test
    fun `save assigns id order and appears in flow`() = runBlocking {
        val vault = newVault()
        val saved = vault.save("Tokyo", "JP", "Japan", "public-vpn-1", sampleOvpn)
        assertTrue(saved.id.isNotEmpty())
        assertEquals(0, saved.order)
        assertEquals(listOf(saved.id), vault.saved.value.map { it.id })
        val second = vault.save("Seoul", "KR", "Korea", "public-vpn-2", sampleOvpn)
        assertEquals(1, second.order)
    }

    @Test
    fun `blank name falls back to hostName`() = runBlocking {
        val vault = newVault()
        val saved = vault.save("", "JP", "Japan", "public-vpn-9", sampleOvpn)
        assertEquals("public-vpn-9", saved.name)
    }

    @Test
    fun `ovpn text is persisted under per-id key and resolves back`() = runBlocking {
        val kv = MemKv()
        val vault = newVault(kv)
        val saved = vault.save("Tokyo", "JP", "Japan", "public-vpn-1", sampleOvpn)
        // Text blob stored under ovpn.<id>, kept OUT of the compact metadata index.
        assertEquals(sampleOvpn, kv.map["ovpn.${saved.id}"])
        assertFalse(kv.map[SharedPreferencesOvpnVault.KEY_VAULT]!!.contains(sampleOvpn))
        assertEquals(sampleOvpn, vault.resolve(saved.id))
    }

    @Test
    fun `delete removes entry and its ovpn text blob`() = runBlocking {
        val kv = MemKv()
        val vault = newVault(kv)
        val a = vault.save("a", "JP", "Japan", "h-a", sampleOvpn)
        vault.delete(a.id)
        assertTrue(vault.saved.value.isEmpty())
        assertNull(kv.map["ovpn.${a.id}"])
        assertNull(vault.resolve(a.id))
    }

    @Test
    fun `reorder persists new order`() = runBlocking {
        val vault = newVault()
        val a = vault.save("a", "JP", "Japan", "h-a", sampleOvpn)
        val b = vault.save("b", "KR", "Korea", "h-b", sampleOvpn)
        val c = vault.save("c", "US", "United States", "h-c", sampleOvpn)
        vault.reorder(listOf(c.id, a.id, b.id))
        assertEquals(listOf(c.id, a.id, b.id), vault.saved.value.map { it.id })
        assertEquals(listOf(0, 1, 2), vault.saved.value.map { it.order })
    }

    @Test
    fun `state survives reconstruction from same persistence`() = runBlocking {
        val kv = MemKv()
        val vault1 = newVault(kv)
        val a = vault1.save("a", "JP", "Japan", "h-a", sampleOvpn)
        vault1.save("b", "KR", "Korea", "h-b", "client\nremote 9.9.9.9 1194\n")

        // Simulate process restart: brand-new vault over the same kv.
        val vault2 = newVault(kv)
        assertEquals(listOf("a", "b"), vault2.saved.value.map { it.name })
        assertEquals(sampleOvpn, vault2.resolve(a.id))
    }

    @Test
    fun `resolve unknown id is null`() = runBlocking {
        assertNull(newVault().resolve("ghost"))
    }

    @Test
    fun `delete unknown id is a no-op`() = runBlocking {
        val vault = newVault()
        vault.save("a", "JP", "Japan", "h-a", sampleOvpn)
        vault.delete("ghost")
        assertEquals(1, vault.saved.value.size)
    }
}
