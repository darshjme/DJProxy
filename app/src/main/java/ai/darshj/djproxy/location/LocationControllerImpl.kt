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
    private val engine: MockPublisher,
    /**
     * The opt-in gate: the user's EXPLICIT choice (default binds
     * [ai.darshj.djproxy.ui.LocationPreference]). Injected as a plain lambda so the pref-gate flow is
     * unit-testable on the JVM with no Context. We NEVER publish a mock unless this returns true.
     */
    private val optedIn: () -> Boolean,
    /** Honest capability detection (default binds [LocationCapabilityDetector.detect]); injectable for tests. */
    private val capabilityProvider: () -> LocationCapability,
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
            // Opt-in gate: location matching is OFF unless the user explicitly chose it (onboarding
            // choice / settings toggle). Without opt-in we NEVER publish a mock fix, regardless of the
            // OS mock-location grant. [ai.darshj.djproxy.ui.LocationPreference] is the single source.
            if (!optedIn()) {
                _current.value = null
                return
            }
            refreshCap()
            if (_capability.value == LocationCapability.UNAVAILABLE) {
                // Honest: no grant, no root → we cannot spoof. Publish nothing.
                _current.value = null
                return
            }
            // Root tier: try to self-grant the app-op so the providers will accept fixes.
            engine.ensureGrant()
            refreshCap()

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
        // The seam passes a Context, but detection is bound in [capabilityProvider] (closed over the app
        // context by the registrar). We ignore the argument so the flow stays JVM-testable.
        refreshCap()
    }

    private fun refreshCap() {
        _capability.value = runCatching { capabilityProvider() }.getOrDefault(LocationCapability.UNAVAILABLE)
    }

    /**
     * Self-test the mock-location path end to end (task: "Test mock location"): set a KNOWN fix and read
     * it back to confirm the providers took it, then restore whatever was live. Honest by construction —
     * it reports the real per-provider write result and the best-effort read-back, and refuses to run at
     * all when the opt-in is off or capability is [LocationCapability.UNAVAILABLE]. Never throws.
     *
     * Callable by the ui via the concrete controller ([LocationSettingsPanel] hosts the button); the
     * [LocationController] seam is core-owned and cannot gain this method, so it lives here.
     */
    suspend fun runSelfTest(): SelfTestReport {
        return try {
            if (!optedIn()) {
                return SelfTestReport.notRun(
                    _capability.value,
                    "Location matching is off — turn it on above, then test.",
                )
            }
            refreshCap()
            if (_capability.value == LocationCapability.UNAVAILABLE) {
                return SelfTestReport.notRun(
                    _capability.value,
                    "No mock-location grant — self-test can't run. Set DJProxy as the mock-location app first.",
                )
            }
            engine.ensureGrant()
            refreshCap()
            val cap = _capability.value

            // Pause any live republish loop so it cannot fight the test fix mid-probe.
            val restore: SpoofedLocation? = _current.value
            synchronized(jobLock) {
                republishJob?.cancel()
                republishJob = null
            }

            val outcome = engine.selfTest(SELF_TEST_FIX)

            // Restore reality: re-arm the live fix if we were publishing one; otherwise fully stop so we
            // never leave a spoofed location running while disconnected/idle.
            if (connected && restore != null) {
                beginPublishing(restore)
            } else {
                engine.stop()
                _current.value = null
            }

            SelfTestReport(
                ran = true,
                capability = cap,
                target = SELF_TEST_FIX,
                probes = outcome.probes,
                fusedActive = outcome.fusedActive,
                summary = summarize(outcome),
            )
        } catch (_: Throwable) {
            SelfTestReport.notRun(_capability.value, "Self-test hit an unexpected error.")
        }
    }

    private fun summarize(o: SelfTestOutcome): String {
        val accepted = o.probes.filter { it.accepted }.map { shortName(it.provider) }
        val matched = o.probes.any { it.readBack is ReadBack.Match }
        return when {
            accepted.isEmpty() && !o.fusedActive ->
                "Providers refused the fix — spoofing is NOT active. Check that DJProxy is the mock-location app."
            matched ->
                "Confirmed: ${accepted.joinToString("+")} accepted the known fix and it read back at the test point."
            accepted.isNotEmpty() ->
                "${accepted.joinToString("+")} accepted the known fix (write OK). Read-back is skipped without " +
                    "system location-read permission, so the write is the authoritative signal."
            else ->
                "Fused mock is active; the LocationManager test providers did not accept the fix on this device."
        }
    }

    private fun shortName(provider: String): String = when (provider) {
        "gps" -> "GPS"
        "network" -> "network"
        else -> provider
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
         * The known fix the self-test writes and reads back. A distinctive real-world point (the Eiffel
         * Tower) so a human eyeballing Google Maps during the test immediately sees the jump and it can
         * never be confused with a plausible real GPS reading.
         */
        val SELF_TEST_FIX = Fix(lat = 48.8584, lng = 2.2945, accuracyM = 5f)

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

/**
 * The result of [LocationControllerImpl.runSelfTest], surfaced in [LocationSettingsPanel]. [ran] is
 * false when the self-test was refused up front (opt-in off / capability unavailable) — that is not a
 * failure, it is an honest "can't test". When [ran] is true, [writeConfirmed] is the authoritative
 * proof the providers took the fix; [readBackConfirmed] is the stronger (permission-dependent) proof.
 */
data class SelfTestReport(
    val ran: Boolean,
    val summary: String,
    val capability: LocationCapability,
    val target: Fix,
    val probes: List<ProviderProbe>,
    val fusedActive: Boolean,
) {
    /** At least one test provider accepted the written fix (the in-process authoritative signal). */
    val writeConfirmed: Boolean get() = probes.any { it.accepted }

    /** The fix read back within tolerance — only possible when the app can also read location. */
    val readBackConfirmed: Boolean get() = probes.any { it.readBack is ReadBack.Match }

    /** Overall: the test ran and the platform demonstrably took the fix (write or fused). */
    val passed: Boolean get() = ran && (writeConfirmed || fusedActive)

    companion object {
        fun notRun(capability: LocationCapability, why: String): SelfTestReport = SelfTestReport(
            ran = false,
            summary = why,
            capability = capability,
            target = LocationControllerImpl.SELF_TEST_FIX,
            probes = emptyList(),
            fusedActive = false,
        )
    }
}
