package ai.darshj.djproxy.ovpnengine

import android.content.Context
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyType
import ai.darshj.djproxy.vpn.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ovpnsocks.Ovpnsocks

/**
 * The ovpnengine lane's controller (mirrors [ai.darshj.djproxy.tor.OnionProxyManager]'s role): drives
 * the gomobile-bound userspace OpenVPN client (`ovpnsocks`, = ooni/minivpn + wireguard netstack +
 * go-socks5). It brings ONE OpenVPN tunnel up from a `.ovpn` profile and exposes it as a loopback
 * SOCKS5 the EXISTING hev tunnel routes through — so a VPN Gate / OpenVPN server is used AS a proxy,
 * exactly like the embedded Tor lane's `127.0.0.1:9050`. No core file is touched.
 *
 * The engine's own socket to the VPN Gate server is created inside DJProxy's app process, which the tun
 * already excludes via `addDisallowedApplication(self)` — so it egresses directly (no routing loop),
 * while device traffic flows tun → hev → 127.0.0.1:<port> → minivpn → the VPN Gate exit.
 *
 * Contract: never throws (the native seam is wrapped); [start] suspends until the OpenVPN handshake
 * completes (or fails). Ciphers supported by minivpn: AES-128/256-CBC, AES-128/256-GCM + SHA1/256/512;
 * a server outside that set returns null (honest partial — surfaced to the ui as a connect failure).
 */
class OvpnEngineController(private val appContext: Context) {

    @Volatile
    private var running = false

    @Volatile
    private var port = 0

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** The loopback SOCKS5 port the engine settled on while running, else 0. */
    fun socksPort(): Int = port

    /**
     * Bring up the OpenVPN tunnel from [ovpn] profile text and return a `socks5://127.0.0.1:<port>`
     * [ProxyConfig] to feed the existing apply path, or null if the tunnel could not be established
     * (unsupported cipher / bad profile / no network). Suspends until connected. Never throws.
     */
    suspend fun start(ovpn: String): ProxyConfig? = withContext(Dispatchers.IO) {
        runCatching {
            if (running) stopInternal()
            // Ovpnsocks.start blocks until the OpenVPN handshake completes; throws on any failure.
            val p = Ovpnsocks.start(ovpn, appContext.cacheDir.absolutePath)
            port = p.toInt()
            running = true
            _active.value = true
            LogBus.i(TAG, "OpenVPN engine up — routing via local SOCKS5 127.0.0.1:$port")
            ProxyConfig(type = ProxyType.SOCKS5, host = LOOPBACK, port = port)
        }.getOrElse { t ->
            LogBus.w(TAG, "OpenVPN engine failed to connect: ${t.message}")
            running = false
            port = 0
            _active.value = false
            null
        }
    }

    /** Tear the OpenVPN tunnel down. Idempotent; never throws. */
    fun stop() {
        runCatching { stopInternal() }
    }

    private fun stopInternal() {
        if (running || port != 0) {
            runCatching { Ovpnsocks.stop() }
            LogBus.i(TAG, "OpenVPN engine stopped")
        }
        running = false
        port = 0
        _active.value = false
    }

    companion object {
        private const val TAG = "OvpnEngine"
        private const val LOOPBACK = "127.0.0.1"
    }
}
