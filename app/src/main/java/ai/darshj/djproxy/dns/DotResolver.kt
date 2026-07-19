package ai.darshj.djproxy.dns

import ai.darshj.djproxy.proxy.DialResult
import ai.darshj.djproxy.proxy.UpstreamDialer
import ai.darshj.djproxy.vpn.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.Socket

/**
 * FALLBACK 1 (§3.3): DNS-over-TLS (RFC 7858) on :853 through the proxy — a length-prefixed DNS
 * message exchange over a TLS session to the resolver. Used when DoH:443 is unavailable but :853
 * survives the exit's egress filter. Never throws.
 */
class DotResolver(
    private val dialer: UpstreamDialer,
    private val endpoints: List<DohEndpoint> = DEFAULTS,
    private val timeoutMs: Int = DohResolver.DNS_TIMEOUT_MS,
    private val tlsWrap: (Socket, String) -> Socket = { s, h -> tlsWrap853(s, h) },
) : DnsResolver {

    override val label: String = "DoT:853"

    override suspend fun resolve(query: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        for (ep in endpoints) {
            val answer = resolveVia(ep, query)
            if (answer != null) return@withContext DnsMessage.withId(answer, query)
        }
        null
    }

    /** Suspend: awaits the IO-dispatched dialer directly instead of parking a thread in runBlocking. */
    private suspend fun resolveVia(ep: DohEndpoint, query: ByteArray): ByteArray? {
        val dial = runCatching { dialer.connect(ep.ip, 853) }.getOrElse {
            LogBus.w(TAG, "DoT connect ${ep.host} failed: ${it.message ?: it.javaClass.simpleName}")
            return null
        }
        val raw: Socket = when (dial) {
            is DialResult.Ok -> dial.socket
            is DialResult.Fail -> return null
        }
        var tls: Socket? = null
        return try {
            raw.soTimeout = timeoutMs
            tls = tlsWrap(raw, ep.host)
            tls.soTimeout = timeoutMs
            LengthPrefixedDns.exchange(tls, query)
        } catch (t: Throwable) {
            LogBus.w(TAG, "DoT ${ep.host} exchange failed: ${t.message ?: t.javaClass.simpleName}")
            null
        } finally {
            runCatching { (tls ?: raw).close() }
            runCatching { raw.close() }
        }
    }

    companion object {
        private const val TAG = "DoT"

        val DEFAULTS = listOf(
            DohEndpoint("1.1.1.1", "cloudflare-dns.com"),
            DohEndpoint("8.8.8.8", "dns.google"),
        )

        // Reuse the DoH lane's SDK-guarded TLS wrap (SNI on API 24+, manual hostname verify on 21-23)
        // so DoT does not re-introduce the API 24-only SNIHostName crash on API 21-23.
        private fun tlsWrap853(sock: Socket, host: String): Socket =
            DohResolver.tlsWrapPort(sock, host, 853)
    }
}

/** RFC 7766/7858 2-byte length-prefixed DNS message exchange over an already-connected stream. */
internal object LengthPrefixedDns {
    fun exchange(socket: Socket, query: ByteArray): ByteArray? {
        val out = socket.getOutputStream()
        out.write(DnsMessage.frame(query))
        out.flush()
        val inp = socket.getInputStream()
        val lenBuf = readFully(inp, 2) ?: return null
        val respLen = DnsMessage.parseLength(lenBuf[0], lenBuf[1])
        if (respLen < 0) return null
        return readFully(inp, respLen)
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(b, off, n - off)
            if (r < 0) return null
            off += r
        }
        return b
    }
}
