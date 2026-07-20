package ai.darshj.djproxy.config

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyType
import java.net.URLDecoder

/**
 * Parses a single proxy *URI* into a [ProxyConfig] or a typed [ImportError].
 *
 * Recognised and mapped to a real [ProxyConfig]:
 *   - `socks5://` `socks5h://` `socks://` `http://` `https://`  (delegated to the frozen [ProxyParser])
 *   - `ss://` SIP002 / legacy base64 — **only** when the cipher is `none`/`plain` (a userinfo we can
 *     front); every real Shadowsocks AEAD cipher is rejected by name (DJProxy can't terminate it).
 *   - `djproxy://import|connect` deep links (embedded `uri=` or `host/port/user/pass/type` params).
 *
 * Recognised but **honestly rejected** (full obfuscation/transport protocols DJProxy does not speak):
 *   - `vmess://` `vless://` `trojan://` `hysteria2://` / `hy2://` → [ImportError.UnsupportedProtocol],
 *     with the endpoint extracted for a helpful message. Never a fake success.
 *
 * Pure-JVM, no Android APIs — safe under `unitTests.isReturnDefaultValues` and at runtime on minSdk 21.
 */
object UriConfigParser {

    fun parse(raw: String): ImportResult {
        val input = raw.trim()
        if (input.isEmpty()) return ImportResult.Rejected(ImportError.Empty)

        val scheme = schemeOf(input)
            ?: return ImportResult.Rejected(ImportError.Unreadable(input))

        return when (scheme) {
            // Plain SOCKS/HTTP proxy URIs — parsed directly (IPv6- and #fragment-aware).
            "socks5", "socks5h", "socks", "http" -> fromProxyUri(scheme, input)
            // DJProxy dials proxies in cleartext (raw SOCKS5 / HTTP-CONNECT) and has no TLS-to-proxy
            // support, so an `https://` proxy would be dialed in the clear (leaking Basic creds against a
            // plaintext listener) or simply fail a real TLS handshake. Reject it by name rather than
            // silently mapping it to plain HTTP CONNECT and pretending the hop is encrypted.
            "https" -> ImportResult.Rejected(
                ImportError.UnsupportedProtocol(
                    "https",
                    "DJProxy speaks plaintext SOCKS5/HTTP-CONNECT only and cannot TLS-encrypt the hop to " +
                        "an https proxy — use a socks5:// or http:// endpoint",
                ),
            )
            "ss" -> parseShadowsocks(input)
            "djproxy" -> parseDeepLink(input)
            "vmess" -> rejectVmess(input)
            "vless" -> rejectGeneric("vless", input)
            "trojan" -> rejectGeneric("trojan", input)
            "hysteria2", "hy2" -> rejectGeneric("hysteria2", input)
            else -> ImportResult.Rejected(
                ImportError.UnsupportedProtocol(scheme, "unknown scheme"),
            )
        }
    }

    /**
     * Deep-link entry (`djproxy://import?...` / `djproxy://connect?...`). Two forms:
     *  - `?uri=<url-encoded proxy uri>` → decode and recurse.
     *  - `?host=..&port=..&user=..&pass=..&type=socks5|http` → build directly.
     */
    fun parseDeepLink(raw: String): ImportResult {
        val parts = parseGenericUri(raw)
            ?: return ImportResult.Rejected(ImportError.MalformedUri("djproxy", "no query"))
        val q = parseQuery(parts.query)

        q["uri"]?.let { embedded ->
            val decoded = percentDecode(embedded)
            return parse(decoded)
        }

        val host = q["host"]?.takeIf { it.isNotBlank() }
            ?: return ImportResult.Rejected(
                ImportError.MalformedUri("djproxy", "no uri= or host= parameter"),
            )
        val port = q["port"]?.toIntOrNull()
            ?: return ImportResult.Rejected(ImportError.MalformedUri("djproxy", "missing/invalid port"))
        val type = ProxyType.fromScheme(q["type"] ?: "socks5") ?: ProxyType.SOCKS5
        return buildSingle(
            type = type,
            host = host,
            port = port,
            user = q["user"].orEmpty(),
            pass = q["pass"].orEmpty(),
            schemeForError = "djproxy",
        )
    }

