package ai.darshj.djproxy.hotspot

import ai.darshj.djproxy.proxy.DialResult
import ai.darshj.djproxy.proxy.UpstreamDialer
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections

/**
 * The honest, root-free "router": a proxy endpoint bound to the LAN/hotspot interface that OTHER
 * devices point at. It speaks BOTH protocols on ONE port (auto-detected from the first byte):
 *
 *  - **SOCKS5** (RFC 1928 + optional RFC 1929 user/pass) — CONNECT only.
 *  - **HTTP proxy** — `CONNECT host:port` (HTTPS tunnels) AND absolute-form (`GET http://host/…`)
 *    with optional `Proxy-Authorization: Basic`.
 *
 * Every accepted client request is dialled through DJProxy's OWN upstream proxy via the injected
 * [UpstreamDialer] (obtained from `VpnDependencies.dialerFactory`, already `protect()`-ed), so a
 * tethered device's traffic exits through the same upstream the phone uses — with no device-DNS or
 * direct-egress leak. This is deliberately NOT the fake "tethered traffic magically joins the VPN"
 * story that stock Android cannot deliver; the client must point at this endpoint.
 *
 * Fail-closed: any handshake/dial failure closes both sockets, so a client never gets a half-open
 * tunnel that could fall through to a direct connection.
 *
 * The wire parsing is factored into pure functions ([parseRequestLine], [hostPortFromConnect],
 * [hostPortFromAbsoluteUri], [httpAuthOk]) so request handling is unit-tested without sockets, and
 * the whole server is exercised end-to-end against a fake dialer.
 */
