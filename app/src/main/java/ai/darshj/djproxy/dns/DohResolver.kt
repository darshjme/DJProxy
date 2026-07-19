package ai.darshj.djproxy.dns

import ai.darshj.djproxy.proxy.DialResult
import ai.darshj.djproxy.proxy.UpstreamDialer
import ai.darshj.djproxy.vpn.LogBus
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A DoH endpoint reached by IP literal (no bootstrap DNS) with TLS SNI + Host set to [host]. */
data class DohEndpoint(val ip: String, val host: String) {
    companion object {
        /** Cloudflare then Google — both RFC 8484 POST /dns-query on :443. */
        val DEFAULTS = listOf(
            DohEndpoint("1.1.1.1", "cloudflare-dns.com"),
            DohEndpoint("1.0.0.1", "cloudflare-dns.com"),
            DohEndpoint("8.8.8.8", "dns.google"),
        )
    }
}

/**
 * PRIMARY DNS transport (§3.1): DNS-over-HTTPS on :443 carried through the proxy CONNECT tunnel, so
 * resolution happens at the proxy EXIT (correct geo, no DNS leak). Residential SOCKS5 exits block
 * :53 but never :443 — that is the whole reason a proxy exists — so this survives where TCP:53 does not.
 *
 * Connects to the endpoint's IP literal (bootstrap-free) and sets TLS SNI + HTTP Host + certificate
 * hostname verification to the DoH virtual host. Never throws: any failure returns null so the
 * [CompositeDnsResolver] can fall back.
 */
class DohResolver(
    private val dialer: UpstreamDialer,
    private val endpoints: List<DohEndpoint> = DohEndpoint.DEFAULTS,
    private val timeoutMs: Int = DNS_TIMEOUT_MS,
    /** TLS wrap seam; overridable in tests with an identity wrap over a plain loopback server. */
    private val tlsWrap: (Socket, String) -> Socket = ::defaultTlsWrap,
) : DnsResolver {

    override val label: String = "DoH:443"

    override suspend fun resolve(query: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        for (ep in endpoints) {
            val answer = resolveVia(ep, query)
            if (answer != null) return@withContext DnsMessage.withId(answer, query)
        }
        null
    }

    /**
     * Suspend so the (already IO-dispatched) [UpstreamDialer.connect] is awaited directly instead of
     * being bridged through runBlocking — that bridge parked a second IO-pool thread per resolution
     * and defeated structured cancellation of the connect.
     */
    private suspend fun resolveVia(ep: DohEndpoint, query: ByteArray): ByteArray? {
        val dial = runCatching { dialer.connect(ep.ip, 443) }.getOrElse {
            LogBus.w(TAG, "DoH connect ${ep.host} failed: ${it.message ?: it.javaClass.simpleName}")
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
            val out = tls.getOutputStream()
            out.write(DohHttp.buildRequest(query, ep.host))
            out.flush()
            DohHttp.parseResponse(tls.getInputStream())
        } catch (t: Throwable) {
            LogBus.w(TAG, "DoH ${ep.host} exchange failed: ${t.message ?: t.javaClass.simpleName}")
            null
        } finally {
            runCatching { (tls ?: raw).close() }
            runCatching { raw.close() }
        }
    }

    companion object {
        const val DNS_TIMEOUT_MS = 8_000
        private const val TAG = "DoH"

        /**
         * True when the SNI / endpoint-identification SSLParameters APIs are available. Those calls
         * (SNIHostName, setServerNames, setEndpointIdentificationAlgorithm) were added in API 24 (N);
         * on API 21-23 they throw NoSuchMethodError, so we must not touch them there. Pure — unit-tested.
         */
        fun shouldUseSniApis(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.N

        /**
         * Wraps [sock] (already tunnelled to [host]:443 through the proxy) in a verified TLS session.
         *
         * On API 24+ we set SNI + endpoint-identification so the platform verifies the certificate
         * against [host] during the handshake. On API 21-23 those APIs do not exist (they would throw
         * NoSuchMethodError and silently break DNS for the whole 21-23 range), so we still create the
         * TLS socket, complete the handshake, then verify the hostname MANUALLY with the platform's
         * default HTTPS hostname verifier — we NEVER skip certificate/hostname verification.
         */
        fun defaultTlsWrap(sock: Socket, host: String): Socket = tlsWrapPort(sock, host, 443)

        internal fun tlsWrapPort(sock: Socket, host: String, port: Int): Socket {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(sock, host, port, true) as SSLSocket
            if (shouldUseSniApis(Build.VERSION.SDK_INT)) {
                ssl.sslParameters = ssl.sslParameters.apply {
                    serverNames = listOf(javax.net.ssl.SNIHostName(host))
                    endpointIdentificationAlgorithm = "HTTPS" // verify cert against the DoH host
                } as SSLParameters
                ssl.startHandshake()
            } else {
                // API 21-23: no SNI/endpoint-id APIs. Handshake, then verify hostname ourselves.
                ssl.startHandshake()
                val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
                if (!verifier.verify(host, ssl.session)) {
                    runCatching { ssl.close() }
                    throw SSLPeerUnverifiedException("TLS hostname mismatch for $host")
                }
            }
            return ssl
        }
    }
}
