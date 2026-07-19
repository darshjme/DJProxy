package ai.darshj.djproxy.dns

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Pure HTTP/1.1 framing for RFC 8484 DNS-over-HTTPS (`POST /dns-query`,
 * `Content-Type: application/dns-message`). Kept free of sockets/TLS so the request/response wire
 * format is unit-testable off-device (see DohResolverTest). [DohResolver] supplies the transport.
 */
object DohHttp {

    const val PATH = "/dns-query"
    private const val CT = "application/dns-message"

    /** Builds the exact bytes of a DoH POST for [query] to virtual host [host]. */
    fun buildRequest(query: ByteArray, host: String): ByteArray {
        val head = buildString {
            append("POST ").append(PATH).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("Accept: ").append(CT).append("\r\n")
            append("Content-Type: ").append(CT).append("\r\n")
            append("Content-Length: ").append(query.size).append("\r\n")
            append("Connection: close\r\n")
            append("User-Agent: djproxy\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(head.size + query.size)
        System.arraycopy(head, 0, out, 0, head.size)
        System.arraycopy(query, 0, out, head.size, query.size)
        return out
    }

    /**
     * Reads one HTTP response from [input] and returns the raw `application/dns-message` body, or
     * null on any non-200 / malformed / empty response. Binary-safe (never uses a char reader over
     * the body). Handles `Content-Length` and `Connection: close` (read-to-EOF); minimal chunked.
     */
    fun parseResponse(input: InputStream): ByteArray? {
        val header = readHead(input) ?: return null
        val firstLine = header.substringBefore("\r\n")
        val code = parseStatusCode(firstLine) ?: return null
        if (code != 200) return null

        val lower = header.lowercase()
        if (lower.contains("transfer-encoding:") && lower.contains("chunked")) {
            return readChunked(input)?.takeIf { it.isNotEmpty() }
        }
        val contentLength = headerValue(header, "content-length")?.trim()?.toIntOrNull()
        val body = if (contentLength != null) {
            if (contentLength <= 0 || contentLength > MAX_BODY) return null
            readFully(input, contentLength) ?: return null
        } else {
            readToEnd(input)
        }
        return body.takeIf { it.isNotEmpty() }
    }

    /** Parses `HTTP/1.1 200 OK` → 200; null if not a status line. */
    fun parseStatusCode(firstLine: String): Int? {
        if (!firstLine.startsWith("HTTP/")) return null
        val parts = firstLine.split(' ', limit = 3)
        if (parts.size < 2) return null
        return parts[1].toIntOrNull()
    }

    private fun headerValue(header: String, nameLower: String): String? {
        for (line in header.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            if (line.substring(0, idx).trim().lowercase() == nameLower) {
                return line.substring(idx + 1)
            }
        }
        return null
    }

    /** Reads bytes up to and including the first CRLFCRLF, returned as an ASCII string; null if EOF. */
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var m = 0
        while (out.size() < MAX_HEAD) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString("US-ASCII")
            out.write(b)
            m = when {
                m == 0 && b == 13 -> 1
                m == 1 && b == 10 -> 2
                m == 2 && b == 13 -> 3
                m == 3 && b == 10 -> 4
                b == 13 -> 1
                else -> 0
            }
            if (m == 4) break
        }
        return out.toString("US-ASCII")
    }

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) return null
            off += r
        }
        return b
    }

    private fun readToEnd(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(2048)
        while (out.size() < MAX_BODY) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        while (out.size() < MAX_BODY) {
            val sizeLine = readLine(input) ?: return null
            val size = sizeLine.trim().substringBefore(';').toIntOrNull(16) ?: return null
            if (size == 0) break
            // Reject an attacker-declared oversized chunk BEFORE allocating a buffer for it (OOM guard).
            if (size < 0 || size > MAX_BODY - out.size()) return null
            val chunk = readFully(input, size) ?: return null
            out.write(chunk)
            readLine(input) // trailing CRLF after chunk data
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString("US-ASCII")
            if (b == 10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(b)
        }
    }

    private const val MAX_HEAD = 16_384
    private const val MAX_BODY = 0xFFFF
}