class LanShareServer(
    private val bindAddress: InetAddress,
    private val requireAuth: Boolean,
    private val credential: LanCredential?,
    /** Supplies the proxy-tunnelled dialer; null when no proxy is configured (share cannot serve). */
    private val dialerProvider: () -> UpstreamDialer?,
    private val onError: (String) -> Unit = {},
    private val preferredPort: Int = DEFAULT_PORT,
) {
    /** Parsed request line and absolute-URI target — class-level so tests can name them directly. */
    data class HttpRequestLine(val method: String, val target: String, val version: String)
    data class AbsoluteTarget(val host: String, val port: Int, val pathAndQuery: String)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    /**
     * Every live socket (client + its upstream) for in-flight tunnels, so [stop] can fail-closed by
     * closing them all — not just the listening socket. Without this, an already-accepted pump keeps
     * proxying after "Stop sharing".
     */
    private val live: MutableSet<Socket> = Collections.synchronizedSet(HashSet())

    /** The port clients must dial. Valid only after [start]; -1 before/after. */
    val listenPort: Int get() = server?.localPort ?: -1

    /** The concrete address the endpoint is bound to (for tests: must be the LAN addr, never 0.0.0.0). */
    val boundAddress: InetAddress? get() = server?.inetAddress

    /**
     * Binds the endpoint and starts accepting. Tries [preferredPort] first (a stable, QR-able port),
     * falling back to an ephemeral port if it is taken. Returns the bound port, or -1 on failure.
     */
    fun start(): Int {
        if (running) return listenPort
        val ss = ServerSocket()
        ss.reuseAddress = true
        val bound = try {
            ss.bind(InetSocketAddress(bindAddress, preferredPort))
            true
        } catch (_: IOException) {
            runCatching { ss.bind(InetSocketAddress(bindAddress, 0)) }.isSuccess
        }
        if (!bound) {
            runCatching { ss.close() }
            onError("Could not bind LAN share socket")
            return -1
        }
        server = ss
        running = true
        Thread({ acceptLoop(ss) }, "djproxy-lanshare-accept").apply { isDaemon = true }.start()
        return ss.localPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        // Fail-closed: tear down every in-flight tunnel so no pump keeps forwarding after stop.
        val snapshot = synchronized(live) { live.toList() }
        snapshot.forEach { closeQuietly(it) }
        live.clear()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val client = try {
                ss.accept()
            } catch (_: IOException) {
                if (running) continue else break
            }
            Thread({ handle(client) }, "djproxy-lanshare-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        live.add(client)
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val ins = client.getInputStream()
            val out = client.getOutputStream()

            val first = ins.read()
            if (first == -1) return
            upstream = if (first == SOCKS_VER) {
                serveSocks5(ins, out)
            } else {
                serveHttp(ins, out, first)
            }
            val up = upstream
            if (up != null) {
                // Register the upstream BEFORE pumping (pump blocks) so stop() can close it mid-flight.
                live.add(up)
                client.soTimeout = 0
                pump(client, up)
            }
        } catch (_: IOException) {
            // fail-closed (includes SocketTimeoutException on a stalled handshake)
        } finally {
            upstream?.let { live.remove(it) }
            live.remove(client)
            closeQuietly(upstream)
            closeQuietly(client)
        }
    }

    // ---- SOCKS5 (RFC 1928 / 1929) ---------------------------------------------------------------

    /** Returns the connected upstream socket on success (the caller pumps + owns/closes it), else null. */
    private fun serveSocks5(ins: InputStream, out: OutputStream): Socket? {
        // The 0x05 version byte was already consumed by the sniff.
        val nMethods = ins.read()
        if (nMethods <= 0) return null
        val methods = readFully(ins, nMethods)

        if (requireAuth) {
            if (!methods.contains(METHOD_USERPASS.toByte())) {
                out.write(byteArrayOf(SOCKS_VER.toByte(), METHOD_NO_ACCEPTABLE.toByte())); out.flush()
                return null
            }
            out.write(byteArrayOf(SOCKS_VER.toByte(), METHOD_USERPASS.toByte())); out.flush()
            if (!socksUserPassOk(ins, out)) return null
        } else {
            out.write(byteArrayOf(SOCKS_VER.toByte(), METHOD_NONE.toByte())); out.flush()
        }

        val req = readSocksRequest(ins) ?: run {
            socksReply(out, SOCKS_REP_GENERAL); return null
        }
        if (req.cmd != CMD_CONNECT) {
            socksReply(out, SOCKS_REP_CMD_UNSUPPORTED); return null
        }
        // SSRF guard: never let a shared client pivot into the phone's own LAN / loopback via a literal.
        if (isBlockedTarget(req.host)) {
            onError("Blocked LAN-share target ${req.host} (private/loopback)")
            socksReply(out, SOCKS_REP_HOST_UNREACHABLE); return null
        }
        val dialer = dialerProvider() ?: run {
            onError("No proxy configured — LAN share cannot forward")
            socksReply(out, SOCKS_REP_GENERAL); return null
        }
        return when (val dial = runBlocking { dialer.connect(req.host, req.port) }) {
            is DialResult.Fail -> {
                socksReply(out, SOCKS_REP_HOST_UNREACHABLE); null
            }
            is DialResult.Ok -> {
                socksReply(out, SOCKS_REP_SUCCEEDED)
                dial.socket
            }
        }
    }

    /** RFC 1929 username/password sub-negotiation. Replies 0x01,0x00 on success; 0x01,0x01 on fail. */
    private fun socksUserPassOk(ins: InputStream, out: OutputStream): Boolean {
        val ver = ins.read(); if (ver != AUTH_VER) return false
        val uLen = ins.read(); if (uLen < 0) return false
        val user = String(readFully(ins, uLen), Charsets.UTF_8)
        val pLen = ins.read(); if (pLen < 0) return false
        val pass = String(readFully(ins, pLen), Charsets.UTF_8)
        // Constant-time compare so response timing does not leak how many leading bytes matched.
        val ok = credential != null &&
            constantTimeEquals(user, credential.user) &&
            constantTimeEquals(pass, credential.pass)
        out.write(byteArrayOf(AUTH_VER.toByte(), if (ok) 0x00 else 0x01)); out.flush()
        return ok
    }

    private data class SocksRequest(val cmd: Int, val host: String, val port: Int)

    private fun readSocksRequest(ins: InputStream): SocksRequest? {
        val ver = ins.read(); if (ver != SOCKS_VER) return null
        val cmd = ins.read(); if (cmd < 0) return null
        ins.read() // RSV
        val host = when (ins.read()) {
            ATYP_IPV4 -> {
                val a = readFully(ins, 4)
                "${a[0].toInt() and 0xFF}.${a[1].toInt() and 0xFF}.${a[2].toInt() and 0xFF}.${a[3].toInt() and 0xFF}"
            }
            ATYP_DOMAIN -> {
                val len = ins.read(); if (len <= 0) return null
                String(readFully(ins, len), Charsets.US_ASCII)
            }
            ATYP_IPV6 -> {
                val a = readFully(ins, 16)
                (0 until 16 step 2).joinToString(":") {
                    "%02x%02x".format(a[it].toInt() and 0xFF, a[it + 1].toInt() and 0xFF)
                }
            }
            else -> return null
        }
        val p = readFully(ins, 2)
        val port = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        return SocksRequest(cmd, host, port)
    }

    private fun socksReply(out: OutputStream, rep: Int) {
        out.write(byteArrayOf(SOCKS_VER.toByte(), rep.toByte(), 0x00, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    // ---- HTTP proxy -----------------------------------------------------------------------------

    private fun serveHttp(ins: InputStream, out: OutputStream, firstByte: Int): Socket? {
        val head = readHttpHead(ins, firstByte) ?: return null
        val lines = head.split("\r\n")
        val req = parseRequestLine(lines.firstOrNull().orEmpty()) ?: run {
            writeHttpStatus(out, 400, "Bad Request"); return null
        }
        val headers = lines.drop(1).filter { it.isNotEmpty() }

        if (requireAuth && !httpAuthOk(headers, credential)) {
            writeHttpStatus(
                out, 407, "Proxy Authentication Required",
                extra = "Proxy-Authenticate: Basic realm=\"DJProxy\"\r\n",
            )
            return null
        }

        val dialer = dialerProvider() ?: run {
            onError("No proxy configured — LAN share cannot forward")
            writeHttpStatus(out, 502, "Bad Gateway"); return null
        }

        return if (req.method.equals("CONNECT", ignoreCase = true)) {
            val hp = hostPortFromConnect(req.target) ?: run {
                writeHttpStatus(out, 400, "Bad Request"); return null
            }
            if (isBlockedTarget(hp.first)) {
                onError("Blocked LAN-share target ${hp.first} (private/loopback)")
                writeHttpStatus(out, 403, "Forbidden"); return null
            }
            when (val dial = runBlocking { dialer.connect(hp.first, hp.second) }) {
                is DialResult.Fail -> {
                    writeHttpStatus(out, 502, "Bad Gateway"); null
                }
                is DialResult.Ok -> {
                    out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                    dial.socket
                }
            }
        } else {
            val abs = hostPortFromAbsoluteUri(req.target) ?: run {
                writeHttpStatus(out, 400, "Bad Request"); return null
            }
            if (isBlockedTarget(abs.host)) {
                onError("Blocked LAN-share target ${abs.host} (private/loopback)")
                writeHttpStatus(out, 403, "Forbidden"); return null
            }
            when (val dial = runBlocking { dialer.connect(abs.host, abs.port) }) {
                is DialResult.Fail -> {
                    writeHttpStatus(out, 502, "Bad Gateway"); null
                }
                is DialResult.Ok -> {
                    // Rewrite the proxy request into an origin-form request the target server expects,
                    // dropping hop-by-hop / proxy-only headers, then splice the two streams.
                    val rebuilt = rebuildOriginRequest(req.method, abs, headers)
                    val up = dial.socket.getOutputStream()
                    up.write(rebuilt.toByteArray(Charsets.US_ASCII))
                    up.flush()
                    dial.socket
                }
            }
        }
    }

    // ---- bidirectional pump ---------------------------------------------------------------------

    private fun pump(a: Socket, b: Socket) {
        val t = Thread({ copy(a.getInputStream(), b.getOutputStream(), a, b) }, "djproxy-lanshare-up")
        t.isDaemon = true
        t.start()
        copy(b.getInputStream(), a.getOutputStream(), a, b)
        runCatching { t.join(PUMP_JOIN_MS) }
    }

    private fun copy(src: InputStream, dst: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = src.read(buf)
                if (n == -1) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: IOException) {
            // normal teardown
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    // ---- byte helpers ---------------------------------------------------------------------------

    private fun readFully(ins: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = ins.read(buf, off, n - off)
            if (r == -1) throw EOFException("stream closed after $off/$n")
            off += r
        }
        return buf
    }

    /**
     * Reads the HTTP head up to and including the first CRLFCRLF, prepending [firstByte] which was
     * already consumed by the protocol sniff. Bounded by [HTTP_HEAD_LIMIT].
     */
    private fun readHttpHead(ins: InputStream, firstByte: Int): String? {
        val out = ByteArrayOutputStream()
        out.write(firstByte)
        var m = if (firstByte == 13) 1 else 0
        while (out.size() < HTTP_HEAD_LIMIT) {
            val b = ins.read()
            if (b == -1) return if (out.size() > 1) out.toString("US-ASCII") else null
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

    private fun writeHttpStatus(out: OutputStream, code: Int, reason: String, extra: String = "") {
        val msg = "HTTP/1.1 $code $reason\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n"
        runCatching {
            out.write(msg.toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    private fun closeQuietly(s: Socket?) {
        try {
            s?.close()
        } catch (_: Exception) {
            // best-effort
        }
    }

    companion object {
        const val DEFAULT_PORT = 8787
        private const val BUFFER_SIZE = 32 * 1024
        private const val PUMP_JOIN_MS = 2_000L
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val HTTP_HEAD_LIMIT = 65_536

        private const val SOCKS_VER = 0x05
        private const val AUTH_VER = 0x01
        private const val METHOD_NONE = 0x00
        private const val METHOD_USERPASS = 0x02
        private const val METHOD_NO_ACCEPTABLE = 0xFF
        private const val CMD_CONNECT = 0x01
        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04
        private const val SOCKS_REP_SUCCEEDED = 0x00
        private const val SOCKS_REP_GENERAL = 0x01
        private const val SOCKS_REP_HOST_UNREACHABLE = 0x04
        private const val SOCKS_REP_CMD_UNSUPPORTED = 0x07

        // ---- pure, unit-tested request parsing --------------------------------------------------

        /** Parses `METHOD SP target SP HTTP/x.y`; null if the line is not a well-formed request line. */
        fun parseRequestLine(line: String): HttpRequestLine? {
            val parts = line.trim().split(' ')
            if (parts.size != 3) return null
            if (!parts[2].startsWith("HTTP/")) return null
            if (parts[0].isEmpty() || parts[1].isEmpty()) return null
            return HttpRequestLine(parts[0], parts[1], parts[2])
        }

        /** `host:port` (or bare `host`, default 443) from a CONNECT target. IPv6 in `[..]` supported. */
        fun hostPortFromConnect(target: String): Pair<String, Int>? {
            if (target.startsWith("[")) {
                val close = target.indexOf(']')
                if (close < 0) return null
                val host = target.substring(1, close)
                val rest = target.substring(close + 1)
                val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: return null else 443
                if (host.isEmpty() || port !in 1..65535) return null
                return host to port
            }
            val i = target.lastIndexOf(':')
            return if (i < 0) {
                if (target.isEmpty()) null else target to 443
            } else {
                val host = target.substring(0, i)
                val port = target.substring(i + 1).toIntOrNull() ?: return null
                if (host.isEmpty() || port !in 1..65535) null else host to port
            }
        }

        /** Splits an absolute-form URI (`http://host[:port]/path?q`) into host/port/path. */
        fun hostPortFromAbsoluteUri(uri: String): AbsoluteTarget? {
            val schemeSep = uri.indexOf("://")
            if (schemeSep < 0) return null
            val scheme = uri.substring(0, schemeSep).lowercase()
            val defaultPort = when (scheme) {
                "http" -> 80
                "https" -> 443
                else -> return null
            }
            val afterScheme = uri.substring(schemeSep + 3)
            val slash = afterScheme.indexOf('/')
            val authority = if (slash < 0) afterScheme else afterScheme.substring(0, slash)
            val pathAndQuery = if (slash < 0) "/" else afterScheme.substring(slash)
            // Strip any userinfo@ prefix.
            val at = authority.lastIndexOf('@')
            val hostPort = if (at >= 0) authority.substring(at + 1) else authority
            val (host, port) = if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close < 0) return null
                val h = hostPort.substring(1, close)
                val rest = hostPort.substring(close + 1)
                h to (if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: return null else defaultPort)
            } else {
                val i = hostPort.lastIndexOf(':')
                if (i < 0) hostPort to defaultPort
                else hostPort.substring(0, i) to (hostPort.substring(i + 1).toIntOrNull() ?: return null)
            }
            if (host.isEmpty() || port !in 1..65535) return null
            return AbsoluteTarget(host, port, pathAndQuery)
        }

        /** True if a `Proxy-Authorization: Basic <b64(user:pass)>` header matches [cred]. */
        fun httpAuthOk(headers: List<String>, cred: LanCredential?): Boolean {
            if (cred == null) return true
            val header = headers.firstOrNull { it.substringBefore(':').trim().equals("Proxy-Authorization", true) }
                ?: return false
            val value = header.substringAfter(':').trim()
            if (!value.startsWith("Basic ", ignoreCase = true)) return false
            val decoded = runCatching {
                String(Base64Lite.decode(value.substring(6).trim()), Charsets.UTF_8)
            }.getOrNull() ?: return false
            // Constant-time compare so timing does not leak how many leading credential bytes matched.
            return constantTimeEquals(decoded, "${cred.user}:${cred.pass}")
        }

        /** Timing-independent UTF-8 string equality (java.security.MessageDigest.isEqual). */
        fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

        /**
         * True if [host] is an IP literal in a private / loopback / link-local / unspecified range (or
         * the name "localhost"), which a shared LAN client must not be allowed to reach through the
         * upstream proxy — that would let a stranger on the LAN pivot into the phone's own network.
         * Hostnames are resolved by the upstream exit (not here), so only literals are screened. Pure.
         */
        fun isBlockedTarget(host: String): Boolean {
            val h = host.trim().trim('[', ']')
            if (h.equals("localhost", ignoreCase = true)) return true
            val addr = runCatching {
                if (isIpLiteral(h)) InetAddress.getByName(h) else null
            }.getOrNull() ?: return false
            if (addr.isAnyLocalAddress || addr.isLoopbackAddress ||
                addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isMulticastAddress
            ) {
                return true
            }
            // IPv4 100.64/10 (CGNAT) and IPv6 unique-local fc00::/7 are not covered by isSiteLocal.
            if (addr is Inet4Address) {
                val b = addr.address
                if ((b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xFF) in 64..127) return true
            }
            if (addr is Inet6Address) {
                if ((addr.address[0].toInt() and 0xFE) == 0xFC) return true
            }
            return false
        }

        private fun isIpLiteral(h: String): Boolean {
            if (h.isEmpty()) return false
            if (h.contains(':')) return true // IPv6
            val parts = h.split('.')
            if (parts.size != 4) return false
            return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
        }

        /** Rebuilds an origin-form request, dropping proxy-only / hop-by-hop headers, ensuring Host. */
        fun rebuildOriginRequest(method: String, target: AbsoluteTarget, headers: List<String>): String {
            val sb = StringBuilder()
            sb.append(method).append(' ').append(target.pathAndQuery).append(" HTTP/1.1\r\n")
            var hasHost = false
            for (h in headers) {
                val name = h.substringBefore(':').trim().lowercase()
                if (name == "proxy-authorization" || name == "proxy-connection") continue
                if (name == "host") hasHost = true
                sb.append(h).append("\r\n")
            }
            if (!hasHost) {
                val hostHeader = if (target.port == 80) target.host else "${target.host}:${target.port}"
                sb.append("Host: ").append(hostHeader).append("\r\n")
            }
            sb.append("\r\n")
            return sb.toString()
        }
    }
}

/** Minimal Base64 decoder (RFC 4648) — avoids android.util.Base64 so it runs in JVM unit tests. */
internal object Base64Lite {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val REV = IntArray(128) { -1 }.also { r -> ALPHABET.forEachIndexed { i, c -> r[c.code] = i } }

    fun decode(s: String): ByteArray {
        val clean = s.filter { it != '=' && it != '\n' && it != '\r' }
        val out = ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = if (c.code < 128) REV[c.code] else -1
            require(v >= 0) { "invalid base64 char" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
