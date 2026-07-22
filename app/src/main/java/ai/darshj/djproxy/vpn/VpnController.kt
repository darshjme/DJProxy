package ai.darshj.djproxy.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyType
import ai.darshj.djproxy.proxy.ProxyError
import ai.darshj.djproxy.proxy.SocketProtector
import ai.darshj.djproxy.proxy.UpstreamDialer
import ai.darshj.djproxy.proxy.ValidationResult
import ai.darshj.djproxy.proxy.Validator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The loopback SOCKS front the engine dials (the proxy lane's LocalSocksServer implements this at
 * integration). It is the ONLY policy front in the main process; the vpn lane starts/stops it but
 * never speaks its wire protocol. Kept as a tiny local seam because the frozen contract does not
 * define an interface for it.
 */
interface LoopbackProxy {
    /** The loopback TCP port the engine must dial (127.0.0.1:listenPort). Valid only after [start]. */
    val listenPort: Int
    fun start()
    fun stop()
}

/**
 * The single wiring point that the app integration layer fills in (Wave 2). Everything here is a
 * frozen-contract interface except [LoopbackProxy]. The vpn lane owns the lifecycle; these
 * factories hand it the concrete proxy/engine implementations without the vpn lane importing them.
 *
 * All factories receive [VpnRuntime.protector] — the ONE protect() seam — so every off-device socket
 * (live dial, pre-flight validation, DNS-over-TCP) is excluded from the tun through the same path.
 */
object VpnDependencies {
    @Volatile
    var validatorFactory: (SocketProtector) -> Validator = {
        error("VpnDependencies.validatorFactory not wired (proxy lane, Wave 2)")
    }

    @Volatile
    var dialerFactory: (ProxyConfig, SocketProtector) -> UpstreamDialer = { _, _ ->
        error("VpnDependencies.dialerFactory not wired (proxy lane, Wave 2)")
    }

    @Volatile
    var loopbackProxyFactory: (ProxyConfig, SocketProtector) -> LoopbackProxy = { _, _ ->
        error("VpnDependencies.loopbackProxyFactory not wired (proxy lane, Wave 2)")
    }

    // The native engine is owned directly by DjVpnService as an out-of-process RemoteEngine (it must
    // bind the :engine Service, which requires a Context), so there is no engine factory here.
}

/**
 * Process-global shared state between [DjVpnService] (the tun/route/protect owner) and any
 * [VpnController] in the same process. Single source of the published [VpnState].
 */
object VpnRuntime {
    private val _state = MutableStateFlow(VpnState.IDLE)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    val counters = TunnelCounters()

    /** Set by [DjVpnService] while it is alive; the real VpnService.protect() lives only there. */
    @Volatile
    var serviceProtector: SocketProtector? = null

    /**
     * The dynamic protect() seam every dialer uses. Before the tun is up (pre-flight validation)
     * there is nothing to protect from, so it is a safe no-op that reports success; once the service
     * is up it delegates to the real, single VpnService.protect() call site in [DjVpnService].
     */
    val protector = SocketProtector { socket -> serviceProtector?.protect(socket) ?: true }

    /** Last config that genuinely validated; used to re-establish on always-on / process restart. */
    @Volatile
    var currentConfig: ProxyConfig? = null

    /**
     * Human source label the connect site sets IMMEDIATELY before [VpnController.apply] — "WARP",
     * "VPN Gate (Japan)", "Tor", "Manual SOCKS5", "Saved: <name>", "Free: <host>" — so the status line
     * names WHAT is connected instead of an opaque `scheme://…@127.0.0.1:port`. Null → fall back to
     * [ProxyConfig.redacted]. (Same seam WgDirectVpnService uses for "WARP · WireGuard (direct)".)
     */
    @Volatile
    var sourceLabel: String? = null

    /** The status line for [config]: the explicit [sourceLabel] if set, else the redacted config. */
    fun labelFor(config: ProxyConfig): String = sourceLabel ?: config.redacted()

    /** Last advisory health snapshot; read by the diagnostics lane for its report (§8). */
    @Volatile
    var lastHealthReport: HealthReport? = null

    /** Exit IP observed by the last successful pre-flight; surfaced to the location seam (§10.9). */
    @Volatile
    var lastValidatedExitIp: String? = null

