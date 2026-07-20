package ai.darshj.djproxy.surfaces

import ai.darshj.djproxy.vpn.VpnStage
import ai.darshj.djproxy.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the surfaces state machine: the tile/widget appearance is a pure function of the read-only
 * [VpnState] (+ whether a last-validated config exists), with a busy-guard that forbids toggling
 * mid-transition. No device, no Robolectric, no core mutation.
 */
class ConnectTileMapperTest {

    private fun state(stage: VpnStage, redacted: String? = null) =
        VpnState(stage = stage, proxyRedacted = redacted)

    // ---- connect tile: up states -----------------------------------------------------------------

    @Test
    fun `connected is ACTIVE, toggleable, and shows the redacted region`() {
        val v = ConnectTileMapper.map(state(VpnStage.CONNECTED, "socks5://•••@1.2.3.4:1080"), hasConfig = true)
        assertEquals(TileVisualState.ACTIVE, v.state)
        assertTrue(v.toggleable)
        assertEquals("1.2.3.4:1080", v.subtitle)
        assertEquals("DJProxy", v.title)
    }

    @Test
    fun `connected without a redacted string falls back to Connected`() {
        val v = ConnectTileMapper.map(state(VpnStage.CONNECTED, null), hasConfig = true)
        assertEquals(TileVisualState.ACTIVE, v.state)
        assertEquals("Connected", v.subtitle)
    }

    @Test
    fun `reconnecting is ACTIVE but NOT toggleable (busy-guard)`() {
        val v = ConnectTileMapper.map(state(VpnStage.RECONNECTING), hasConfig = true)
        assertEquals(TileVisualState.ACTIVE, v.state)
        assertFalse(v.toggleable)
    }

    // ---- connect tile: transition states are busy-guarded ----------------------------------------

    @Test
    fun `validating connecting stopping are INACTIVE and NOT toggleable`() {
        for (stage in listOf(VpnStage.VALIDATING, VpnStage.CONNECTING, VpnStage.STOPPING)) {
            val v = ConnectTileMapper.map(state(stage), hasConfig = true)
            assertEquals("stage=$stage", TileVisualState.INACTIVE, v.state)
            assertFalse("stage=$stage must busy-guard", v.toggleable)
        }
    }

    // ---- connect tile: down states ---------------------------------------------------------------

    @Test
    fun `idle with a config is toggleable and invites connect`() {
        val v = ConnectTileMapper.map(state(VpnStage.IDLE), hasConfig = true)
        assertEquals(TileVisualState.INACTIVE, v.state)
        assertTrue(v.toggleable)
        assertEquals("Tap to connect", v.subtitle)
    }

    @Test
    fun `idle without a config still toggleable but says none configured`() {
        val v = ConnectTileMapper.map(state(VpnStage.IDLE), hasConfig = false)
        assertEquals(TileVisualState.INACTIVE, v.state)
        assertTrue(v.toggleable) // tap routes to opening the app
        assertEquals("No proxy configured", v.subtitle)
    }

    @Test
    fun `error with a config offers retry, without a config does not`() {
        val withCfg = ConnectTileMapper.map(state(VpnStage.ERROR), hasConfig = true)
        assertEquals(TileVisualState.INACTIVE, withCfg.state)
        assertTrue(withCfg.toggleable)
        assertEquals("Error · tap to retry", withCfg.subtitle)

        val noCfg = ConnectTileMapper.map(state(VpnStage.ERROR), hasConfig = false)
        assertFalse(noCfg.toggleable)
        assertEquals("Error", noCfg.subtitle)
    }

    // ---- region extraction -----------------------------------------------------------------------

    @Test
    fun `regionOf strips scheme and redacted credentials`() {
        assertEquals("1.2.3.4:1080", ConnectTileMapper.regionOf("socks5://•••@1.2.3.4:1080"))
        assertEquals("host.example:8080", ConnectTileMapper.regionOf("http://host.example:8080"))
        assertEquals("h:9", ConnectTileMapper.regionOf("h:9")) // no scheme, no auth
        assertNull(ConnectTileMapper.regionOf(null))
        assertNull(ConnectTileMapper.regionOf(""))
    }

    // ---- tor tile --------------------------------------------------------------------------------

    @Test
    fun `tor tile is UNAVAILABLE when the lane is absent`() {
        val v = TorTileMapper.map(available = false, active = false, bootstrapPct = 0)
        assertEquals(TileVisualState.UNAVAILABLE, v.state)
        assertFalse(v.toggleable)
    }

    @Test
    fun `tor tile is ACTIVE and toggleable when onion routing is live`() {
        val v = TorTileMapper.map(available = true, active = true, bootstrapPct = 100)
        assertEquals(TileVisualState.ACTIVE, v.state)
        assertTrue(v.toggleable)
        assertEquals(".onion enabled", v.subtitle)
    }

    @Test
    fun `tor tile shows bootstrap percent and is NOT toggleable mid-bootstrap`() {
        val v = TorTileMapper.map(available = true, active = false, bootstrapPct = 47)
        assertEquals(TileVisualState.INACTIVE, v.state)
        assertFalse(v.toggleable)
        assertTrue(v.subtitle.contains("47"))
    }

    @Test
    fun `tor tile invites enabling when available and idle`() {
        val v = TorTileMapper.map(available = true, active = false, bootstrapPct = 0)
        assertEquals(TileVisualState.INACTIVE, v.state)
        assertTrue(v.toggleable)
        assertEquals("Tap to enable", v.subtitle)
    }
}
