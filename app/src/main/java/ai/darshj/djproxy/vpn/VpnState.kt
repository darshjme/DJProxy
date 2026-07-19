package ai.darshj.djproxy.vpn

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.proxy.ProxyError
import ai.darshj.djproxy.proxy.ValidationResult
import kotlinx.coroutines.flow.StateFlow

/** Coarse lifecycle of the tunnel, driven by the VpnController and rendered by the UI. */
enum class VpnStage {
    /** No tunnel; nothing routed. */
    IDLE,

    /** Running the pre-flight (real connect + handshake + probe). VPN is NOT up yet. */
    VALIDATING,

    /** Validation passed; establishing tun + starting the engine. */
    CONNECTING,

    /** Tun up, routes held (0.0.0.0/0 + ::/0 blackhole), traffic flowing through the proxy. */
    CONNECTED,

    /** Engine died or link changed; routes are HELD (fail-closed, traffic dropping) while we recover. */
    RECONNECTING,

    /** Tearing down. */
    STOPPING,

    /** Terminal failure; [VpnState.error] explains why. Routes are down. */
    ERROR,
}

/** Tri-state health of one advisory indicator (§2.3). UNKNOWN = the probe could not decide. */
enum class Health { OK, DEGRADED, UNKNOWN }

/**
 * Advisory post-connect health (§2.3). This is NOT a gate: nothing here can block, tear down, or
 * throw. It is rendered by the UI as non-blocking chips. `OK` means "behaving as the leak model
 * intends" (v6 blackholed, UDP dropped, DNS resolving through the proxy).
 */
data class HealthReport(
    val ipv6: Health = Health.UNKNOWN,          // OK = blackholed as intended
    val udp: Health = Health.UNKNOWN,           // OK = dropped as intended
    val dns: Health = Health.UNKNOWN,           // OK = resolved through proxy
    val activeDnsStrategy: String = "",         // "DoH:443" | "DoT:853" | "TCP:53"
    val emulatorBypassSuspected: Boolean = false,
    val checkedAtMs: Long = 0,
) {
    val hasWarnings: Boolean
        get() = ipv6 == Health.DEGRADED || udp == Health.DEGRADED ||
            dns == Health.DEGRADED || emulatorBypassSuspected
    // NOTE: there is NO allPass gate anymore. Nothing blocks CONNECTED on this.
}

/**
 * DEPRECATED back-compat shim for the v2 leak self-test report. In v3 the leak checks are advisory
 * ([HealthReport]) and never gate CONNECTED; this type is retained only so a not-yet-migrated UI
 * surface keeps compiling. Core no longer populates [VpnState.leakChecks] (it is always null now).
 */
@Deprecated("Replaced by HealthReport (advisory). Migrate UI to VpnState.health.")
data class LeakCheckReport(
    val ipv6Unreachable: Boolean = false,
    val udpBlocked: Boolean = false,
    val dnsTunnelled: Boolean = false,
    val checkedAtMs: Long = 0,
) {
    val allPass: Boolean get() = ipv6Unreachable && udpBlocked && dnsTunnelled
}

/**
 * The complete, immutable snapshot the UI binds to. Published as a [StateFlow] by the service.
 * Everything the status card needs is here; the UI computes uptime from [connectedSinceMs].
 */
data class VpnState(
    val stage: VpnStage = VpnStage.IDLE,
    /** Redacted proxy string for display (never the password). Null until a proxy is applied. */
    val proxyRedacted: String? = null,
    /** Epoch millis when CONNECTED began, or 0 when not connected. UI derives uptime from this. */
    val connectedSinceMs: Long = 0,
    val stats: TunnelStats = TunnelStats.EMPTY,
    /** Set only in ERROR (and echoed on a failed apply). */
    val error: ProxyError? = null,
    /** Advisory post-connect health; null until the first HealthMonitor pass. Never gates CONNECTED. */
    val health: HealthReport? = null,
    /** DEPRECATED: always null in v3 (leak checks are advisory now). Kept for UI back-compat only. */
    @Deprecated("Use health (HealthReport).")
    val leakChecks: LeakCheckReport? = null,
) {
    val isUp: Boolean get() = stage == VpnStage.CONNECTED || stage == VpnStage.RECONNECTING
    val isBusy: Boolean get() = stage == VpnStage.VALIDATING || stage == VpnStage.CONNECTING || stage == VpnStage.STOPPING

    companion object {
        val IDLE = VpnState()
    }
}

/**
 * The one control surface the UI talks to. Implemented in the vpn lane; it owns the tun, the
 * routes, the single protect() seam, the engine sidecar, and the LocalSocksServer.
 *
 * [apply] is the whole product's contract: it VALIDATES FIRST and only brings the tunnel up on a
 * genuine [ValidationResult.Success]. On failure it returns the typed result and stays down
 * (fail-closed) — it must never route traffic on a failed or unvalidated config.
 */
interface VpnController {
    val state: StateFlow<VpnState>

    /**
     * Validate the config for real, then (only on success) bring the device-wide tunnel up and
     * run the leak self-test. Returns the validation outcome either way; a [ValidationResult.Failure]
     * means nothing was routed.
     */
    suspend fun apply(config: ProxyConfig): ValidationResult

    /** Tear the tunnel down and drop all routes. Idempotent. */
    fun stop()
}
