package ai.darshj.djproxy.dns

import ai.darshj.djproxy.proxy.DialResult
import ai.darshj.djproxy.proxy.UpstreamDialer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Socket

/**
 * FALLBACK 2 (§3.3): plain DNS-over-TCP (RFC 7766, 2-byte length prefix) to [dnsServer]:53 through
 * the proxy. This is the v2 behaviour, retained because some proxies DO allow :53 and it is the
 * cheapest path when they do. Never throws.
 */
class DnsOverTcpResolver(
    private val dialer: UpstreamDialer,
    private val dnsServer: String,
    private val timeoutMs: Int = DohResolver.DNS_TIMEOUT_MS,
) : DnsResolver {

    override val label: String = "TCP:53"

    override suspend fun resolve(query: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val dial = runCatching { dialer.connect(dnsServer, 53) }.getOrNull()
            ?: return@withContext null
        val socket: Socket = when (dial) {
            is DialResult.Ok -> dial.socket
            is DialResult.Fail -> return@withContext null
        }
        val answer = try {
            socket.soTimeout = timeoutMs
            LengthPrefixedDns.exchange(socket, query)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
        answer?.let { DnsMessage.withId(it, query) }
    }
}
