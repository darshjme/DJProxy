package ai.darshj.djproxy.hotspot

import android.content.Context
import ai.darshj.djproxy.proxy.UpstreamDialer
import ai.darshj.djproxy.vpn.LogBus
import ai.darshj.djproxy.vpn.VpnDependencies
import ai.darshj.djproxy.vpn.VpnRuntime
import ai.darshj.djproxy.vpn.VpnStage
import ai.darshj.djproxy.vpn.seams.HotspotCapability
import ai.darshj.djproxy.vpn.seams.HotspotController
import ai.darshj.djproxy.vpn.seams.ShareResult
import ai.darshj.djproxy.vpn.seams.ShareState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Concrete [HotspotController] (§9.4). Owns the LAN share endpoint and the root transparent redirect,
 * publishes honest capability/share state, and self-observes [VpnRuntime.state] (core never drives
 * it): if the tun drops while a root transparent redirect is active, it tears the redirect down so it
 * cannot silently point tethered clients at a dead table.
 *
 * All suspend seam calls return a [ShareResult] and never throw.
 */
class HotspotControllerImpl(
    private val appContext: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Injectable for tests; production resolves the proxy-tunnelled dialer from the VPN wiring. */
    private val dialerProvider: () -> UpstreamDialer? = { defaultDialer() },
) : HotspotController {

    private val _capability = MutableStateFlow(ai.darshj.djproxy.hotspot.HotspotCapability.detect())
    override val capability: StateFlow<HotspotCapability> = _capability.asStateFlow()

    private val _share = MutableStateFlow<ShareState>(ShareState.Off)
    override val share: StateFlow<ShareState> = _share.asStateFlow()

    private var lanServer: LanShareServer? = null
    private val rootRedirector = RootRedirector()
    @Volatile private var activeCredential: LanCredential? = null

    init {
        // Self-observe the tunnel: a root transparent redirect is only meaningful while the tun is up.
        scope.launch {
            VpnRuntime.state.collect { st ->
                if (_share.value is ShareState.RootTransparent &&
                    st.stage != VpnStage.CONNECTED && st.stage != VpnStage.RECONNECTING
                ) {
                    LogBus.i(TAG, "Tun no longer up (${st.stage}); tearing down root transparent redirect")
                    stopShare()
                }
            }
        }
    }

    /**
     * The [requireAuth] flag is accepted for the seam signature but auth is ALWAYS enforced: the LAN
     * share is a live relay over the user's paid, identity-bearing exit, so an unauthenticated mode
     * (especially bound to a Wi-Fi-client interface on an untrusted network) would be an open relay.
     * We also bind ONLY to the detected LAN address — never 0.0.0.0 — so the endpoint is scoped to the
     * hotspot/LAN subnet instead of every interface (cellular / IPv6 / other joined networks).
     */
    override suspend fun startLanShare(requireAuth: Boolean): ShareResult {
        return runCatching {
            stopShareInternal()
            val addr = ai.darshj.djproxy.hotspot.HotspotCapability.localLanAddress()
                ?: return@runCatching ShareResult.Fail(
                    "No LAN/hotspot interface — enable your Wi-Fi hotspot or join a network first",
                )
            if (VpnRuntime.currentConfig == null) {
                return@runCatching ShareResult.Fail("No proxy configured — apply a proxy in DJProxy first")
            }
            val bindAddr = runCatching { InetAddress.getByName(addr) }.getOrNull()
                ?: return@runCatching ShareResult.Fail("Could not resolve LAN address $addr to bind")
            val cred = LanCredential.random() // always: never an unauthenticated open relay
            activeCredential = cred
            val server = LanShareServer(
                bindAddress = bindAddr,
                requireAuth = true,
                credential = cred,
                dialerProvider = dialerProvider,
                onError = { LogBus.w(TAG, it) },
            )
            val port = server.start()
            if (port <= 0) {
                activeCredential = null
                return@runCatching ShareResult.Fail("Could not bind the LAN share port")
            }
            // Defence in depth: refuse to advertise a share that bound to every interface.
            if (server.boundAddress?.isAnyLocalAddress != false) {
                server.stop()
                activeCredential = null
                return@runCatching ShareResult.Fail("Refusing to share: endpoint bound to all interfaces")
            }
            lanServer = server
            val state = ShareState.LanProxy(addr = addr, port = port, cred = cred.asUserInfo())
            _share.value = state
            LogBus.i(TAG, "LAN share up on $addr:$port (auth=required)")
            ShareResult.Ok(state)
        }.getOrElse { ShareResult.Fail("LAN share failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    override suspend fun startRootTransparent(): ShareResult {
        return runCatching {
            if (_capability.value != HotspotCapability.ROOT_TRANSPARENT_AVAILABLE) {
                return@runCatching ShareResult.Fail("Transparent redirect needs root (not available on this device)")
            }
            stopShareInternal()
            when (val r = rootRedirector.apply()) {
                is RootRedirector.Result.Fail -> ShareResult.Fail(r.reason)
                is RootRedirector.Result.Ok -> {
                    _share.value = ShareState.RootTransparent
                    LogBus.i(TAG, "Root transparent redirect active via ${r.tun}")
                    ShareResult.Ok(ShareState.RootTransparent)
                }
            }
        }.getOrElse { ShareResult.Fail("Transparent redirect failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    override fun stopShare() {
        runCatching { stopShareInternal() }
        _share.value = ShareState.Off
    }

    private fun stopShareInternal() {
        lanServer?.stop()
        lanServer = null
        activeCredential = null
        if (rootRedirector.isActive) rootRedirector.revert()
    }

    override fun qrPayload(): String? {
        val s = _share.value
        return if (s is ShareState.LanProxy) QrPayload.forState(s) else null
    }

    override fun refreshCapability(ctx: Context) {
        _capability.value = ai.darshj.djproxy.hotspot.HotspotCapability.detect()
    }

    /** The LAN address currently detected (for settings UI), or null when off-network. */
    fun currentLanAddress(): String? = ai.darshj.djproxy.hotspot.HotspotCapability.localLanAddress()

    /** The SOCKS5 variant of the current share payload, for clients that prefer SOCKS5. */
    fun socksPayload(): String? {
        val s = _share.value
        return if (s is ShareState.LanProxy) {
            QrPayload.forSocks5(s.addr, s.port, LanCredential.parse(s.cred))
        } else {
            null
        }
    }

    companion object {
        private const val TAG = "Hotspot"

        /** Resolves the proxy-tunnelled dialer from the live VPN wiring; null when no proxy applied. */
        private fun defaultDialer(): UpstreamDialer? {
            val cfg = VpnRuntime.currentConfig ?: return null
            return runCatching { VpnDependencies.dialerFactory(cfg, VpnRuntime.protector) }.getOrNull()
        }
    }
}
