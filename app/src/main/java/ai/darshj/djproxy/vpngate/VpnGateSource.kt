package ai.darshj.djproxy.vpngate

/**
 * Provider of the **VPN Gate public catalog**. The implementation fetches the volunteer CSV over HTTPS,
 * parses + SSRF-screens it with [VpnGateCsvParser], caps/sorts, decodes each row's `.ovpn`, and caches
 * with a TTL. It **never auto-connects** and never claims to tunnel — it produces a browsable catalog
 * the ui renders for Export/Share, with the rare directly-dialable row funnelling through the unchanged
 * `VpnController.apply(ProxyConfig)` seam behind the existing untrusted-server consent gate.
 *
 * Offline-degrading: a fetch failure serves the (possibly stale) cache when one exists and otherwise
 * returns [VpnGateResult.Failed] — it never throws to the caller (mirror of `freeproxy.FreeProxySource`).
 */
interface VpnGateSource {
    /**
     * Fetch + parse + screen the VPN Gate CSV.
     *
     * @param force when true, bypass the cache TTL and re-fetch from the network.
     * @return [VpnGateResult.Ok] with the (possibly cached) servers, or [VpnGateResult.Failed] when no
     *         source could be reached and no cache exists.
     */
    suspend fun fetch(force: Boolean = false): VpnGateResult

    /**
     * Return the last stored catalog from the LOCAL CACHE ONLY, with no network access, or `null` when
     * nothing is cached. Used to populate the VPN Gate tab when it is first opened so merely viewing it
     * never emits an outbound request — the network pull stays reserved for an explicit [fetch] with
     * `force = true`. Default is `null` so off-device fakes stay simple.
     */
    suspend fun fetchCachedOnly(): VpnGateResult.Ok? = null
}

sealed interface VpnGateResult {
    /**
     * @param servers screened, capped, score-sorted catalog rows (each with a decoded `.ovpn`).
     * @param fetchedAt epoch-millis the [servers] were produced/cached.
     * @param fromCache true when served from cache (fresh hit, or stale fallback after a fetch failure).
     */
    data class Ok(
        val servers: List<VpnGateServer>,
        val fetchedAt: Long,
        val fromCache: Boolean,
    ) : VpnGateResult

    /** No source reachable and no cache to fall back on. [reason] is a short human string. */
    data class Failed(val reason: String) : VpnGateResult
}
