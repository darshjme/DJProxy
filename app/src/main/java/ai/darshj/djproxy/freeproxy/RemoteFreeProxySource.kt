package ai.darshj.djproxy.freeproxy

import ai.darshj.djproxy.core.ProxyType
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * [FreeProxySource] over the real network. Fetches a fixed set of maintained, liveness-tested public
 * lists (jetkai + proxifly) as plain-text `ip:port` bodies over **HTTPS**, parses + SSRF-screens them
 * with [FreeProxyParser], merges/dedupes/caps, and caches via [FreeProxyCache].
 *
 * Hardening: HTTPS enforced per source (non-https sources are refused, not fetched), redirects OFF,
 * per-response byte cap ([maxBytes]), 10 s timeouts, `User-Agent: DJProxy`. Zero new dependency — a
 * plain [HttpURLConnection], exactly like `config.SubscriptionFetcher`. The network body read is behind
 * the [BodyFetcher] seam so the whole fetch/merge/cache pipeline is unit-testable with no socket.
 *
 * Never auto-connects; never throws to the caller — failures degrade to stale cache or
 * [FreeProxyResult.Failed].
 */
class RemoteFreeProxySource(
    private val cache: FreeProxyCache,
    private val sources: List<Source> = DEFAULT_SOURCES,
    private val fetcher: BodyFetcher = RealFetcher,
    private val maxBytes: Int = MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) : FreeProxySource {

    /** One upstream list: its https URL, the protocol of its entries, and a display label. */
    data class Source(val url: String, val defaultType: ProxyType, val label: String)

    /** Body-read seam: returns the raw response text, or throws on any network/IO failure. */
    fun interface BodyFetcher {
        suspend fun fetch(url: String, maxBytes: Int): String
    }

    override suspend fun fetch(force: Boolean): FreeProxyResult {
        if (!force) {
            cache.getFresh()?.let { fresh ->
                return FreeProxyResult.Ok(fresh.entries, fresh.fetchedAt, fromCache = true)
            }
        }

        val lists = ArrayList<List<FreeProxyEntry>>(sources.size)
        var anySuccess = false
        var lastError: String? = null

        for (src in sources) {
            val reject = rejectReason(src.url)
            if (reject != null) { lastError = reject; continue }
            val body = try {
                fetcher.fetch(src.url, maxBytes)
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                continue
            }
            anySuccess = true
            // Belt-and-suspenders cap in case a seam returns an oversized body.
            val capped = if (body.length > maxBytes) body.substring(0, maxBytes) else body
            lists.add(FreeProxyParser.parse(capped, src.defaultType, src.label))
        }

        if (!anySuccess) {
            // Total fetch failure: serve stale cache if we have one, else fail.
            cache.getAny()?.let { stale ->
                return FreeProxyResult.Ok(stale.entries, stale.fetchedAt, fromCache = true)
            }
            return FreeProxyResult.Failed(lastError ?: "No free-proxy source reachable")
        }

        val merged = FreeProxyParser.mergeDedupeCap(lists)
        if (merged.isEmpty()) {
            cache.getAny()?.let { stale ->
                return FreeProxyResult.Ok(stale.entries, stale.fetchedAt, fromCache = true)
            }
            return FreeProxyResult.Failed("Free-proxy lists were empty after screening")
        }

        val now = clock()
        cache.put(merged, now)
        return FreeProxyResult.Ok(merged, now, fromCache = false)
    }

    /**
     * Cache-only read: returns whatever snapshot is stored (fresh or stale) with no network I/O, or
     * `null` when the cache is cold. The auto-populate on entering the Free tab uses this so viewing
     * the tab never opens a socket; the explicit Refresh is the only network path.
     */
    override suspend fun fetchCachedOnly(): FreeProxyResult.Ok? {
        val snap = cache.getAny() ?: return null
        return FreeProxyResult.Ok(snap.entries, snap.fetchedAt, fromCache = true)
    }

    /** Non-null reason if [url] must not be fetched (malformed or not https). Pure → testable. */
    private fun rejectReason(url: String): String? {
        val parsed = runCatching { URL(url) }.getOrNull()
            ?: return "Malformed free-proxy source URL"
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            return "Free-proxy source must be https"
        }
        if (parsed.host.isNullOrBlank()) return "Free-proxy source has no host"
        return null
    }

    companion object {
        const val MAX_BYTES: Int = 512 * 1024        // 512 KB per response
        private const val TIMEOUT_MS = 10_000

        /**
         * Fixed, maintained public lists. TXT (not JSON) endpoints so parsing stays JSON-free and the
         * lane keeps ZERO new dependencies. Priority order = merge/dedupe priority.
         *
         * **Host diversity (the Refresh "looks broken" fix):** the list deliberately spans TWO distinct
         * hosts — `raw.githubusercontent.com` (jetkai + proxifly) AND `api.proxyscrape.com`. A refresh
         * only fails when *every* source is unreachable, so a single blocked/rate-limited host (a common
         * cause of a silent, inert grey list) can no longer sink the whole pull: the surviving host still
         * returns rows. The proxyscrape endpoints use `format=text&proxy_format=ipport`, so their bodies
         * are the same bare `ip:port` lines [FreeProxyParser] already screens — no JSON, no new dependency.
         */
        val DEFAULT_SOURCES: List<Source> = listOf(
            Source(
                "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt",
                ProxyType.SOCKS5, "jetkai · socks5",
            ),
            Source(
                "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-http.txt",
                ProxyType.HTTP, "jetkai · http",
            ),
            Source(
                "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks5/data.txt",
                ProxyType.SOCKS5, "proxifly · socks5",
            ),
            Source(
                "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.txt",
                ProxyType.HTTP, "proxifly · http",
            ),
            // Second distinct host so a raw.githubusercontent.com outage/rate-limit can't fail the refresh.
            Source(
                "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&proxy_format=ipport&format=text&protocol=socks5",
                ProxyType.SOCKS5, "proxyscrape · socks5",
            ),
            Source(
                "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&proxy_format=ipport&format=text&protocol=http",
                ProxyType.HTTP, "proxyscrape · http",
            ),
        )

        /**
         * Real HTTPS body reader: redirects OFF, HTTPS enforced, byte-capped stream read, 10 s timeouts.
         * Modelled on `config.SubscriptionFetcher.RealFetcher` — no new dependency.
         */
        val RealFetcher: BodyFetcher = BodyFetcher { url, maxBytes ->
            withContext(Dispatchers.IO) {
                val parsed = URL(url)
                if (!parsed.protocol.equals("https", ignoreCase = true)) {
                    throw IOException("Free-proxy source must be https")
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
         * Build a production source backed by a `SharedPreferences` cache blob (`djproxy_freeproxy`).
         * The ui lane calls this from `MainActivity` when wiring the three v6 seams.
         */
        fun create(context: Context): RemoteFreeProxySource {
            val prefs = context.applicationContext
                .getSharedPreferences("djproxy_freeproxy", Context.MODE_PRIVATE)
            val persistence = object : FreeProxyCache.Persistence {
                override fun read(): String? = prefs.getString(KEY_BLOB, null)
                override fun write(value: String) { prefs.edit().putString(KEY_BLOB, value).apply() }
                override fun clear() { prefs.edit().remove(KEY_BLOB).apply() }
            }
            return RemoteFreeProxySource(cache = FreeProxyCache(persistence))
        }

        private const val KEY_BLOB = "freeproxy.v1"
    }
}
