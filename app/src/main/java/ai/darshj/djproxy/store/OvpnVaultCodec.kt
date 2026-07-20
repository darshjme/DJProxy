package ai.darshj.djproxy.store

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pure, dependency-free (de)serialiser for the `.ovpn` vault **metadata** blob — the seam that makes
 * saved-profile persistence fully unit-testable off-device. The direct mirror of [VaultCodec] (the
 * proxy vault's codec): there is **no JSON dependency** (JSON `org.json` is stubbed to no-ops under
 * `unitTests.isReturnDefaultValues=true`, which would silently break a JSON-backed store in JVM
 * tests), so each [SavedOvpn] is one line of URL-encoded, unit-separator-delimited fields.
 * URL-encoding neutralises the delimiter, newlines, and any control chars a user might type into a
 * profile name, so the format is injection-proof and round-trips exactly.
 *
 * Wire format (one record per line):
 * ```
 * id␟name␟countryShort␟countryLong␟hostName␟order
 * ```
 * where `␟` = U+241F. The bulky `.ovpn` text is **not** encoded here — it lives under the separate
 * `ovpn.<id>` key (mirroring the proxy vault's `pw.<id>`) so the metadata index stays compact.
 */
object OvpnVaultCodec {

    /** Unit separator between fields — a printable sentinel; user text is URL-encoded so it is unique. */
    private const val FIELD = "␟"
    private const val CHARSET = "UTF-8"
    private const val FIELD_COUNT = 6

    /** Serialise the (ordered) list into the newline-delimited blob persisted under `ovpnvault.v1`. */
    fun encode(profiles: List<SavedOvpn>): String =
        profiles.joinToString("\n") { encodeRecord(it) }

    /**
     * Parse a blob produced by [encode] back into [SavedOvpn] rows. Robust to blank lines, trailing
     * whitespace, and malformed records (a record with the wrong field count or an unparseable order
     * is skipped rather than crashing the whole load).
     */
    fun decode(blob: String?): List<SavedOvpn> {
        if (blob.isNullOrEmpty()) return emptyList()
        return blob.split("\n")
            .mapNotNull { line -> decodeRecord(line) }
    }

    private fun encodeRecord(p: SavedOvpn): String = listOf(
        p.id,
        p.name,
        p.countryShort,
        p.countryLong,
        p.hostName,
        p.order.toString(),
    ).joinToString(FIELD) { enc(it) }

    private fun decodeRecord(line: String): SavedOvpn? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(FIELD)
        if (parts.size != FIELD_COUNT) return null

        val id = dec(parts[0])
        if (id.isEmpty()) return null
        val name = dec(parts[1])
        val countryShort = dec(parts[2])
        val countryLong = dec(parts[3])
        val hostName = dec(parts[4])
        val order = dec(parts[5]).toIntOrNull() ?: 0

        return SavedOvpn(
            id = id,
            name = name,
            countryShort = countryShort,
            countryLong = countryLong,
            hostName = hostName,
            order = order,
        )
    }

    private fun enc(s: String): String = URLEncoder.encode(s, CHARSET)

    private fun dec(s: String): String = try {
        URLDecoder.decode(s, CHARSET)
    } catch (_: IllegalArgumentException) {
        s
    }
}
