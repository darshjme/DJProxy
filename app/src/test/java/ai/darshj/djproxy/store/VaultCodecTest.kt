package ai.darshj.djproxy.store

import ai.darshj.djproxy.core.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure round-trip tests for [VaultCodec]. No Android, no JSON — exercises the hand-serialised blob
 * incl. adversarial names (delimiters, newlines, control chars) that must survive URL-encoding.
 */
class VaultCodecTest {

    private fun proxy(
        id: String,
        name: String = "n-$id",
        type: ProxyType = ProxyType.SOCKS5,
        host: String = "1.2.3.4",
        port: Int = 1080,
        username: String = "",
        dnsServer: String = "1.1.1.1",
        origin: ProxyOrigin = ProxyOrigin.USER,
        order: Int = 0,
    ) = SavedProxy(id, name, type, host, port, username, dnsServer, false, origin, false, order)

    @Test
    fun `empty list round-trips to empty`() {
        assertEquals("", VaultCodec.encode(emptyList()))
        assertEquals(emptyList<SavedProxy>(), VaultCodec.decode(""))
        assertEquals(emptyList<SavedProxy>(), VaultCodec.decode(null))
    }

    @Test
    fun `single entry round-trips exactly`() {
        val p = proxy("id1", name = "DE residential", type = ProxyType.HTTP,
            host = "proxy.example.com", port = 8080, username = "bob", origin = ProxyOrigin.FREE_PUBLIC, order = 3)
        val decoded = VaultCodec.decode(VaultCodec.encode(listOf(p)))
        assertEquals(1, decoded.size)
        assertEquals(p, decoded.first())
    }

    @Test
    fun `name with delimiter newline and control chars survives`() {
        val nasty = "weird␟name\nwith\ttabs and ␟ separators & = signs %20"
        val p = proxy("id2", name = nasty)
        val decoded = VaultCodec.decode(VaultCodec.encode(listOf(p)))
        assertEquals(1, decoded.size)
        assertEquals(nasty, decoded.first().name)
    }

    @Test
    fun `order is preserved and list stays ordered`() {
        val list = listOf(proxy("a", order = 0), proxy("b", order = 1), proxy("c", order = 2))
        val decoded = VaultCodec.decode(VaultCodec.encode(list))
        assertEquals(listOf("a", "b", "c"), decoded.map { it.id })
        assertEquals(listOf(0, 1, 2), decoded.map { it.order })
    }

    @Test
    fun `hasPassword and isDefault are not encoded and decode as false`() {
        val p = proxy("id3").copy(hasPassword = true, isDefault = true)
        val decoded = VaultCodec.decode(VaultCodec.encode(listOf(p))).first()
        assertTrue(!decoded.hasPassword)
        assertTrue(!decoded.isDefault)
    }

    @Test
    fun `malformed records are skipped not fatal`() {
        val good = VaultCodec.encode(listOf(proxy("ok")))
        val blob = "not-enough-fields\n$good\n\n   \n" +
            "id␟n␟SOCKS5␟h␟NOTANUMBER␟u␟1.1.1.1␟USER␟0" // bad port
        val decoded = VaultCodec.decode(blob)
        assertEquals(listOf("ok"), decoded.map { it.id })
    }

    @Test
    fun `blank dns decodes to default`() {
        val p = proxy("id4", dnsServer = "")
        val decoded = VaultCodec.decode(VaultCodec.encode(listOf(p))).first()
        assertEquals("1.1.1.1", decoded.dnsServer)
    }

    @Test
    fun `type and origin enums round-trip`() {
        val http = proxy("h", type = ProxyType.HTTP, origin = ProxyOrigin.FREE_PUBLIC)
        val decoded = VaultCodec.decode(VaultCodec.encode(listOf(http))).first()
        assertEquals(ProxyType.HTTP, decoded.type)
        assertEquals(ProxyOrigin.FREE_PUBLIC, decoded.origin)
    }
}
