package ai.darshj.djproxy.location

import android.content.Context
import ai.darshj.djproxy.vpn.VpnDependencies
import ai.darshj.djproxy.vpn.VpnRuntime
import ai.darshj.djproxy.vpn.seams.LocationCapability
import ai.darshj.djproxy.vpn.seams.LocationController
import ai.darshj.djproxy.vpn.seams.SpoofedLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The location lane's implementation of the [LocationController] seam. Registered into
 * [ai.darshj.djproxy.vpn.FeatureRegistry] by [LocationRegistrar]; core calls [onProxyConnected] on
 * CONNECTED and [onProxyDisconnected] on teardown — both MUST NOT throw, so every body here is
 * wrapped defensively.
 *
 * Flow on connect:
 *   1. Refresh the honest capability. If UNAVAILABLE we publish nothing and say so (never fake it).
 *   2. Choose the target: a manual override if set, else resolve the exit IP → geo THROUGH the proxy.
 *   3. Try to obtain the app-op grant (root self-grant if needed), start the mock providers, and keep
 *      republishing on an interval (Android expires/needs periodic test fixes for fused + updates).
 *
 * All coroutine work runs on an injected [scope] (SupervisorJob) so a geo-lookup failure can never
 * bubble into core's bring-up.
 */
class LocationControllerImpl(
    private val appContext: Context,
    private val engine: MockLocationEngine,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, _ -> /* never propagate into core */ },
    ),
    /** Builds the exit-geo resolver from the live proxy config; overridable for tests. */
    private val resolverFactory: () -> ExitGeoResolver? = { defaultResolver() },
    private val republishIntervalMs: Long = 1_000L,
) : LocationController {

    private val _capability = MutableStateFlow(LocationCapability.UNAVAILABLE)
    override val capability: StateFlow<LocationCapability> = _capability.asStateFlow()

    private val _current = MutableStateFlow<SpoofedLocation?>(null)
    override val current: StateFlow<SpoofedLocation?> = _current.asStateFlow()

    @Volatile
    private var manual: SpoofedLocation? = null

    @Volatile
    private var connected: Boolean = false

    @Volatile
    private var lastExitIp: String? = null

    /** Guards all [republishJob] transitions; mutated from onProxyConnected/setManual/clearManual/disconnect. */
    private val jobLock = Any()

    @Volatile
    private var republishJob: Job? = null

    override suspend fun onProxyConnected(exitIp: String?) {
        try {
            connected = true
            lastExitIp = exitIp
            refreshCapability(appContext)
            if (_capability.value == LocationCapability.UNAVAILABLE) {
                // Honest: no grant, no root → we cannot spoof. Publish nothing.
                _current.value = null
                return
            }
            // Root tier: try to self-grant the app-op so the providers will accept fixes.
            engine.ensureGrant()
            refreshCapability(appContext)

            val target = manual ?: resolveExit(exitIp)
            if (target == null) {
                _current.value = null
                return
            }
            beginPublishing(target)
        } catch (_: Throwable) {
            // Seam contract: MUST NOT throw.
        }
    }

    override fun onProxyDisconnected() {
        try {
            connected = false
            synchronized(jobLock) {
                republishJob?.cancel()
                republishJob = null
            }
            engine.stop()
            _current.value = null
        } catch (_: Throwable) {
            // MUST NOT throw.
        }
    }

    override fun setManualLocation(lat: Double, lng: Double) {
        val loc = SpoofedLocation(lat, lng, label = "Manual ($lat, $lng)", source = "manual")
        manual = loc
        // If we are live, switch to the manual fix immediately.
        if (connected && _capability.value != LocationCapability.UNAVAILABLE) {
            scope.launch { runCatching { beginPublishing(loc) } }
        }
    }

    override fun clearManual() {
        manual = null
        // Revert to exit-geo if we are live.
        if (connected && _capability.value != LocationCapability.UNAVAILABLE) {
            scope.launch {
                runCatching {
                    val target = resolveExit(lastExitIp)
                    if (target != null) beginPublishing(target) else stopPublishing()
                }
            }
        } else {
            stopPublishing()
        }
    }

    override fun refreshCapability(ctx: Context) {
        _capability.value = LocationCapabilityDetector.detect(ctx)
    }

    private suspend fun resolveExit(exitIp: String?): SpoofedLocation? {
        val resolver = runCatching { resolverFactory() }.getOrNull() ?: return null
        val ip = exitIp ?: VpnRuntime.lastValidatedExitIp
        return runCatching { resolver.resolve(ip) }.getOrNull()
    }

    private fun beginPublishing(target: SpoofedLocation) {
        val fix = Fix(target.lat, target.lng)
        // Cancel the old loop, push the first fix, and install the new loop ATOMICALLY so two
        // concurrent callers (onProxyConnected vs setManualLocation) cannot orphan a running loop.
        synchronized(jobLock) {
            republishJob?.cancel()
            _current.value = target
            // First push synchronously so getLastKnownLocation is immediately correct.
            engine.start(fix)
            republishJob = scope.launch {
                while (isActive && connected) {
                    runCatching { engine.publish(fix) }
                    delay(republishIntervalMs)
                }
            }
        }
    }

    private fun stopPublishing() {
        synchronized(jobLock) {
            republishJob?.cancel()
            republishJob = null
        }
        engine.stop()
        _current.value = null
    }

    companion object {
        /**
         * Builds an [ExitGeoResolver] whose HTTP GET is dialed through the CURRENT proxy config, so the
         * geo API sees the exit. Returns null if no config is applied yet (nothing to resolve through).
         */
        fun defaultResolver(): ExitGeoResolver? {
            val config = VpnRuntime.currentConfig ?: return null
            val dialer = runCatching {
                VpnDependencies.dialerFactory(config, VpnRuntime.protector)
            }.getOrNull() ?: return null
            return ExitGeoResolver(ProxyGeoTransport(dialer))
        }
    }
}
