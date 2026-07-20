package ai.darshj.djproxy.vpngate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * [VpnGateSource] over the real network. Fetches the VPN Gate volunteer catalog CSV over **HTTPS**,
 * parses + SSRF-screens it with [VpnGateCsvParser], and caches via [VpnGateCache].
 *
 * Hardening (mirror of `freeproxy.RemoteFreeProxySource` / `config.SubscriptionFetcher`): HTTPS enforced
 * (a non-https [url] is refused, never fetched), redirects OFF, per-response byte cap ([maxBytes]),
 * 15 s timeouts, `User-Agent: DJProxy`. Zero new dependency — a plain [HttpURLConnection]. The body read
 * is behind the [BodyFetcher] seam so the whole fetch/parse/cache pipeline is unit-testable with no
 * socket.
 *
 * CRITICAL vs the free-proxy source: the CSV embeds a full base64 `.ovpn` per row, so a 512 KB cap would
 * truncate the feed. [MAX_BYTES] is raised to 8 MiB (SSOT acceptance) so the whole catalog is read.
 *
 * Never auto-connects; never throws to the caller — failures degrade to stale cache or
 * [VpnGateResult.Failed].
 */
class RemoteVpnGateSource(
    private val cache: VpnGateCache,
    private val url: String = DEFAULT_URL,
    private val fetcher: BodyFetcher = RealFetcher,
    private val maxBytes: Int = MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) : VpnGateSource {

    /** Body-read seam: returns the raw CSV text, or throws on any network/IO failure. */
    fun interface BodyFetcher {
        suspend fun fetch(url: String, maxBytes: Int): String
    }

    override suspend fun fetch(force: Boolean): VpnGateResult {
        if (!force) {
            cache.getFresh()?.let { fresh ->
                return VpnGateResult.Ok(fresh.servers, fresh.fetchedAt, fromCache = true)
            }
        }

        rejectReason(url)?.let { reason ->
            // A misconfigured URL is not a transient failure — serve stale if we can, else fail.
            cache.getAny()?.let { stale ->
                return VpnGateResult.Ok(stale.servers, stale.fetchedAt, fromCache = true)
            }
            return VpnGateResult.Failed(reason)
        }

        val body = try {
            fetcher.fetch(url, maxBytes)
        } catch (t: Throwable) {
            cache.getAny()?.let { stale ->
                return VpnGateResult.Ok(stale.servers, stale.fetchedAt, fromCache = true)
            }
            return VpnGateResult.Failed(t.message ?: t.javaClass.simpleName)
        }

        // Belt-and-suspenders cap in case a seam returns an oversized body.
        val capped = if (body.length > maxBytes) body.substring(0, maxBytes) else body
        val servers = VpnGateCsvParser.parse(capped)
        if (servers.isEmpty()) {
            cache.getAny()?.let { stale ->
                return VpnGateResult.Ok(stale.servers, stale.fetchedAt, fromCache = true)
            }
            return VpnGateResult.Failed("VPN Gate catalog was empty after screening")
        }

        val now = clock()
        cache.put(servers, now)
        return VpnGateResult.Ok(servers, now, fromCache = false)
    }

    /**
     * Cache-only read: returns whatever snapshot is stored (fresh or stale) with no network I/O, or
     * `null` when the cache is cold. Opening the VPN Gate tab uses this so viewing it never opens a
     * socket; the explicit Refresh is the only network path.
     */
    override suspend fun fetchCachedOnly(): VpnGateResult.Ok? {
        val snap = cache.getAny() ?: return null
        return VpnGateResult.Ok(snap.servers, snap.fetchedAt, fromCache = true)
    }

    /** Non-null reason if [u] must not be fetched (malformed or not https). Pure → testable. */
    private fun rejectReason(u: String): String? {
        val parsed = runCatching { URL(u) }.getOrNull()
            ?: return "Malformed VPN Gate URL"
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            return "VPN Gate source must be https"
        }
        if (parsed.host.isNullOrBlank()) return "VPN Gate URL has no host"
        return null
    }

    companion object {
        /** VPN Gate's public iPhone CSV endpoint (the smallest, cleanest machine-readable feed). */
        const val DEFAULT_URL: String = "https://www.vpngate.net/api/iphone/"

        /** 8 MiB — the CSV embeds a base64 `.ovpn` per row; 512 KB would truncate it (SSOT). */
        const val MAX_BYTES: Int = 8 * 1024 * 1024
        private const val TIMEOUT_MS = 15_000

        /**
         * Real HTTPS body reader: redirects OFF, HTTPS enforced, byte-capped stream read, 15 s timeouts.
         * Modelled on `freeproxy.RemoteFreeProxySource.RealFetcher` — no new dependency.
         */
        val RealFetcher: BodyFetcher = BodyFetcher { url, maxBytes ->
            withContext(Dispatchers.IO) {
                val parsed = URL(url)
                if (!parsed.protocol.equals("https", ignoreCase = true)) {
                    throw IOException("VPN Gate source must be https")
                }
                val conn = (parsed.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "DJProxy")
                    setRequestProperty("Accept", "text/plain, */*")
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val sb = StringBuilder()
                        val buf = CharArray(8192)
                        var total = 0
                        while (true) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            val remaining = maxBytes - total
                            if (remaining <= 0) break
                            val take = if (n > remaining) remaining else n
                            sb.append(buf, 0, take)
                            total += take
                            if (total >= maxBytes) break
                        }
                        sb.toString()
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }

        /**
         * Build a production source backed by a `SharedPreferences` cache blob (`djproxy_vpngate`).
         * The ui lane / [VpnGateRegistrar] calls this to construct the single catalog source.
         */
        fun create(context: Context): RemoteVpnGateSource {
            val prefs = context.applicationContext
                .getSharedPreferences("djproxy_vpngate", Context.MODE_PRIVATE)
            val persistence = object : VpnGateCache.Persistence {
                override fun read(): String? = prefs.getString(KEY_BLOB, null)
                override fun write(value: String) { prefs.edit().putString(KEY_BLOB, value).apply() }
                override fun clear() { prefs.edit().remove(KEY_BLOB).apply() }
            }
            return RemoteVpnGateSource(cache = VpnGateCache(persistence))
        }

        private const val KEY_BLOB = "vpngate.v1"
    }
}
