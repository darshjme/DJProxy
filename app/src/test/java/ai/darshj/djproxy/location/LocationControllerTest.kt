package ai.darshj.djproxy.location

import ai.darshj.djproxy.vpn.seams.LocationCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the controller flow that the seams/task hang on: the OPT-IN pref gate, the honest
 * capability gate, exit-geo vs manual precedence, teardown, and the self-test wiring — all on the JVM
 * with fakes (no Android). The controller was refactored to inject `optedIn` / `capabilityProvider` /
 * a [MockPublisher] precisely so this flow is testable without Robolectric/Mockito.
 */
class LocationControllerTest {

    /** Records every publish/self-test the controller drives. */
    private class FakePublisher(
        var selfTestOutcome: SelfTestOutcome = SelfTestOutcome(
            probes = listOf(ProviderProbe("gps", accepted = true, readBack = ReadBack.Unreadable)),
            fusedActive = false,
        ),
    ) : MockPublisher {
        val started = mutableListOf<Fix>()
        val published = mutableListOf<Fix>()
        var stops = 0
        var grants = 0
        var selfTests = 0
        override fun ensureGrant(): Boolean { grants++; return true }
        override fun start(fix: Fix): Boolean { started.add(fix); return true }
        override fun publish(fix: Fix): Boolean { published.add(fix); return true }
        override fun stop() { stops++ }
        override fun selfTest(fix: Fix): SelfTestOutcome { selfTests++; return selfTestOutcome }
        override val fusedActive: Boolean get() = selfTestOutcome.fusedActive
        override val activeProviders: Set<String> get() = setOf("gps")
    }

    private class FakeTransport(private val bodies: Map<String, String?>) : GeoHttpTransport {
        override suspend fun get(host: String, path: String): String? = bodies[host]
    }

    /** ipinfo.io answering "Paris" for the exit-geo path. */
    private fun parisResolver(): () -> ExitGeoResolver? = {
        ExitGeoResolver(FakeTransport(mapOf("ipinfo.io" to """{"loc":"48.8566,2.3522","city":"Paris","country":"FR"}""")))
    }

    private fun controller(
        engine: FakePublisher,
        optedIn: Boolean,
        capability: LocationCapability,
        resolver: () -> ExitGeoResolver? = parisResolver(),
    ) = LocationControllerImpl(
        engine = engine,
        optedIn = { optedIn },
        capabilityProvider = { capability },
        // Unconfined so the async manual/clear launches complete inline for deterministic asserts.
        scope = CoroutineScope(Dispatchers.Unconfined),
        resolverFactory = resolver,
        republishIntervalMs = 60_000L, // republish loop sleeps immediately; we assert on the first push
    )

    // ---- pref gate --------------------------------------------------------------------------------

    @Test
    fun `opt-in OFF publishes nothing even when capability is ready`() = runBlocking {
        val engine = FakePublisher()
        val c = controller(engine, optedIn = false, capability = LocationCapability.READY_MOCK)
        c.onProxyConnected("104.28.1.1")
        assertNull("no fix while opt-in is off", c.current.value)
        assertTrue("engine never touched while opt-in is off", engine.started.isEmpty())
    }

    @Test
    fun `opt-in ON but capability UNAVAILABLE publishes nothing`() = runBlocking {
        val engine = FakePublisher()
        val c = controller(engine, optedIn = true, capability = LocationCapability.UNAVAILABLE)
        c.onProxyConnected("104.28.1.1")
        assertNull(c.current.value)
        assertTrue(engine.started.isEmpty())
        assertEquals(LocationCapability.UNAVAILABLE, c.capability.value)
    }

    // ---- exit-geo happy path ----------------------------------------------------------------------

    @Test
    fun `opt-in ON and ready publishes the resolved exit location`() = runBlocking {
        val engine = FakePublisher()
        val c = controller(engine, optedIn = true, capability = LocationCapability.READY_MOCK)
        c.onProxyConnected("104.28.1.1")
        val fix = c.current.value
        assertNotNull(fix)
        assertEquals(48.8566, fix!!.lat, 1e-6)
        assertEquals(2.3522, fix.lng, 1e-6)
        assertTrue(fix.source.startsWith("exit-geo:"))
        assertEquals(48.8566, engine.started.first().lat, 1e-6)
    }

