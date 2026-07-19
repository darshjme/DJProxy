package ai.darshj.djproxy.location

import ai.darshj.djproxy.vpn.seams.LocationCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecideCapabilityTest {

    @Test
    fun `grant on real device is READY_MOCK`() {
        assertEquals(LocationCapability.READY_MOCK, decideCapability(grant = true, emulator = false, rooted = false))
        assertEquals(LocationCapability.READY_MOCK, decideCapability(grant = true, emulator = false, rooted = true))
    }

    @Test
    fun `grant on emulator is READY_EMULATOR regardless of root`() {
        assertEquals(LocationCapability.READY_EMULATOR, decideCapability(grant = true, emulator = true, rooted = false))
        assertEquals(LocationCapability.READY_EMULATOR, decideCapability(grant = true, emulator = true, rooted = true))
    }

    @Test
    fun `no grant but root is READY_ROOT`() {
        assertEquals(LocationCapability.READY_ROOT, decideCapability(grant = false, emulator = false, rooted = true))
        // Even on an emulator, no grant + root routes through the self-grant tier.
        assertEquals(LocationCapability.READY_ROOT, decideCapability(grant = false, emulator = true, rooted = true))
    }

    @Test
    fun `no grant and no root is UNAVAILABLE and never fakes success`() {
        assertEquals(LocationCapability.UNAVAILABLE, decideCapability(grant = false, emulator = false, rooted = false))
        assertEquals(LocationCapability.UNAVAILABLE, decideCapability(grant = false, emulator = true, rooted = false))
    }
}

class IsEmulatorTest {

    private fun facts(
        fingerprint: String = "brand/prod/dev:14/UP1A/123:user/release-keys",
        model: String = "Pixel 8",
        manufacturer: String = "Google",
        brand: String = "google",
        device: String = "shiba",
        product: String = "shiba",
        hardware: String = "shiba",
        board: String = "shiba",
        qemuProp: String? = null,
    ) = BuildFacts(fingerprint, model, manufacturer, brand, device, product, hardware, board, qemuProp)

    @Test
    fun `real pixel is not an emulator`() {
        assertFalse(isEmulator(facts()))
    }

    @Test
    fun `qemu prop forces emulator`() {
        assertTrue(isEmulator(facts(qemuProp = "1")))
    }

    @Test
    fun `avd goldfish is an emulator`() {
        assertTrue(
            isEmulator(
                facts(
                    fingerprint = "generic/sdk_gphone_x86/generic:14/...",
                    model = "sdk_gphone_x86",
                    brand = "generic",
                    hardware = "goldfish",
                    product = "sdk_gphone_x86",
                ),
            ),
        )
    }

    @Test
    fun `ldplayer is an emulator`() {
        assertTrue(isEmulator(facts(model = "LDPlayer", hardware = "android_x86", product = "ldplayer", board = "android_x86")))
    }

    @Test
    fun `genymotion is an emulator`() {
        assertTrue(isEmulator(facts(manufacturer = "Genymotion", hardware = "vbox86", product = "vbox86p")))
    }
}

/** The provider state machine (pure) exercised with a recording fake sink. */
class MockProviderMachineTest {

    private class FakeSink(
        /** Providers the platform "refuses" to add (simulating a missing app-op). */
        private val refuse: Set<String> = emptySet(),
    ) : LocationSink {
        val added = mutableListOf<String>()
        val enabled = mutableListOf<String>()
        val disabled = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val pushed = mutableListOf<Pair<String, Fix>>()

        override fun add(name: String): Boolean {
            if (name in refuse) return false
            added.add(name); return true
        }
        override fun enable(name: String) { enabled.add(name) }
        override fun disable(name: String) { disabled.add(name) }
        override fun remove(name: String) { removed.add(name) }
        override fun push(name: String, fix: Fix): Boolean { pushed.add(name to fix); return true }
    }

    private val providers = listOf("gps", "network")

    @Test
    fun `publish before start is a no-op`() {
        val sink = FakeSink()
        val m = MockProviderMachine(sink, providers)
        assertFalse(m.publish(Fix(1.0, 2.0)))
        assertTrue(sink.pushed.isEmpty())
        assertEquals(MockState.STOPPED, m.state)
    }

    @Test
    fun `start adds and enables all granted providers`() {
        val sink = FakeSink()
        val m = MockProviderMachine(sink, providers)
        assertTrue(m.start())
        assertEquals(MockState.ACTIVE, m.state)
        assertEquals(listOf("gps", "network"), sink.added)
        assertEquals(listOf("gps", "network"), sink.enabled)
        assertEquals(setOf("gps", "network"), m.active)
    }

    @Test
    fun `a refused provider is never marked active and never receives a fix`() {
        val sink = FakeSink(refuse = setOf("network"))
        val m = MockProviderMachine(sink, providers)
        assertTrue(m.start()) // gps still worked
        assertEquals(setOf("gps"), m.active)
        m.publish(Fix(10.0, 20.0))
        assertEquals(listOf("gps"), sink.pushed.map { it.first })
    }

    @Test
    fun `start returns false when every provider is refused`() {
        val sink = FakeSink(refuse = setOf("gps", "network"))
        val m = MockProviderMachine(sink, providers)
        assertFalse(m.start())
        assertTrue(m.active.isEmpty())
        assertFalse(m.publish(Fix(1.0, 1.0)))
    }

    @Test
    fun `publish pushes to every active provider`() {
        val sink = FakeSink()
        val m = MockProviderMachine(sink, providers)
        m.start()
        assertTrue(m.publish(Fix(37.0, -122.0)))
        assertEquals(setOf("gps", "network"), sink.pushed.map { it.first }.toSet())
    }

    @Test
    fun `stop disables and removes all providers and is idempotent`() {
        val sink = FakeSink()
        val m = MockProviderMachine(sink, providers)
        m.start()
        m.stop()
        assertEquals(MockState.STOPPED, m.state)
        assertEquals(setOf("gps", "network"), sink.disabled.toSet())
        assertEquals(setOf("gps", "network"), sink.removed.toSet())
        assertTrue(m.active.isEmpty())
        // Idempotent: second stop does nothing more.
        val removedCount = sink.removed.size
        m.stop()
        assertEquals(removedCount, sink.removed.size)
    }

    @Test
    fun `start is idempotent`() {
        val sink = FakeSink()
        val m = MockProviderMachine(sink, providers)
        m.start()
        m.start() // must not re-add
        assertEquals(listOf("gps", "network"), sink.added)
    }
}
