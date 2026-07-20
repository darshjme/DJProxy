package ai.darshj.djproxy.config

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyParser

/** One parsed, human-labelled proxy — the row shape for a subscription pick-list. */
data class NamedConfig(val name: String, val config: ProxyConfig)

/**
 * The total outcome of an import attempt. Every front door (paste, QR, deep link, share, .ovpn,
 * subscription) converges on exactly one of these (DESIGN_V4 §5).
 */
sealed interface ImportResult {
    /** A single ready-to-connect config → fill the fields / redacted line, offer connect. */
    data class Single(val config: ProxyConfig) : ImportResult

    /** A list (subscription) → the ui shows a pick-list; the user chooses one. Never empty. */
    data class Many(val configs: List<NamedConfig>) : ImportResult

    /** A typed, named refusal → the existing inline rose card. */
    data class Rejected(val error: ImportError) : ImportResult
}

/**
 * The single facade the ViewModel's `ingestExternal` calls. It **sniffs the shape** of [raw] — a
 * pasted proxy line, a `socks5://`/`ss://`/`vmess://` URI, a `djproxy://` deep link, an `.ovpn` file's
 * text, or a subscription URL — dispatches to the right parser, and returns an [ImportResult]. The
 * resulting [ProxyConfig] feeds the **existing** `VpnController.apply`; no core file is touched.
 *
 * [import] is `suspend` because a subscription front-door performs a network fetch; every other shape
 * resolves synchronously via [importLocal]. The ViewModel already runs `ingestExternal` in a coroutine.
 */
object ConfigImporter {

    /** Full facade including the networked subscription path. */
    suspend fun import(raw: String): ImportResult {
        val input = raw.trim()
        if (input.isEmpty()) return ImportResult.Rejected(ImportError.Empty)

        // A subscription URL is an http(s) link with a *path or query* (a bare http://host:port is an
        // HTTP proxy, handled locally). Only this shape needs the network.
        if (looksLikeSubscription(input)) {
            return SubscriptionFetcher.fetch(input)
        }
        return importLocal(input)
    }

    /**
     * The synchronous subset: everything except a live subscription fetch. Safe to call off a coroutine
     * (QR result, single paste). A subscription-shaped URL routed here is reported honestly rather than
     * silently fetched.
     */
    fun importLocal(raw: String): ImportResult {
        val input = raw.trim()
        if (input.isEmpty()) return ImportResult.Rejected(ImportError.Empty)

        // 1) OpenVPN profile text (multi-line).
        if (OvpnParser.looksLikeOvpn(input)) {
            return OvpnParser.parse(input)
        }

        // 2) A single URI (scheme://…).
        val scheme = UriConfigParser.schemeOf(input)
        if (scheme != null && !input.contains('\n')) {
            if (looksLikeSubscription(input)) {
                // Caller used the sync path for a networked shape — say so, don't fake it.
                return ImportResult.Rejected(
                    ImportError.SubscriptionUnreachable("use the Subscription tab to fetch this link"),
                )
            }
            return UriConfigParser.parse(input)
        }

        // 3) A pasted base64 blob that is really a newline list of configs.
        decodeConfigList(input)?.let { return it }

        // 4) Fall back to the frozen 7-format paste parser (host:port:user:pass, whitespace form, …).
        return when (val r = ProxyParser.parse(input)) {
            is ProxyParser.Result.Ok -> ImportResult.Single(r.config)
            is ProxyParser.Result.Err -> ImportResult.Rejected(ImportError.Unreadable(input))
        }
    }

    /** Clipboard front door: trims the clip text and imports it (DESIGN_V4 §5). */
    suspend fun fromClipboard(clip: CharSequence?): ImportResult =
        import((clip ?: "").toString())

    /** Deep-link / VIEW-intent front door (`djproxy://`, `socks5://…`, `ss://…`). */
    fun fromDeepLink(uri: String): ImportResult {
        val input = uri.trim()
        if (input.isEmpty()) return ImportResult.Rejected(ImportError.Empty)
        return if (UriConfigParser.schemeOf(input) == "djproxy") {
            UriConfigParser.parseDeepLink(input)
        } else {
            UriConfigParser.parse(input)
        }
    }

    // ---- shape sniffing ---------------------------------------------------------------------------

    /** True for an http(s) URL that carries a resource path or query — i.e. a subscription, not a proxy. */
    private fun looksLikeSubscription(input: String): Boolean {
        val scheme = UriConfigParser.schemeOf(input) ?: return false
        if (scheme != "http" && scheme != "https") return false
        val parts = UriConfigParser.parseGenericUri(input) ?: return false
        val hasPath = parts.path.length > 1 // more than a bare "/"
        val hasQuery = !parts.query.isNullOrEmpty()
        return hasPath || hasQuery
    }

    /** A single-token base64 blob decoding to a newline list of ≥2 config URIs → treat as a subscription. */
    private fun decodeConfigList(input: String): ImportResult? {
        if (input.contains('\n') || input.contains(' ') || input.contains("://")) return null
        val decoded = Base64Compat.decodeToString(input) ?: return null
        if (!decoded.contains("://")) return null
        val uriLines = decoded.lineSequence().map { it.trim() }.count { it.contains("://") }
        if (uriLines < 2) return null
        return SubscriptionFetcher.parseSubscriptionBody(decoded)
    }
}
