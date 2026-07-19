package ai.darshj.djproxy.location

import ai.darshj.djproxy.dns.DohResolver
import ai.darshj.djproxy.proxy.DialResult
import ai.darshj.djproxy.proxy.UpstreamDialer
import ai.darshj.djproxy.vpn.seams.SpoofedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket

/**
 * Resolves the proxy EXIT IP to a lat/lng by asking an IP-geolocation service — but the HTTP request
 * is dialed THROUGH the proxy ([UpstreamDialer]) and TLS-wrapped at the exit, so the service sees the
 * proxy's egress address (correct geo), never the phone's real IP. This is the whole point: the
 * spoofed device location must match what a website would see, and a website sees the exit.
 *
 * Never throws (the [ai.darshj.djproxy.vpn.seams.LocationController] seam forbids it): every failure
 * path returns null and the controller simply publishes nothing.
 */
class ExitGeoResolver(
    private val transport: GeoHttpTransport,
    private val endpoints: List<GeoEndpoint> = GeoEndpoint.DEFAULTS,
) {

    /**
     * @param exitIp the proxy egress IP if core already observed it (preferred — we then query that
     *   exact IP so the answer is stable even if a later request egresses a different pool member);
     *   null falls back to the endpoint's "my current IP" path (still routed through the proxy).
     */
    suspend fun resolve(exitIp: String?): SpoofedLocation? {
        for (ep in endpoints) {
            val path = if (!exitIp.isNullOrBlank() && looksLikeIp(exitIp)) ep.pathFor(exitIp) else ep.selfPath
            val body = runCatching { transport.get(ep.host, path) }.getOrNull() ?: continue
            val geo = GeoParser.parse(body) ?: continue
            val label = geo.label.ifBlank { exitIp?.takeIf { it.isNotBlank() } ?: "proxy exit" }
            return SpoofedLocation(
                lat = geo.lat,
                lng = geo.lng,
                label = label,
                source = "exit-geo:${ep.host}",
            )
        }
        return null
    }

    private fun looksLikeIp(s: String): Boolean {
        // Accept IPv4 dotted-quad or anything containing ':' (IPv6). The geo APIs accept both.
        if (s.contains(':')) return true
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }
}

/**
 * One IP-geolocation endpoint reachable over HTTPS. All three below are keyless and return JSON that
 * [GeoParser] understands; the resolver tries them in order so one being down/blocked is survivable.
 */
data class GeoEndpoint(
    val host: String,
    /** Path that reports the CALLER's current (exit) IP. */
    val selfPath: String,
    private val ipPrefix: String,
    private val ipSuffix: String,
) {
    fun pathFor(ip: String): String = "$ipPrefix$ip$ipSuffix"

    companion object {
        val DEFAULTS: List<GeoEndpoint> = listOf(
            // ipinfo.io/json  and  ipinfo.io/<ip>/json  → {"loc":"37.3,-122.0","city":..,"region":..,"country":..}
            GeoEndpoint("ipinfo.io", "/json", "/", "/json"),
            // ipwho.is/  and  ipwho.is/<ip>  → {"latitude":..,"longitude":..,"city":..,"region":..,"country":..}
            GeoEndpoint("ipwho.is", "/", "/", ""),
            // ipapi.co/json/  and  ipapi.co/<ip>/json/  → {"latitude":..,"longitude":..,"city":..,"region":..,"country_name":..}
            GeoEndpoint("ipapi.co", "/json/", "/", "/json/"),
        )
    }
}

/** Pull one HTTPS GET body through the proxy exit. Returns the response body text or null. */
fun interface GeoHttpTransport {
    suspend fun get(host: String, path: String): String?
}

/**
 * Default transport: dials the geo host:443 through the proxy [UpstreamDialer], wraps the tunnelled
 * socket in a verified TLS session (reusing the DNS lane's TLS helper so SNI/host-verify are
 * identical), issues an HTTP/1.1 GET, and returns the body. Because the CONNECT is tunnelled, the
 * TLS + HTTP conversation happens at the proxy exit.
 */