    // ---- manual precedence ------------------------------------------------------------------------

    @Test
    fun `manual override takes precedence over exit-geo`() = runBlocking {
        val engine = FakePublisher()
        val c = controller(engine, optedIn = true, capability = LocationCapability.READY_MOCK)
        c.setManualLocation(10.0, 20.0)
        c.onProxyConnected("104.28.1.1") // resolver would say Paris; manual must win
        val fix = c.current.value
        assertNotNull(fix)
        assertEquals(10.0, fix!!.lat, 1e-9)
        assertEquals(20.0, fix.lng, 1e-9)
        assertEquals("manual", fix.source)
    }

    @Test
    fun `clearManual reverts to the exit-geo fix while connected`() = runBlocking {
        val engine = FakePublisher()
        val c = controller(engine, optedIn = true, capability = LocationCapability.READY_MOCK)
        c.onProxyConnected("104.28.1.1")
        c.setManualLocation(10.0, 20.0)
        c.clearManual() // Unconfined scope → completes inline
        val fix = c.current.value
        assertNotNull(fix)
        assertEquals(48.8566, fix!!.lat, 1e-6)
        assertTrue(fix.source.startsWith("exit-geo:"))
    }

    // ---- teardown ---------------------------------------------------------------------------------

    @Test
    fun `disconnect stops the engine and clears the published fix`() = runBlocking {
        val engine = FakePublisher()
        val c = controller(engine, optedIn = true, capability = LocationCapability.READY_MOCK)
        c.onProxyConnected("104.28.1.1")
        c.onProxyDisconnected()
        assertNull(c.current.value)
        assertTrue("engine.stop() called on teardown", engine.stops >= 1)
    }

    // ---- self-test --------------------------------------------------------------------------------

    @Test
    fun `self-test refuses to run when opt-in is off`() = runBlocking {
        val engine = FakePublisher()
        val c = controller(engine, optedIn = false, capability = LocationCapability.READY_MOCK)
        val r = c.runSelfTest()
        assertFalse(r.ran)
        assertFalse(r.passed)
        assertEquals(0, engine.selfTests)
    }

    @Test
    fun `self-test refuses to run when capability is unavailable`() = runBlocking {
        val engine = FakePublisher()
        val c = controller(engine, optedIn = true, capability = LocationCapability.UNAVAILABLE)
        val r = c.runSelfTest()
        assertFalse(r.ran)
        assertEquals(0, engine.selfTests)
    }

    @Test
    fun `self-test passes and reports write-confirmed when a provider accepts the known fix`() = runBlocking {
        val engine = FakePublisher(
            selfTestOutcome = SelfTestOutcome(
                probes = listOf(
                    ProviderProbe("gps", accepted = true, readBack = ReadBack.Match(48.8584, 2.2945, 3.0)),
                    ProviderProbe("network", accepted = true, readBack = ReadBack.Unreadable),
                ),
                fusedActive = false,
            ),
        )
        val c = controller(engine, optedIn = true, capability = LocationCapability.READY_MOCK)
        val r = c.runSelfTest()
        assertTrue(r.ran)
        assertTrue(r.passed)
        assertTrue(r.writeConfirmed)
        assertTrue(r.readBackConfirmed)
        assertEquals(1, engine.selfTests)
        // The known fix is the distinctive Eiffel Tower point.
        assertEquals(48.8584, r.target.lat, 1e-6)
    }

    @Test
    fun `self-test reports NOT active when every provider refuses`() = runBlocking {
        val engine = FakePublisher(
            selfTestOutcome = SelfTestOutcome(
                probes = listOf(
                    ProviderProbe("gps", accepted = false, readBack = ReadBack.Unreadable),
                    ProviderProbe("network", accepted = false, readBack = ReadBack.Unreadable),
                ),
                fusedActive = false,
            ),
        )
        val c = controller(engine, optedIn = true, capability = LocationCapability.READY_ROOT)
        val r = c.runSelfTest()
        assertTrue("it ran", r.ran)
        assertFalse("but did not pass — never fakes success", r.passed)
        assertFalse(r.writeConfirmed)
    }
}
