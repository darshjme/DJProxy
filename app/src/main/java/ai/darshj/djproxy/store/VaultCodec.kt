package ai.darshj.djproxy.store

import ai.darshj.djproxy.core.ProxyType
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pure, dependency-free (de)serialiser for the vault metadata blob — the seam that makes vault
 * persistence fully unit-testable off-device.
 *
 * There is **no JSON dependency**: JSON (`org.json`) is stubbed to no-ops under
 * `unitTests.isReturnDefaultValues=true`, which would silently break a JSON-backed store in JVM
 * tests. Instead each [SavedProxy] is one line of URL-encoded, unit-separator-delimited fields.
 * URL-encoding neutralises the delimiter, newlines, and any control chars a user might type into a
 * proxy name, so the format is injection-proof and round-trips exactly.
 *
 * Wire format (one record per line):
 * ```
 * id␟name␟typeName␟host␟port␟username␟dnsServer␟origin␟order
 * ```
 * where `␟` = U+241F. Fields [SavedProxy.hasPassword] and [SavedProxy.isDefault] are **not** encoded
 * here: `hasPassword` is derived from the presence of the ciphertext blob and `isDefault` from the
 * separately-stored default id, both overlaid by the store after decode.
 */
object VaultCodec {

    /** Unit separator between fields — a printable sentinel; user text is URL-encoded so it is unique. */
    private const val FIELD = "␟"
    private const val CHARSET = "UTF-8"
    private const val FIELD_COUNT = 9

    /** Serialise the (ordered) list into the newline-delimited blob persisted under `vault.v1`. */
    fun encode(proxies: List<SavedProxy>): String =
        proxies.joinToString("\n") { encodeRecord(it) }

    /**
     * Parse a blob produced by [encode] back into [SavedProxy] rows. Robust to blank lines, trailing
     * whitespace, and malformed records (a record with the wrong field count or an unparseable
     * port/order is skipped rather than crashing the whole load). [SavedProxy.hasPassword] and
     * [SavedProxy.isDefault] are set to false here; the store overlays their true values.
     */
    fun decode(blob: String?): List<SavedProxy> {
        if (blob.isNullOrEmpty()) return emptyList()
        return blob.split("\n")
            .mapNotNull { line -> decodeRecord(line) }
    }

    private fun encodeRecord(p: SavedProxy): String = listOf(
        p.id,
        p.name,
        p.type.name,
        p.host,
        p.port.toString(),
        p.username,
        p.dnsServer,
        p.origin.name,
        p.order.toString(),
    ).joinToString(FIELD) { enc(it) }

    private fun decodeRecord(line: String): SavedProxy? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(FIELD)
        if (parts.size != FIELD_COUNT) return null

        val id = dec(parts[0])
        if (id.isEmpty()) return null
        val name = dec(parts[1])
        val type = parseType(dec(parts[2])) ?: return null
        val host = dec(parts[3])
        val port = dec(parts[4]).toIntOrNull() ?: return null
        val username = dec(parts[5])
        val dnsServer = dec(parts[6]).ifEmpty { "1.1.1.1" }
        val origin = ProxyOrigin.fromName(dec(parts[7]))
        val order = dec(parts[8]).toIntOrNull() ?: 0

        return SavedProxy(
            id = id,
            name = name,
            type = type,
            host = host,
            port = port,
            username = username,
            dnsServer = dnsServer,
            hasPassword = false,
            origin = origin,
            isDefault = false,
            order = order,
        )
    }

    /** Accepts both the enum [ProxyType.name] (SOCKS5/HTTP) and, defensively, a scheme string. */
    private fun parseType(raw: String): ProxyType? =
        ProxyType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: ProxyType.fromScheme(raw)

    private fun enc(s: String): String = URLEncoder.encode(s, CHARSET)

    private fun dec(s: String): String = try {
        URLDecoder.decode(s, CHARSET)
    } catch (_: IllegalArgumentException) {
        s
    }
}