class ProxyGeoTransport(
    private val dialer: UpstreamDialer,
    private val timeoutMs: Int = 8_000,
    private val tlsWrap: (Socket, String) -> Socket = DohResolver::defaultTlsWrap,
) : GeoHttpTransport {

    override suspend fun get(host: String, path: String): String? = withContext(Dispatchers.IO) {
        val dial = runCatching { dialer.connect(host, 443) }.getOrNull() ?: return@withContext null
        val raw: Socket = when (dial) {
            is DialResult.Ok -> dial.socket
            is DialResult.Fail -> return@withContext null
        }
        var tls: Socket? = null
        try {
            raw.soTimeout = timeoutMs
            tls = tlsWrap(raw, host)
            tls.soTimeout = timeoutMs
            val req = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("User-Agent: djproxy\r\n")
                append("Accept: application/json\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)
            tls.getOutputStream().apply { write(req); flush() }
            HttpText.readBody(tls.getInputStream())
        } catch (_: Exception) {
            null
        } finally {
            runCatching { (tls ?: raw).close() }
            runCatching { raw.close() }
        }
    }
}

/** A parsed geo answer: coordinates plus a human "City, Region, Country" label. */
data class GeoResult(val lat: Double, val lng: Double, val label: String)

/**
 * Tolerant, dependency-free JSON scalar extractor for the three geo-API shapes we query. We do NOT
 * pull in a JSON library: the fields we need are flat scalars, and a targeted key extractor is both
 * smaller and immune to the APIs adding/removing unrelated fields. Handles:
 *   - ipinfo:  "loc":"37.386,-122.083", "city", "region", "country"
 *   - ipwho.is:"latitude":37.386, "longitude":-122.083, "city", "region", "country"
 *   - ipapi.co:"latitude", "longitude", "city", "region", "country_name" / "country"
 */
object GeoParser {

    fun parse(json: String): GeoResult? {
        val (lat, lng) = extractLatLng(json) ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        // Reject the null-island 0,0 that broken/blocked lookups often emit.
        if (lat == 0.0 && lng == 0.0) return null
        return GeoResult(lat, lng, buildLabel(json))
    }

    private fun extractLatLng(json: String): Pair<Double, Double>? {
        // ipinfo "loc":"lat,lng"
        extractRaw(json, "loc")?.let { loc ->
            val p = loc.split(',')
            if (p.size == 2) {
                val a = p[0].trim().toDoubleOrNull()
                val b = p[1].trim().toDoubleOrNull()
                if (a != null && b != null) return a to b
            }
        }
        val lat = firstNumber(json, listOf("latitude", "lat"))
        val lng = firstNumber(json, listOf("longitude", "lon", "lng", "long"))
        if (lat != null && lng != null) return lat to lng
        return null
    }

    private fun buildLabel(json: String): String {
        val city = firstString(json, listOf("city"))
        val region = firstString(json, listOf("region", "regionName", "region_name", "state_prov"))
        val country = firstString(json, listOf("country_name", "countryName", "country"))
        return listOf(city, region, country).filter { !it.isNullOrBlank() }.joinToString(", ")
    }

    private fun firstNumber(json: String, keys: List<String>): Double? {
        for (k in keys) extractRaw(json, k)?.trim()?.toDoubleOrNull()?.let { return it }
        return null
    }

    private fun firstString(json: String, keys: List<String>): String? {
        for (k in keys) extractRaw(json, k)?.let { if (it.isNotBlank()) return it }
        return null
    }

    /**
     * Returns the raw scalar value for `"key"` — the contents of a quoted string, or the literal token
     * of a bare number/bool. Searches for the KEY WITH ITS QUOTES (`"lat"`), so `"latitude"` can never
     * be matched by a request for `"lat"`.
     */
    private fun extractRaw(json: String, key: String): String? {
        val needle = "\"$key\""
        var i = json.indexOf(needle)
        if (i < 0) return null
        i += needle.length
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != ':') return null
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length) return null
        return if (json[i] == '"') {
            val end = json.indexOf('"', i + 1)
            if (end < 0) null else json.substring(i + 1, end)
        } else {
            val start = i
            while (i < json.length && json[i] != ',' && json[i] != '}' && json[i] != ']' && !json[i].isWhitespace()) i++
            json.substring(start, i)
        }
    }
}

/** Minimal HTTP/1.1 response reader (200-only, Content-Length | chunked | Connection: close). */
internal object HttpText {

    private const val MAX_HEAD = 16_384
    private const val MAX_BODY = 1 shl 20 // 1 MiB is far more than any geo JSON

    fun readBody(input: InputStream): String? {
        val header = readHead(input) ?: return null
        val firstLine = header.substringBefore("\r\n")
        val code = statusCode(firstLine) ?: return null
        if (code != 200) return null

        val lower = header.lowercase()
        val bytes = if (lower.contains("transfer-encoding:") && lower.contains("chunked")) {
            readChunked(input)
        } else {
            val len = headerValue(header, "content-length")?.trim()?.toIntOrNull()
            if (len != null) {
                if (len <= 0 || len > MAX_BODY) return null
                readFully(input, len)
            } else {
                readToEnd(input)
            }
        } ?: return null
        return if (bytes.isEmpty()) null else String(bytes, Charsets.UTF_8)
    }

    private fun statusCode(firstLine: String): Int? {
        if (!firstLine.startsWith("HTTP/")) return null
        val parts = firstLine.split(' ', limit = 3)
        if (parts.size < 2) return null
        return parts[1].toIntOrNull()
    }

    private fun headerValue(header: String, nameLower: String): String? {
        for (line in header.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            if (line.substring(0, idx).trim().lowercase() == nameLower) return line.substring(idx + 1)
        }
        return null
    }

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
        val buf = ByteArray(4096)
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
            readLine(input) // trailing CRLF
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
}
