package ai.darshj.djproxy.vpngate

import android.content.Context
import android.content.Intent
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.vpn.LogBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The vpngate lane's public seam (mirrors the shape/role of `tor.TorController`). The ui reads it via
 * [VpnGateGateway.controller] — `FeatureRegistry` is core and may NOT be edited to add a `vpnGateController`
 * slot, so the lane owns its own nullable holder instead (same divergence the tor lane took).
 *
 * HONEST CONTRACT (SSOT `vpngate_scope`): this controls a server **catalog/browser**, NOT a tunnel.
 *   - [refresh] / [loadCache] populate [servers] from [VpnGateSource].
 *   - [shareOvpn] hands the decoded `.ovpn` to an external OpenVPN app via `ACTION_SEND` EXTRA_TEXT — no
 *     FileProvider, no new manifest/res surface (SSOT acceptance).
 *   - [applyDialable] routes the RARE directly-dialable row through the caller's `VpnController.apply`
 *     seam (passed in, so this lane never hard-depends on the vpn controller and stays unit-testable).
 *
 * Contract: no method throws. Every accessor is cheap; [refresh]/[loadCache] are suspending. Serialised
 * so two Refresh taps cannot overlap.
 */
class VpnGateController(
    private val source: VpnGateSource,
) {

    /** The current catalog rows (score desc / ping asc). Empty until a refresh or a cache load. */
    private val _servers = MutableStateFlow<List<VpnGateServer>>(emptyList())
    val servers: StateFlow<List<VpnGateServer>> = _servers.asStateFlow()

    /** Explicit refresh state so the ui shows spinner → count/timestamp → inline error (never silent). */
    private val _refreshState = MutableStateFlow<VpnGateRefreshState>(VpnGateRefreshState.Idle)
    val refreshState: StateFlow<VpnGateRefreshState> = _refreshState.asStateFlow()

    /** Epoch-millis the current [servers] were produced/cached, or `null` before any load. */
    private val _lastFetchedAt = MutableStateFlow<Long?>(null)
    val lastFetchedAt: StateFlow<Long?> = _lastFetchedAt.asStateFlow()

    /** Serialises refresh so overlapping taps don't double-fetch or race the state flows. */
    private val refreshLock = Mutex()

    /**
     * Network refresh of the catalog. Publishes [VpnGateRefreshState.Loading] up front, then either
     * [VpnGateRefreshState.Success] (with the count + fetchedAt) or [VpnGateRefreshState.Error]. On a
     * fetch failure the source itself degrades to stale cache, so a Success can still carry
     * `fromCache = true` rows. Never throws (seam contract).
     */
    suspend fun refresh(force: Boolean = true) {
        try {
            refreshLock.withLock {
                _refreshState.value = VpnGateRefreshState.Loading
                when (val res = source.fetch(force)) {
                    is VpnGateResult.Ok -> {
                        _servers.value = res.servers
                        _lastFetchedAt.value = res.fetchedAt
                        _refreshState.value = VpnGateRefreshState.Success(res.servers.size, res.fetchedAt)
                        LogBus.i(TAG, "VPN Gate catalog: ${res.servers.size} servers (fromCache=${res.fromCache})")
                    }
                    is VpnGateResult.Failed -> {
                        _refreshState.value = VpnGateRefreshState.Error(res.reason)
                        LogBus.w(TAG, "VPN Gate refresh failed: ${res.reason}")
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            _refreshState.value = VpnGateRefreshState.Error(t.message ?: "VPN Gate refresh failed")
        }
    }

    /**
     * Populate [servers] from the LOCAL CACHE ONLY (no network). Used when the tab is first opened so
     * viewing it never opens a socket. A cold cache is a no-op (state stays [VpnGateRefreshState.Idle]).
     */
    suspend fun loadCache() {
        try {
            val cached = source.fetchCachedOnly() ?: return
            _servers.value = cached.servers
            _lastFetchedAt.value = cached.fetchedAt
            if (cached.servers.isNotEmpty()) {
                _refreshState.value = VpnGateRefreshState.Success(cached.servers.size, cached.fetchedAt)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Cache-only read must never surface an error to the ui.
        }
    }

    /**
     * Hand the decoded `.ovpn` of [server] to another app via a share chooser (`ACTION_SEND` EXTRA_TEXT).
     * No FileProvider, no temp file — the profile rides as text. Safe: swallows any launch failure.
     */
    fun shareOvpn(context: Context, server: VpnGateServer) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "${server.hostName}.ovpn")
                putExtra(Intent.EXTRA_TITLE, "${server.hostName}.ovpn")
                putExtra(Intent.EXTRA_TEXT, server.ovpn)
            }
            val chooser = Intent.createChooser(send, "Open .ovpn in an OpenVPN app")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }.onFailure { LogBus.w(TAG, "Share .ovpn failed: ${it.message}") }
    }

    /**
     * Apply the RARE directly-dialable row through the caller-supplied [apply] seam (the ViewModel passes
     * `vpnController::apply`). Returns false — without calling [apply] — for any OpenVPN-only row, so the
     * ui can never accidentally "connect" a non-proxy VPN Gate server. Never throws.
     *
     * @return true when [server] was dialable and [apply] was invoked; false otherwise.
     */
    suspend fun applyDialable(
        server: VpnGateServer,
        apply: suspend (ProxyConfig) -> Unit,
    ): Boolean {
        val config = server.dialConfig ?: return false
        if (!server.directlyDialable) return false
        return try {
            apply(config)
            true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            LogBus.w(TAG, "VPN Gate apply failed for ${server.hostName}: ${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "VpnGate"
    }
}

/**
 * Explicit refresh state for the VPN Gate tab so the ui renders spinner → count/timestamp → inline error
 * instead of a silent grey list (parity with the servers-tab free-refresh states, SSOT).
 */
sealed interface VpnGateRefreshState {
    /** Never fetched this process, and no cache surfaced yet. */
    object Idle : VpnGateRefreshState

    /** A refresh is in flight — the ui shows a spinner. */
    object Loading : VpnGateRefreshState

    /** Catalog loaded: [count] rows produced/cached at [fetchedAt] (epoch-millis). */
    data class Success(val count: Int, val fetchedAt: Long) : VpnGateRefreshState

    /** No source reachable and no cache — [reason] is short human copy for the inline error card. */
    data class Error(val reason: String) : VpnGateRefreshState
}
