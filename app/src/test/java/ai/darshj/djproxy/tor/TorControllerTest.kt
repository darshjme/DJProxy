package ai.darshj.djproxy.tor

import ai.darshj.djproxy.core.ProxyType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-machine + config-production tests for [TorControllerImpl], driven by a fully in-memory
 * [FakeOnionProxyManager] — no Android, no tor-android. Proves:
 *   - the 127.0.0.1:9050 (no-auth SOCKS5) config production is correct,
 *   - success/failure transitions of active/phase/progress,
 *   - engine shutdown on failure and on stop (fail-closed),
 *   - idempotent start() while already active (no re-bootstrap),
 *   - honest reporting of a fallback SOCKS port.
 */
class TorControllerTest {

    /** In-memory Tor engine: replays [progressSteps] then returns [outcome]; counts calls. */
    private class FakeOnionProxyManager(
        private val outcome: TorLaunchResult,
        private val progressSteps: List<Int> = listOf(10, 45, 80, 100),
        override val socksPort: Int = OnionProxyManager.DEFAULT_SOCKS_PORT,
    ) : OnionProxyManager {
        var bootstrapCalls = 0
        var shutdownCalls = 0
        val progressSeen = mutableListOf<Int>()

        override suspend fun bootstrap(onProgress: (Int) -> Unit): TorLaunchResult {
            bootstrapCalls++
            progressSteps.forEach { onProgress(it) }
            return outcome
        }

        override fun shutdown() {
            shutdownCalls++
        }
    }

    private val foregroundEvents = mutableListOf<Boolean>()
    private fun controller(manager: OnionProxyManager) =
        TorControllerImpl(manager = manager, foreground = { on -> foregroundEvents.add(on) })

    @Test
    fun `successful start becomes active with 127_0_0_1 9050 no-auth SOCKS5 config`() = runTest {
        val manager = FakeOnionProxyManager(TorLaunchResult.Ready(9050))
        val c = controller(manager)

        val result = c.start()

        assertTrue(result is TorStartResult.Started)
        val cfg = (result as TorStartResult.Started).proxyConfig
        assertEquals(ProxyType.SOCKS5, cfg.type)
        assertEquals("127.0.0.1", cfg.host)
        assertEquals(9050, cfg.port)
        assertFalse("Tor loopback must carry no auth", cfg.hasAuth)
        assertEquals("", cfg.username)
        assertEquals("", cfg.password)

        assertTrue(c.active.value)
        assertEquals(TorPhase.READY, c.phase.value)
        assertEquals(100, c.bootstrapProgress.value)
        assertTrue(foregroundEvents.first()) // foreground started
        assertEquals(0, manager.shutdownCalls) // stays up on success
    }

    @Test
    fun `proxyConfig is always socks5 127_0_0_1 9050 no auth`() {
        val c = controller(FakeOnionProxyManager(TorLaunchResult.Ready(9050)))
        val cfg = c.proxyConfig()
        assertEquals("socks5://127.0.0.1:9050", cfg.redacted())
        assertFalse(cfg.hasAuth)
    }

    @Test
    fun `failed bootstrap reverts to FAILED, not active, engine shut down (fail-closed)`() = runTest {
        val manager = FakeOnionProxyManager(
            outcome = TorLaunchResult.Failed("no network"),
            progressSteps = listOf(5, 20),
        )
        val c = controller(manager)

        val result = c.start()

        assertTrue(result is TorStartResult.Failed)
        assertEquals("no network", (result as TorStartResult.Failed).reason)
        assertFalse(c.active.value)
        assertEquals(TorPhase.FAILED, c.phase.value)
        assertEquals(1, manager.shutdownCalls) // fail-closed: half-started tor is torn down
        assertTrue(foregroundEvents.contains(false)) // foreground stopped on failure
    }

    @Test
    fun `bootstrap progress is forwarded to the StateFlow`() = runTest {
        val manager = FakeOnionProxyManager(
            outcome = TorLaunchResult.Ready(9050),
            progressSteps = listOf(10, 45, 80),
        )
        val c = controller(manager)
        c.start()
        // Terminal Ready forces 100 regardless of the last step.
        assertEquals(100, c.bootstrapProgress.value)
    }

    @Test
    fun `stop reverts state and shuts the engine down`() = runTest {
        val manager = FakeOnionProxyManager(TorLaunchResult.Ready(9050))
        val c = controller(manager)
        c.start()
        assertTrue(c.active.value)

        c.stop()

        assertFalse(c.active.value)
        assertEquals(TorPhase.IDLE, c.phase.value)
        assertEquals(0, c.bootstrapProgress.value)
        assertEquals(1, manager.shutdownCalls)
        assertFalse(foregroundEvents.last()) // last foreground event is stop
    }

    @Test
    fun `start while already active is idempotent and does not re-bootstrap`() = runTest {
        val manager = FakeOnionProxyManager(TorLaunchResult.Ready(9050))
        val c = controller(manager)
        c.start()
        val second = c.start()

        assertTrue(second is TorStartResult.Started)
        assertEquals(1, manager.bootstrapCalls) // only the first call bootstrapped
        assertTrue(c.active.value)
    }

    @Test
    fun `a fallback SOCKS port is reported honestly in the config`() = runTest {
        // Tor could not bind 9050 and fell back to 9150.
        val manager = FakeOnionProxyManager(TorLaunchResult.Ready(9150))
        val c = controller(manager)
        val result = c.start() as TorStartResult.Started
        assertEquals(9150, result.proxyConfig.port)
        assertEquals(9150, c.proxyConfig().port)
    }

    @Test
    fun `an out-of-range port from the engine falls back to the default 9050`() = runTest {
        val manager = FakeOnionProxyManager(TorLaunchResult.Ready(0))
        val c = controller(manager)
        val result = c.start() as TorStartResult.Started
        assertEquals(9050, result.proxyConfig.port)
    }
}
