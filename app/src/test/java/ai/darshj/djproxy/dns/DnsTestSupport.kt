package ai.darshj.djproxy.dns

import ai.darshj.djproxy.proxy.DialResult
import ai.darshj.djproxy.proxy.ProxyError
import ai.darshj.djproxy.proxy.UpstreamDialer
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A one-shot loopback HTTP server for DoH tests. Accepts a single connection, drains the request
 * head, then writes [responseBytes] verbatim and closes. No TLS, no real network egress.
 */
class LoopbackHttpServer(private val responseBytes: ByteArray) : AutoCloseable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    }
    val port: Int get() = server.localPort

    fun start() {
        thread(isDaemon = true, name = "loopback-http") {
            runCatching {
                server.accept().use { sock ->
                    drainHead(sock)
                    sock.getOutputStream().apply { write(responseBytes); flush() }
                }
            }
        }
    }

    private fun drainHead(sock: Socket) {
        sock.soTimeout = 2_000
        val inp = sock.getInputStream()
        var m = 0
        runCatching {
            while (true) {
                val b = inp.read()
                if (b == -1) break
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
        }
    }

    override fun close() { runCatching { server.close() } }

    companion object {
        /** A well-formed HTTP/1.1 200 response carrying [body] as application/dns-message. */
        fun dnsResponse(body: ByteArray): ByteArray {
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/dns-message\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            val out = ByteArrayOutputStream()
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            return out.toByteArray()
        }
    }
}

/** An [UpstreamDialer] that always connects to a fixed loopback [targetPort], ignoring host/port. */
class FakeLoopbackDialer(private val targetPort: Int) : UpstreamDialer {
    override suspend fun connect(host: String, port: Int): DialResult {
        val s = Socket()
        return runCatching {
            s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), targetPort), 2_000)
            DialResult.Ok(s) as DialResult
        }.getOrElse { DialResult.Fail(ProxyError.Io(it.message ?: "connect failed")) }
    }
}

/** A dialer that always fails (to force a composite fallback / resolver failure). */
class FailingDialer : UpstreamDialer {
    override suspend fun connect(host: String, port: Int): DialResult =
        DialResult.Fail(ProxyError.ConnectionRefused(host, port))
}

/** A scripted [DnsResolver] that returns [answer] (or null) and counts invocations. */
class FakeResolver(
    override val label: String,
    private val answer: ByteArray?,
) : DnsResolver {
    var calls = 0; private set
    override suspend fun resolve(query: ByteArray): ByteArray? {
        calls++
        return answer
    }
}
