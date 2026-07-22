package ai.darshj.djproxy.wireguard

import android.content.Context
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyType
import ai.darshj.djproxy.vpn.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ovpnsocks.Ovpnsocks

/**
 * WireGuard engine controller — the reliable free-VPN lane (mirror of
 * [ai.darshj.djproxy.ovpnengine.OvpnEngineController]). Brings up ONE userspace WireGuard tunnel via
 * the native engine ([Ovpnsocks.startWireguard]) and exposes it as a loopback SOCKS5 the existing hev
 * tunnel routes through — so ANY WireGuard endpoint (Cloudflare WARP, a user's own Oracle/VPS server,
 * any public WG peer) becomes a device-wide route, exactly like the Tor lane's 127.0.0.1:9050.
 *
 * WARP is the built-in default: a free, anonymous Cloudflare account is registered once
 * ([Ovpnsocks.registerWarp]) and cached in prefs, then reused on every connect.
 *
 * Contract: never throws; [startWarp]/[startConfig] suspend over the handshake (bounded by
 * [CONNECT_TIMEOUT_MS]) and return a `socks5://127.0.0.1:<port>` config or null with [lastFailure] set.
 */
class WgEngineController(private val appContext: Context) {

    @Volatile private var running = false
    @Volatile private var port = 0

    @Volatile
    var lastFailure: String? = null
        private set

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun socksPort(): Int = port

    /** Connect via Cloudflare WARP (free, auto-registered once, cached). */
    suspend fun startWarp(): ProxyConfig? = withContext(Dispatchers.IO) {
        val profile = loadOrRegisterWarp()
        if (profile == null) {
            lastFailure = lastFailure ?: "WARP registration failed — check connectivity and try again"
            _active.value = false
            return@withContext null
        }
        startFromProfile(profile)
    }

    /** Connect via a pasted/imported WireGuard `.conf` (a user's own server / any public WG peer). */
    suspend fun startConfig(confText: String): ProxyConfig? = withContext(Dispatchers.IO) {
        val wg = WgProfile.fromConf(confText)
        if (wg == null) {
            lastFailure = "That doesn't look like a valid WireGuard config (need PrivateKey, Address, Peer PublicKey, Endpoint)"
            return@withContext null
        }
        startFromProfile(wg)
    }

    private suspend fun startFromProfile(p: WgProfile): ProxyConfig? = withContext(Dispatchers.IO) {
        if (running) stopInternal()
        lastFailure = null
        val result: Int? = try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                runInterruptible {
                    Ovpnsocks.startWireguard(
                        p.privateKey, p.address, p.dns, p.peerPublicKey, p.presharedKey,
                        p.endpoint, p.allowedIps, 25L,
                    ).toInt()
                }
            }
        } catch (t: Throwable) {
            lastFailure = t.message?.trim().takeUnless { it.isNullOrBlank() } ?: "WireGuard handshake failed"
            LogBus.w(TAG, "WireGuard failed: $lastFailure")
            runCatching { Ovpnsocks.stopWireguard() }
            null
        }
        if (result == null) {
            if (lastFailure == null) {
                lastFailure = "timed out after ${CONNECT_TIMEOUT_MS / 1000}s — the WireGuard endpoint is unreachable"
                LogBus.w(TAG, "WireGuard $lastFailure")
                runCatching { Ovpnsocks.stopWireguard() }
            }
            running = false; port = 0; _active.value = false
            return@withContext null
        }
        port = result; running = true; _active.value = true; lastFailure = null
        LogBus.i(TAG, "WireGuard up — routing via local SOCKS5 127.0.0.1:$port")
        ProxyConfig(type = ProxyType.SOCKS5, host = LOOPBACK, port = port)
    }

    fun stop() { runCatching { stopInternal() } }

    private fun stopInternal() {
        if (running || port != 0) {
            runCatching { Ovpnsocks.stopWireguard() }
            LogBus.i(TAG, "WireGuard stopped")
        }
        running = false; port = 0; _active.value = false
    }

    /** Loads the cached WARP profile, or registers a fresh one and persists it. Null on failure. */
    private fun loadOrRegisterWarp(): WgProfile? {
        val prefs = appContext.getSharedPreferences(WARP_PREFS, Context.MODE_PRIVATE)
        prefs.getString(WARP_KEY, null)?.let { cached ->
            WgProfile.fromWarpJson(cached)?.let { return it }
        }
        val json = runCatching { Ovpnsocks.registerWarp() }.getOrElse { t ->
            lastFailure = "WARP registration failed: ${t.message}"
            LogBus.w(TAG, lastFailure!!)
            return null
        }
        val profile = WgProfile.fromWarpJson(json)
        if (profile == null) {
            lastFailure = "WARP returned an unexpected profile"
            return null
        }
        prefs.edit().putString(WARP_KEY, json).apply()
        LogBus.i(TAG, "WARP account registered (${profile.address} via ${profile.endpoint})")
        return profile
    }

    /** Drop the cached WARP account so the next connect registers a fresh one (rotation). */
    fun resetWarp() {
        appContext.getSharedPreferences(WARP_PREFS, Context.MODE_PRIVATE).edit().remove(WARP_KEY).apply()
    }

    companion object {
        private const val TAG = "WgEngine"
        private const val LOOPBACK = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val WARP_PREFS = "djproxy_warp"
        private const val WARP_KEY = "warp_profile_json"
    }
}