    fun update(transform: (VpnState) -> VpnState) {
        _state.value = transform(_state.value)
    }
}

/**
 * The one control surface the UI talks to. Runs validate-before-up: it validates the config on the
 * exact live dial path FIRST and only then starts [DjVpnService], which brings the device-wide tun
 * up and runs the leak self-test. Any failure returns a typed [ValidationResult.Failure] and leaves
 * the device un-routed (fail-closed).
 */
class VpnControllerImpl(private val appContext: Context) : VpnController {

    override val state: StateFlow<VpnState> = VpnRuntime.state

    override suspend fun apply(config: ProxyConfig): ValidationResult {
        // Field-level guard first (cheap, no network).
        config.validate()?.let { fieldError ->
            val err = ProxyError.Io(fieldError)
            VpnRuntime.update { VpnState(stage = VpnStage.IDLE, proxyRedacted = VpnRuntime.labelFor(config), error = err) }
            return ValidationResult.Failure(err)
        }

        VpnRuntime.update {
            VpnState(stage = VpnStage.VALIDATING, proxyRedacted = VpnRuntime.labelFor(config))
        }

        // Real connect + real handshake + real probe on the SAME dial path the live tunnel uses.
        val validator = VpnDependencies.validatorFactory(VpnRuntime.protector)
        val result = validator.validate(config)
        if (result is ValidationResult.Failure) {
            VpnRuntime.update { it.copy(stage = VpnStage.IDLE, error = result.error) }
            return result
        }
        result as ValidationResult.Success

        // Validation passed: publish the config to the in-process holder (NOT an Intent extra, so
        // the password never crosses system_server) and hand off to the service. Remember the exit
        // IP so the service can hand it to the location seam once CONNECTED.
        VpnRuntime.currentConfig = config
        VpnRuntime.lastValidatedExitIp = result.exitIp
        VpnRuntime.update { it.copy(stage = VpnStage.CONNECTING, error = null) }
        startService(DjVpnService.ACTION_CONNECT)

        // Wait for a terminal outcome: CONNECTED (up + leak-clean) or ERROR (fail-closed).
        val terminal = withTimeoutOrNull(BRING_UP_TIMEOUT_MS) {
            VpnRuntime.state.first { it.stage == VpnStage.CONNECTED || it.stage == VpnStage.ERROR }
        }
        return when (terminal?.stage) {
            VpnStage.CONNECTED -> result
            else -> ValidationResult.Failure(terminal?.error ?: ProxyError.Timeout("tunnel bring-up"))
        }
    }

    override fun stop() {
        val i = Intent(appContext, DjVpnService::class.java).setAction(DjVpnService.ACTION_STOP)
        appContext.startService(i)
    }

    private fun startService(action: String) {
        val i = Intent(appContext, DjVpnService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(i)
        } else {
            appContext.startService(i)
        }
    }

    companion object {
        private const val BRING_UP_TIMEOUT_MS = 45_000L
    }
}

/**
 * Always-on VPN + kill-switch guidance the UI links to. Android's own always-on toggle (with
 * "Block connections without VPN") is the real kill switch: it forces the system to restart
 * [DjVpnService] and hold routes so no packet ever leaves outside the tunnel — including across
 * reboots and process death. We cannot enable it programmatically (OS policy); we deep-link to it
 * and explain the steps.
 */
object KillSwitch {
    val steps: List<String> = listOf(
        "Open Settings › Network & internet › VPN (this button jumps there).",
        "Tap the gear next to DJProxy.",
        "Enable \"Always-on VPN\".",
        "Enable \"Block connections without VPN\" — this is the kill switch.",
    )

    /** The public system action string for the VPN settings screen (kill-switch toggles live there). */
    private const val ACTION_VPN_SETTINGS = "android.settings.VPN_SETTINGS"

    /** Opens the system VPN settings screen where the always-on / kill-switch toggles live. */
    fun openVpnSettings(context: Context) {
        val i = Intent(ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** Convenience used by the service to reconstruct a [ProxyConfig] from persisted intent extras. */
internal fun proxyTypeOf(name: String?): ProxyType =
    ProxyType.entries.firstOrNull { it.name == name } ?: ProxyType.SOCKS5

/** Marker so a stray VpnService reference in this file is never mistaken for the protect() seam. */
private val vpnServiceClass = VpnService::class