    // ---- socks5:// / http:// (direct, IPv6- and #fragment-aware) ----------------------------------

    private fun fromProxyUri(scheme: String, uri: String): ImportResult {
        val parts = parseGenericUri(uri)
            ?: return ImportResult.Rejected(ImportError.MalformedUri(scheme, "no host"))
        val port = parts.port
            ?: return ImportResult.Rejected(ImportError.MalformedUri(scheme, "missing port"))
        val type = ProxyType.fromScheme(scheme) ?: ProxyType.SOCKS5
        var user = ""
        var pass = ""
        parts.userInfo?.let { ui ->
            val c = ui.indexOf(':')
            if (c >= 0) {
                user = percentDecode(ui.substring(0, c))
                pass = percentDecode(ui.substring(c + 1))
            } else {
                user = percentDecode(ui)
            }
        }
        return buildSingle(type, parts.host, port, user, pass, scheme)
    }

    // ---- ss:// (SIP002 + legacy) ------------------------------------------------------------------

    /**
     * SIP002: `ss://base64url(method:password)@host:port#name`
     * Legacy:  `ss://base64(method:password@host:port)#name`
     *
     * Only `none`/`plain` (unencrypted — a userinfo we can map to a plain endpoint) yields a config;
     * every genuine AEAD cipher is rejected by name because DJProxy cannot terminate Shadowsocks crypto.
     */
    private fun parseShadowsocks(raw: String): ImportResult {
        val afterScheme = raw.substring(raw.indexOf("://") + 3)
        // strip #fragment and ?plugin=... — DJProxy cannot honour SS plugins anyway.
        val body = afterScheme.substringBefore('#').substringBefore('?').trim()
        if (body.isEmpty()) return ImportResult.Rejected(ImportError.MalformedUri("ss", "empty"))

        val method: String
        val password: String
        val host: String
        val port: Int

        val at = body.lastIndexOf('@')
        if (at >= 0) {
            val userPart = body.substring(0, at)
            val hostPart = body.substring(at + 1)
            val mp = decodeMethodPass(userPart)
                ?: return ImportResult.Rejected(ImportError.MalformedUri("ss", "bad userinfo"))
            method = mp.first
            password = mp.second
            val hp = splitHostPort(hostPart)
                ?: return ImportResult.Rejected(ImportError.MalformedUri("ss", "bad host:port"))
            host = hp.first
            port = hp.second
        } else {
            // Fully base64-encoded legacy form.
            val decoded = Base64Compat.decodeToString(body)
                ?: return ImportResult.Rejected(ImportError.MalformedUri("ss", "un-decodable base64"))
            val da = decoded.lastIndexOf('@')
            if (da < 0) return ImportResult.Rejected(ImportError.MalformedUri("ss", "no @ after decode"))
            val mp = decoded.substring(0, da).split(':', limit = 2)
            if (mp.size != 2) return ImportResult.Rejected(ImportError.MalformedUri("ss", "no method:password"))
            method = mp[0]
            password = mp[1]
            val hp = splitHostPort(decoded.substring(da + 1))
                ?: return ImportResult.Rejected(ImportError.MalformedUri("ss", "bad host:port"))
            host = hp.first
            port = hp.second
        }

        return when (method.lowercase()) {
            // "none"/"plain" carry no encryption — best-effort map to a plain SOCKS5 endpoint (DESIGN_V4
            // §5). The pre-flight Validator will still honestly fail if the far end is not real SOCKS5.
            "none", "plain" -> buildSingle(
                type = ProxyType.SOCKS5,
                host = host,
                port = port,
                user = "",
                pass = "",
                schemeForError = "ss",
            )
            else -> ImportResult.Rejected(
                ImportError.UnsupportedProtocol("ss", "$method cipher on $host:$port"),
            )
        }
    }

    /** Returns `method to password`, decoding base64url userinfo or accepting a plain `method:password`. */
    private fun decodeMethodPass(userPart: String): Pair<String, String>? {
        val decoded = Base64Compat.decodeToString(userPart)
        val text = when {
            decoded != null && decoded.contains(':') -> decoded
            userPart.contains(':') -> percentDecode(userPart)
            else -> return null
        }
        val i = text.indexOf(':')
        if (i <= 0) return null
        return text.substring(0, i) to text.substring(i + 1)
    }

