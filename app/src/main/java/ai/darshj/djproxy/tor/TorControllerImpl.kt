package ai.darshj.djproxy.tor

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyType
import ai.darshj.djproxy.vpn.LogBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The tor lane's [TorController] implementation. Pure state machine over an injected [OnionProxyManager]
 * (the tor engine) and an injected [foreground] hook (start/stop the branded [TorService]) — so the
 * whole thing is unit-tested with a fake engine and a no-op foreground, no Android needed.
 *
 * State model:
 * ```
 *   IDLE ──start()──▶ BOOTSTRAPPING ──(engine Ready)──▶ READY   (active=true, progress=100)
 *                                   └─(engine Failed)─▶ FAILED  (active=false, engine shut down)
 *   any ──stop()──▶ IDLE           (active=false, progress=0, engine shut down)
 * ```
 * The produced [proxyConfig] is `socks5://127.0.0.1:<port>` with NO auth — handed to the EXISTING
 * `VpnController.apply`, which then routes the whole device through Tor. `.onion` then works over the
 * existing MapDNS + SOCKS5-domain-CONNECT path with no special-casing (reasoning proven in
 * [TorController]'s KDoc). No core file is touched.
 *
 * Failure / no-network / user-disable are all handled here: a failed bootstrap reverts to IDLE-adjacent
 * FAILED and shuts the engine down (fail-closed — we never leave a half-started tor around), and [stop]
 * always reverts cleanly. Every public body is defensive: the seam contract is "never throw".
 */
class TorControllerImpl(
    private val manager: OnionProxyManager,
    /** Start (true) / stop (false) the branded foreground [TorService]. No-op default for tests. */
    private val foreground: (Boolean) -> Unit = {},
) : TorController {

    private val _bootstrapProgress = MutableStateFlow(0)
    override val bootstrapProgress: StateFlow<Int> = _bootstrapProgress.asStateFlow()

    private val _active = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _phase = MutableStateFlow(TorPhase.IDLE)
    override val phase: StateFlow<TorPhase> = _phase.asStateFlow()

    /** The real SOCKS port Tor settled on; 9050 unless the engine had to fall back. */
    @Volatile
    private var resolvedPort: Int = OnionProxyManager.DEFAULT_SOCKS_PORT

    /** Serialises start() so two taps cannot double-bootstrap. */
    private val startLock = Mutex()

    override suspend fun start(): TorStartResult {
        return try {
            startLock.withLock { startLocked() }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Seam contract: never throw.
            _active.value = false
            _phase.value = TorPhase.FAILED
            runCatching { manager.shutdown() }
            runCatching { foreground(false) }
            TorStartResult.Failed(t.message ?: "Tor failed to start.")
        }
    }

    private suspend fun startLocked(): TorStartResult {
        // Idempotent: already up → hand back the live config without re-bootstrapping.
        if (_active.value) return TorStartResult.Started(proxyConfig())

        _phase.value = TorPhase.BOOTSTRAPPING
        _bootstrapProgress.value = 0
        runCatching { foreground(true) }

        val result = manager.bootstrap { pct ->
            _bootstrapProgress.value = pct.coerceIn(0, 100)
        }

        return when (result) {
            is TorLaunchResult.Ready -> {
                resolvedPort = result.socksPort.takeIf { it in 1..65535 }
                    ?: OnionProxyManager.DEFAULT_SOCKS_PORT
                _bootstrapProgress.value = 100
                _active.value = true
                _phase.value = TorPhase.READY
                LogBus.i(TAG, "Tor enabled — routing via SOCKS5 127.0.0.1:$resolvedPort (.onion enabled)")
                TorStartResult.Started(proxyConfig())
            }

            is TorLaunchResult.Failed -> {
                _active.value = false
                _phase.value = TorPhase.FAILED
                runCatching { manager.shutdown() }
                runCatching { foreground(false) }
                LogBus.w(TAG, "Tor failed to start: ${result.reason}")
                TorStartResult.Failed(result.reason)
            }
        }
    }

    override fun stop() {
        runCatching {
            val wasActive = _active.value
            _active.value = false
            _phase.value = TorPhase.IDLE
            _bootstrapProgress.value = 0
            runCatching { manager.shutdown() }
            runCatching { foreground(false) }
            if (wasActive) LogBus.i(TAG, "Tor disabled — reverting to normal routing")
        }
    }

    override fun proxyConfig(): ProxyConfig = ProxyConfig(
        type = ProxyType.SOCKS5,
        host = OnionProxyManager.LOOPBACK_HOST,
        port = resolvedPort,
        // No auth for a Tor loopback SOCKS5; dnsServer left at the core default (1.1.1.1 over TCP through
        // the exit) — .onion never needs it (resolved inside Tor via SOCKS domain-CONNECT).
    )

    companion object {
        private const val TAG = "Tor"
    }
}
