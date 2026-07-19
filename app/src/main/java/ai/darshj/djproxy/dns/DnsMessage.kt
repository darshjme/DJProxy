package ai.darshj.djproxy.dns

import java.io.ByteArrayOutputStream

/**
 * Pure, side-effect-free DNS wire helpers shared by every [DnsResolver] and by [DnsMessage]-based
 * caching in the interceptor. Extracted out of the old LeakPolicy so it is unit-testable off-device
 * (no sockets, no Android). Everything here operates on a raw DNS *message* (no length prefix).
 */
object DnsMessage {

    const val HEADER_LEN = 12

    /**
     * A stable cache/coalesce key = the question section bytes (qname|qtype|qclass), excluding the
     * transaction ID and flags. Two queries for the same name+type share one upstream round-trip.
     * @return the key, or null if the message is malformed / uses a compression pointer in the qname.
     */
    fun questionKey(query: ByteArray): String? {
        if (query.size < HEADER_LEN) return null
        var p = HEADER_LEN
        while (p < query.size) {
            val len = query[p].toInt() and 0xFF
            if (len == 0) { p += 1; break }
            if (len and 0xC0 != 0) return null // no compression pointers in a well-formed query name
            p += 1 + len
            if (p > query.size) return null
        }
        val end = (p + 4).coerceAtMost(query.size) // + QTYPE(2) + QCLASS(2)
        if (end <= HEADER_LEN) return null
        return String(query, HEADER_LEN, end - HEADER_LEN, Charsets.ISO_8859_1)
    }

    /** Returns a copy of [answer] with its transaction ID overwritten to match [query]'s ID. */
    fun withId(answer: ByteArray, query: ByteArray): ByteArray {
        if (answer.size < 2 || query.size < 2) return answer
        val out = answer.copyOf()
        out[0] = query[0]; out[1] = query[1]
        return out
    }

    /** Prefixes [msg] with the RFC 7766 / RFC 7858 2-byte big-endian length (for DoT / DNS-over-TCP). */
    fun frame(msg: ByteArray): ByteArray {
        val out = ByteArray(2 + msg.size)
        out[0] = ((msg.size ushr 8) and 0xFF).toByte()
        out[1] = (msg.size and 0xFF).toByte()
        System.arraycopy(msg, 0, out, 2, msg.size)
        return out
    }

    /** Parses a 2-byte big-endian length prefix. Returns -1 if out of the valid DNS range. */
    fun parseLength(hi: Byte, lo: Byte): Int {
        val n = ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
        return if (n in 1..0xFFFF) n else -1
    }

    /** Builds a minimal standard A/AAAA query for [name] with a given transaction [id]. */
    fun buildQuery(id: Int, name: String, qtype: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id ushr 8) and 0xFF); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00)            // flags: standard query, recursion desired
        out.write(0x00); out.write(0x01)            // QDCOUNT = 1
        out.write(0x00); out.write(0x00)            // ANCOUNT
        out.write(0x00); out.write(0x00)            // NSCOUNT
        out.write(0x00); out.write(0x00)            // ARCOUNT
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0x00)                             // root label
        out.write((qtype ushr 8) and 0xFF); out.write(qtype and 0xFF)
        out.write(0x00); out.write(0x01)            // QCLASS = IN
        return out.toByteArray()
    }

    /** True if [resp] (length [len]) is a well-formed DNS response whose ID matches [id]. */
    fun isResponseFor(id: Int, resp: ByteArray, len: Int): Boolean {
        if (len < HEADER_LEN) return false
        val respId = ((resp[0].toInt() and 0xFF) shl 8) or (resp[1].toInt() and 0xFF)
        val qr = (resp[2].toInt() and 0x80) != 0 // response bit
        return respId == id && qr
    }

    /** True if the response carries at least one answer record (ANCOUNT > 0). */
    fun hasAnswers(resp: ByteArray, len: Int): Boolean {
        if (len < HEADER_LEN) return false
        val anCount = ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)
        return anCount > 0
    }
}