    // ---- vmess / vless / trojan / hysteria2 (rejected by name) ------------------------------------

    /** VMess links are `vmess://base64(json)`; decode just enough to name the endpoint, then reject. */
    private fun rejectVmess(raw: String): ImportResult {
        val b64 = raw.substring(raw.indexOf("://") + 3).substringBefore('#').substringBefore('?')
        val json = Base64Compat.decodeToString(b64)
        val add = json?.let { Regex("\"add\"\\s*:\\s*\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
        val port = json?.let { Regex("\"port\"\\s*:\\s*\"?(\\d+)\"?").find(it)?.groupValues?.get(1) }
        val endpoint = when {
            !add.isNullOrBlank() && !port.isNullOrBlank() -> "$add:$port"
            !add.isNullOrBlank() -> add
            else -> "encrypted V2Ray transport"
        }
        return ImportResult.Rejected(ImportError.UnsupportedProtocol("vmess", endpoint))
    }

    /** vless/trojan/hysteria2 share `scheme://auth@host:port` — extract the endpoint, then reject. */
    private fun rejectGeneric(canonicalScheme: String, raw: String): ImportResult {
        val parts = parseGenericUri(raw)
        val endpoint = if (parts?.host?.isNotBlank() == true && parts.port != null) {
            "${parts.host}:${parts.port}"
        } else {
            "encrypted transport"
        }
        return ImportResult.Rejected(ImportError.UnsupportedProtocol(canonicalScheme, endpoint))
    }

    // ---- shared builder ---------------------------------------------------------------------------

    private fun buildSingle(
        type: ProxyType,
        host: String,
        port: Int,
        user: String,
        pass: String,
        schemeForError: String,
    ): ImportResult {
        if (port !in 1..65535) {
            return ImportResult.Rejected(ImportError.MalformedUri(schemeForError, "port $port out of range"))
        }
        val cleanHost = host.trim().removeSurrounding("[", "]")
        if (cleanHost.isEmpty()) {
            return ImportResult.Rejected(ImportError.MalformedUri(schemeForError, "empty host"))
        }
        return ImportResult.Single(
            ProxyConfig(type = type, host = cleanHost, port = port, username = user, password = pass),
        )
    }

    // ---- tiny pure-JVM URI helpers (android.net.Uri is unavailable in unit tests) -----------------

    internal data class UriParts(
        val scheme: String,
        val userInfo: String?,
        val host: String,
        val port: Int?,
        val path: String,
        val query: String?,
        val fragment: String?,
    )

    /** Lowercased scheme before `://`, or null if there is none. */
    internal fun schemeOf(raw: String): String? {
        val i = raw.indexOf("://")
        if (i <= 0) return null
        val s = raw.substring(0, i)
        if (s.any { !(it.isLetterOrDigit() || it == '+' || it == '-' || it == '.') }) return null
        return s.lowercase()
    }

    /** Hand-rolled `scheme://[user@]host[:port][/path][?query][#frag]` splitter, IPv6-aware. */
    internal fun parseGenericUri(raw: String): UriParts? {
        val schemeSep = raw.indexOf("://")
        if (schemeSep <= 0) return null
        val scheme = raw.substring(0, schemeSep).lowercase()
        var rest = raw.substring(schemeSep + 3)

        var fragment: String? = null
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            fragment = rest.substring(hash + 1)
            rest = rest.substring(0, hash)
        }
        var query: String? = null
        val qm = rest.indexOf('?')
        if (qm >= 0) {
            query = rest.substring(qm + 1)
            rest = rest.substring(0, qm)
        }
        var path = ""
        val slash = rest.indexOf('/')
        if (slash >= 0) {
            path = rest.substring(slash)
            rest = rest.substring(0, slash)
        }

        var authority = rest
        var userInfo: String? = null
        val at = authority.lastIndexOf('@')
        if (at >= 0) {
            userInfo = authority.substring(0, at)
            authority = authority.substring(at + 1)
        }
        if (authority.isEmpty()) return null

        val host: String
        val port: Int?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(1, close)
            val after = authority.substring(close + 1)
            port = if (after.startsWith(":")) after.substring(1).toIntOrNull() else null
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                host = authority.substring(0, colon)
                port = authority.substring(colon + 1).toIntOrNull()
            } else {
                host = authority
                port = null
            }
        }
        return UriParts(scheme, userInfo, host, port, path, query, fragment)
    }

    /** `host:port` (IPv6-aware) → pair, or null. */
    internal fun splitHostPort(s: String): Pair<String, Int>? {
        val t = s.trim()
        if (t.startsWith("[")) {
            val close = t.indexOf(']')
            if (close < 0) return null
            val host = t.substring(1, close)
            val after = t.substring(close + 1)
            val port = if (after.startsWith(":")) after.substring(1).toIntOrNull() else null
            return if (port != null && host.isNotEmpty()) host to port else null
        }
        val i = t.lastIndexOf(':')
        if (i <= 0 || i == t.length - 1) return null
        val port = t.substring(i + 1).toIntOrNull() ?: return null
        val host = t.substring(0, i)
        return if (host.isNotEmpty()) host to port else null
    }

    internal fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) {
                out[formDecode(pair)] = ""
            } else {
                out[formDecode(pair.substring(0, eq))] = formDecode(pair.substring(eq + 1))
            }
        }
        return out
    }

    /**
     * Percent-decodes `%XX` escapes only, leaving a literal `+` untouched. Used for URI **userinfo**,
     * host and fragment components, where per RFC 3986 `+` is an ordinary unreserved character — NOT a
     * space. (java.net.URLDecoder implements application/x-www-form-urlencoded and turns `+` into a
     * space, which silently corrupts passwords/usernames that legitimately contain `+`.)
     */
    internal fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            val bytes = ch.toString().toByteArray(Charsets.UTF_8)
            out.write(bytes, 0, bytes.size)
            i++
        }
        return out.toString("UTF-8")
    }

    /** Query-string decode: `%XX` escapes AND `+` → space (x-www-form-urlencoded). Query values only. */
    private fun formDecode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}

