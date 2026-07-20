package ai.darshj.djproxy.config

import ai.darshj.djproxy.core.ProxyParser
import ai.darshj.djproxy.hotspot.LanShareServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Fetches a **subscription URL** — an `https://` (or `http://`) link whose body is base64 of a
 * newline-separated list of config URIs — and turns it into an [ImportResult.Many] pick-list
 * (DESIGN_V4 §5).
 *
 * The network hop uses a plain [HttpURLConnection] off the main thread (no new dependency), is fully
 * injectable for tests, and never auto-connects — the ui pick-list chooses one config.
 */
object SubscriptionFetcher {

    private const val TIMEOUT_MS = 15_000
    private const val MAX_BYTES = 4L * 1024 * 1024 // 4 MiB guard against a hostile/huge body
    private const val MAX_REDIRECTS = 5
    private const val MAX_ENTRIES = 1000 // cap the pick-list so a huge body can't OOM/jank the UI

    /** Body fetcher seam so tests can drive [parseSubscriptionBody] without a real socket. */
    fun interface BodyFetcher {
        /** @return the raw response body. @throws any network/IO failure (mapped to Unreachable). */
        suspend fun fetch(url: String): String
    }

    /** Fetch + parse using the real network. */
    suspend fun fetch(url: String): ImportResult = fetch(url, RealFetcher)

    suspend fun fetch(url: String, fetcher: BodyFetcher): ImportResult {
        val body = try {
            fetcher.fetch(url)
        } catch (t: Throwable) {
            return ImportResult.Rejected(
                ImportError.SubscriptionUnreachable(t.message ?: t.javaClass.simpleName),
            )
        }
        return parseSubscriptionBody(body)
    }

    /**
     * Turn a raw subscription body into a pick-list. The body is base64 of a newline list in the common
     * case; some providers serve the plain list directly — both are handled. Each line is parsed via
     * [UriConfigParser] (URIs) or the frozen [ProxyParser] (bare host:port lines). Lines DJProxy can't
     * speak are dropped from the list rather than failing the whole import.
     */
    fun parseSubscriptionBody(body: String): ImportResult {
        val text = decodeIfBase64(body)
        val configs = ArrayList<NamedConfig>()
        for (rawLine in text.lineSequence()) {
            if (configs.size >= MAX_ENTRIES) break // bound the pick-list, not just the byte count
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val result = if (line.contains("://")) {
                UriConfigParser.parse(line)
            } else {
                when (val r = ProxyParser.parse(line)) {
                    is ProxyParser.Result.Ok -> ImportResult.Single(r.config)
                    is ProxyParser.Result.Err -> ImportResult.Rejected(ImportError.Unreadable(line))
                }
            }
            if (result is ImportResult.Single) {
                configs.add(NamedConfig(nameFor(line, result), result.config))
            }
        }
        return if (configs.isEmpty()) {
            ImportResult.Rejected(ImportError.EmptySubscription)
        } else {
            ImportResult.Many(configs)
        }
    }

    /** Prefer the `#fragment` label if the line carries one, else fall back to the redacted endpoint. */
    private fun nameFor(line: String, single: ImportResult.Single): String {
        val hash = line.indexOf('#')
        if (hash in 0 until line.length - 1) {
            val label = UriConfigParser.percentDecode(line.substring(hash + 1)).trim()
            if (label.isNotEmpty()) return label
        }
        return single.config.redacted()
    }

    /** If [body] base64-decodes to text that contains config URIs, use the decoded form; else the raw. */
    private fun decodeIfBase64(body: String): String {
        val trimmed = body.trim()
        val decoded = Base64Compat.decodeToString(trimmed)
        return if (decoded != null && (decoded.contains("://") || decoded.contains('\n'))) decoded else body
    }

    private object RealFetcher : BodyFetcher {
        override suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
            // Manual redirect loop (instanceFollowRedirects=false) so EVERY hop — the original URL and
            // each Location target — is re-screened: https-only, and rejected if the host resolves to a
            // loopback/private/link-local/CGNAT/unique-local address. This blocks the deep-link/share SSRF
            // + internal-beacon vector (a crafted subscription that pivots into the phone's own LAN or
            // confirms install by hitting an internal service).
            var current = url
            var redirects = 0
            while (true) {
                screen(current)
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "DJProxy/4")
                    setRequestProperty("Accept", "text/plain, */*")
                }
                try {
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location")
                            ?: throw IOException("HTTP $code with no Location")
                        if (++redirects > MAX_REDIRECTS) throw IOException("too many redirects")
                        // Resolve relative Location against the current URL, then re-screen next loop.
                        current = URL(URL(current), location).toString()
                        continue
                    }
                    if (code !in 200..299) throw IOException("HTTP $code")
                    return@withContext conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val sb = StringBuilder()
                        val buf = CharArray(8192)
                        var total = 0L
                        while (true) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IOException("subscription too large")
                            sb.append(buf, 0, n)
                        }
                        sb.toString()
                    }
                } finally {
                    conn.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    /**
     * Rejects a subscription URL that must not be fetched: anything but https, or a host that resolves
     * to a loopback / private / link-local / CGNAT / unique-local address (SSRF into the device's own
     * network). Hostnames are resolved here and every resolved address is screened via the same
     * [LanShareServer.isBlockedTarget] predicate the LAN-share endpoint uses.
     */
    private fun screen(url: String) {
        val parsed = try {
            URL(url)
        } catch (t: Throwable) {
            throw IOException("Malformed subscription URL")
        }
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            throw IOException("Subscription must be https (cleartext http is refused)")
        }
        val host = parsed.host ?: throw IOException("Subscription URL has no host")
        if (LanShareServer.isBlockedTarget(host)) {
            throw IOException("Subscription host is a private/loopback address")
        }
        val addrs = try {
            InetAddress.getAllByName(host)
        } catch (t: Throwable) {
            throw IOException("Could not resolve subscription host")
        }
        if (addrs.any { LanShareServer.isBlockedTarget(it.hostAddress ?: "") }) {
            throw IOException("Subscription host resolves to a private/loopback address")
        }
    }
}
