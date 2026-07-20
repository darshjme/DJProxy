package ai.darshj.djproxy.freeproxy

/**
 * Provider of the **free public proxy list**. The implementation fetches maintained, liveness-tested
 * SOCKS5/HTTP lists over HTTPS, parses + SSRF-screens them, dedupes, caps the count, and caches with a
 * TTL. It **never auto-connects** — it only produces a pick-list the ui lane renders; applying an entry
 * still funnels through the unchanged `VpnController.apply(ProxyConfig)` seam behind a consent gate.
 *
 * Offline-degrading: a fetch failure serves the (possibly stale) cache when one exists and otherwise
 * returns [FreeProxyResult.Failed] — it never throws to the caller.
 */
interface FreeProxySource {
    /**
     * Fetch + parse + screen the public lists.
     *
     * @param force when true, bypass the cache TTL and re-fetch from the network.
     * @return [FreeProxyResult.Ok] with the (possibly cached) entries, or [FreeProxyResult.Failed]
     *         when no source could be reached and no cache exists.
     */
    suspend fun fetch(force: Boolean = false): FreeProxyResult

    /**
     * Return the last stored list from the LOCAL CACHE ONLY, without any network access, or `null`
     * when nothing is cached. Used to populate the pick-list when the Free tab is first opened so
     * merely viewing the tab never emits an outbound request — the network pull stays reserved for an
     * explicit [fetch] with `force = true`. Default is `null` (no cache) so off-device fakes stay simple.
     */
    suspend fun fetchCachedOnly(): FreeProxyResult.Ok? = null
}

sealed interface FreeProxyResult {
    /**
     * @param entries screened, deduped, capped public entries (may be empty only when the cache was
     *   empty and every source succeeded but yielded nothing usable — normally non-empty).
     * @param fetchedAt epoch-millis the [entries] were produced/cached.
     * @param fromCache true when served from cache (fresh hit, or stale fallback after a fetch failure).
     */
    data class Ok(
        val entries: List<FreeProxyEntry>,
        val fetchedAt: Long,
        val fromCache: Boolean,
    ) : FreeProxyResult

    /** No source reachable and no cache to fall back on. [reason] is a short human string. */
    data class Failed(val reason: String) : FreeProxyResult
}