/**
 * Minimal, dependency-free Base64 decoder (standard **and** URL-safe alphabets, optional padding,
 * whitespace-tolerant). Written by hand because `java.util.Base64` is API 26+ and `android.util.Base64`
 * returns stubbed empty values under `unitTests.isReturnDefaultValues` — neither is usable across
 * DJProxy's minSdk-21 + unit-test matrix.
 */
internal object Base64Compat {
    private val INV = IntArray(128) { -1 }.apply {
        val std = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in std.indices) this[std[i].code] = i
        this['-'.code] = 62 // URL-safe
        this['_'.code] = 63 // URL-safe
    }

    /** Upper bound on the base64 payload we will decode (~8 MiB of alphabet chars). A hostile paste /
     *  subscription body past this is treated as "not base64" rather than allocating unbounded memory. */
    private const val MAX_INPUT_CHARS = 8 * 1024 * 1024

    /** @return the decoded UTF-8 string, or null if the input holds no valid base64 payload. */
    fun decodeToString(input: String): String? {
        if (input.length > MAX_INPUT_CHARS) return null
        val chars = StringBuilder(minOf(input.length, MAX_INPUT_CHARS))
        for (c in input) {
            if (c.code < 128 && INV[c.code] >= 0) chars.append(c)
        }
        if (chars.length < 2) return null
        // ByteArrayOutputStream, not ArrayList<Byte>: avoid boxing every decoded byte on large inputs.
        val out = java.io.ByteArrayOutputStream(chars.length * 3 / 4 + 3)
        var i = 0
        while (i + 1 < chars.length) {
            val b0 = INV[chars[i].code]
            val b1 = INV[chars[i + 1].code]
            out.write((b0 shl 2) or (b1 shr 4))
            if (i + 2 < chars.length) {
                val b2 = INV[chars[i + 2].code]
                out.write(((b1 and 0x0F) shl 4) or (b2 shr 2))
                if (i + 3 < chars.length) {
                    val b3 = INV[chars[i + 3].code]
                    out.write(((b2 and 0x03) shl 6) or b3)
                }
            }
            i += 4
        }
        if (out.size() == 0) return null
        return out.toString("UTF-8")
    }
}
