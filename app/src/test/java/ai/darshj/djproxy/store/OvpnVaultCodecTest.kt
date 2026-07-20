package ai.darshj.djproxy.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure round-trip tests for [OvpnVaultCodec] — the `.ovpn` vault metadata codec. No Android, no JSON;
 * exercises the hand-serialised blob incl. adversarial names (delimiters, newlines, control chars) that
 * must survive URL-encoding. Mirrors [VaultCodecTest].
 */
class OvpnVaultCodecTest {

    private fun profile(
        id: String,
        name: String = "n-$id",
        countryShort: String = "JP",
        countryLong: String = "Japan",
        hostName: String = "public-vpn-$id",
        order: Int = 0,
    ) = SavedOvpn(id, name, countryShort, countryLong, hostName, order)

    @Test
    fun `empty list round-trips to empty`() {
        assertEquals("", OvpnVaultCodec.encode(emptyList()))
        assertEquals(emptyList<SavedOvpn>(), OvpnVaultCodec.decode(""))
        assertEquals(emptyList<SavedOvpn>(), OvpnVaultCodec.decode(null))
    }

    @Test
    fun `single entry round-trips exactly`() {
        val p = profile(
            "id1",
            name = "Tokyo relay",
            countryShort = "JP",
            countryLong = "Japan",
            hostName = "public-vpn-219",
            order = 3,
        )
        val decoded = OvpnVaultCodec.decode(OvpnVaultCodec.encode(listOf(p)))
        assertEquals(1, decoded.size)
        assertEquals(p, decoded.first())
    }

    @Test
    fun `name with delimiter newline and control chars survives`() {
        val nasty = "weird␟name\nwith\ttabs and ␟ separators & = signs %20"
        val p = profile("id2", name = nasty)
        val decoded = OvpnVaultCodec.decode(OvpnVaultCodec.encode(listOf(p)))
        assertEquals(1, decoded.size)
        assertEquals(nasty, decoded.first().name)
    }

    @Test
    fun `country fields with commas and unicode round-trip`() {
        val p = profile("id5", countryShort = "CI", countryLong = "Côte d'Ivoire, région")
        val decoded = OvpnVaultCodec.decode(OvpnVaultCodec.encode(listOf(p))).first()
        assertEquals("CI", decoded.countryShort)
        assertEquals("Côte d'Ivoire, région", decoded.countryLong)
    }

    @Test
    fun `order is preserved and list stays ordered`() {
        val list = listOf(profile("a", order = 0), profile("b", order = 1), profile("c", order = 2))
        val decoded = OvpnVaultCodec.decode(OvpnVaultCodec.encode(list))
        assertEquals(listOf("a", "b", "c"), decoded.map { it.id })
        assertEquals(listOf(0, 1, 2), decoded.map { it.order })
    }

    @Test
    fun `malformed records are skipped not fatal`() {
        val good = OvpnVaultCodec.encode(listOf(profile("ok")))
        val blob = "not-enough-fields\n$good\n\n   \n" +
            "id␟n␟JP␟Japan␟host" // one field short (5 not 6) → skipped
        val decoded = OvpnVaultCodec.decode(blob)
        assertEquals(listOf("ok"), decoded.map { it.id })
    }

    @Test
    fun `record with empty id is skipped`() {
        val blob = "␟name␟JP␟Japan␟host␟0"
        assertTrue(OvpnVaultCodec.decode(blob).isEmpty())
    }

    @Test
    fun `unparseable order defaults to zero`() {
        val blob = "id␟n␟JP␟Japan␟host␟NOTANUMBER"
        val decoded = OvpnVaultCodec.decode(blob)
        assertEquals(1, decoded.size)
        assertEquals(0, decoded.first().order)
    }
}
