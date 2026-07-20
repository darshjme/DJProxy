package ai.darshj.djproxy.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure test of the self-test path on [MockProviderMachine]: write a known fix, read it back, and
 * classify the read as Match / Mismatch / Unreadable — plus the honesty invariants (a refused provider
 * is never reported as accepting, a read that comes back null is Unreadable, not a fake Match).
 */
class MockSelfTestTest {

    /** A sink that accepts writes for granted providers and replays a canned read-back per provider. */
    private class ReadableSink(
        private val refuse: Set<String> = emptySet(),
        private val readBacks: Map<String, Fix?> = emptyMap(),
    ) : LocationSink {
        override fun add(name: String): Boolean = name !in refuse
        override fun enable(name: String) {}
        override fun disable(name: String) {}
        override fun remove(name: String) {}
        override fun push(name: String, fix: Fix): Boolean = name !in refuse
        override fun read(name: String): Fix? = readBacks[name]
    }

    private val providers = listOf("gps", "network")
    private val target = Fix(48.8584, 2.2945)

    @Test
    fun `exact read-back is a Match`() {
        val sink = ReadableSink(readBacks = mapOf("gps" to target, "network" to target))
        val probes = MockProviderMachine(sink, providers).selfTest(target)
        assertEquals(2, probes.size)
        assertTrue(probes.all { it.accepted })
        assertTrue(probes.all { it.readBack is ReadBack.Match })
        val gps = probes.first { it.provider == "gps" }.readBack as ReadBack.Match
        assertTrue("match distance is ~0 m", gps.distanceM < 1.0)
    }

    @Test
    fun `a far read-back is a Mismatch`() {
        // ~2 km away from the target.
        val far = Fix(48.8760, 2.2945)
        val sink = ReadableSink(readBacks = mapOf("gps" to far, "network" to far))
        val probes = MockProviderMachine(sink, providers).selfTest(target)
        val gps = probes.first { it.provider == "gps" }.readBack
        assertTrue(gps is ReadBack.Mismatch)
        assertTrue((gps as ReadBack.Mismatch).distanceM > 100.0)
    }

    @Test
    fun `accepted write with null read-back is Unreadable, never a fake Match`() {
        // Models the shipping build: the write lands, but the app cannot read location back.
        val sink = ReadableSink(readBacks = mapOf("gps" to null, "network" to null))
        val probes = MockProviderMachine(sink, providers).selfTest(target)
        assertTrue("writes were accepted", probes.all { it.accepted })
        assertTrue("read-back is honestly Unreadable", probes.all { it.readBack is ReadBack.Unreadable })
    }

    @Test
    fun `a refused provider is reported as not accepted and Unreadable`() {
        val sink = ReadableSink(refuse = setOf("network"), readBacks = mapOf("gps" to target))
        val probes = MockProviderMachine(sink, providers).selfTest(target)
        val network = probes.first { it.provider == "network" }
        assertFalse("refused provider never claims acceptance", network.accepted)
        assertTrue(network.readBack is ReadBack.Unreadable)
        // gps still worked and read back.
        assertTrue(probes.first { it.provider == "gps" }.accepted)
        assertTrue(probes.first { it.provider == "gps" }.readBack is ReadBack.Match)
    }

    @Test
    fun `haversine is symmetric and near-zero for identical points`() {
        assertEquals(0.0, haversineMeters(10.0, 20.0, 10.0, 20.0), 1e-6)
        val a = haversineMeters(48.8584, 2.2945, 48.8600, 2.3000)
        val b = haversineMeters(48.8600, 2.3000, 48.8584, 2.2945)
        assertEquals(a, b, 1e-6)
        assertTrue(a > 0.0)
    }
}
