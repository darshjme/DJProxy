package ai.darshj.djproxy.vpn

import ai.darshj.djproxy.proxy.ProxyError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/** Outcome of the in-tun connectivity probe (§2.1). Never thrown — always returned. */
sealed interface ProbeOutcome {
    /** The whole data path (tun→router→engine→loopback→dialer→proxy) works end-to-end. */
    data class Ok(val latencyMs: Long, val exitIp: String?) : ProbeOutcome
    data class Fail(val error: ProxyError) : ProbeOutcome
}

/**
 * The NEW CONNECTED criterion (§2.1): a real TCP round-trip THROUGH the tun. It opens an
 * *un-protected* [Socket] to [host]:[port] (so the OS routes it into the tun → engine → upstream
 * proxy), writes a minimal HTTP GET, and reads a status line. Any parsed HTTP status = success —
 * that proves the whole data path, which is the honest definition of "connected". Leak posture is
 * NOT part of this decision.
 *
 * IMPORTANT (requirement #1 — DNS is strictly advisory): the default probe target is an IP LITERAL
 * (1.1.1.1:80), NOT a hostname. This decouples the CONNECTED gate from in-tun name resolution, so a
 * DNS outage on a residential exit surfaces as a post-connect DNS advisory chip rather than a hard
 * bring-up failure. (The upstream proxy dials the literal directly; no DNS is needed to connect.)
 *
 * This never throws to the caller; a failure is a *data-path* failure (bring-up fails closed), not a
 * *leak* failure. The former LeakException hard-gate is gone.
 */
class ConnectivityProbe(
    /** Diverse IP-literal targets; the data path is proven if the exit reaches ANY of them. */
    private val targets: List<Pair<String, Int>> = DEFAULT_TARGETS,
    private val timeoutMs: Int = PROBE_TIMEOUT_MS,
    private val attempts: Int = 2,
    private val backoffMs: Long = 1_500,
    /** Exit IP known from pre-flight validation, surfaced to callers/seams (§10 step 9). */
    private val knownExitIp: String? = null,
) {
    /**
     * Runs the probe against every target until one proves the path. A target is a WIN either when it
     * returns an HTTP status line OR when the through-tun TCP connect itself completes — a completed
     * connect means the SOCKS CONNECT through the proxy succeeded end-to-end, which is the real
     * data-path signal; whether that one host then speaks HTTP is irrelevant. Only if EVERY target
     * fails to even connect do we report failure. This is what makes a working residential exit that
     * happens to filter one anycast IP (e.g. 1.1.1.1) still bring the tunnel up — matching Postern,
     * which has no such gate at all.
     */
    suspend fun run(): ProbeOutcome = withContext(Dispatchers.IO) {
        var lastError: ProxyError = ProxyError.Timeout("connectivity probe")
        repeat(attempts) { attempt ->
            if (attempt > 0) delay(backoffMs)
            val started = System.currentTimeMillis()
            for ((host, port) in targets) {
                when (val r = attemptOnce(host, port)) {
                    is ProbeOutcome.Ok ->
                        return@withContext ProbeOutcome.Ok(System.currentTimeMillis() - started, knownExitIp)
                    is ProbeOutcome.Fail -> lastError = r.error
                }
            }
        }
        ProbeOutcome.Fail(lastError)
    }

    private fun attemptOnce(host: String, port: Int): ProbeOutcome {
        val socket = Socket()
        return try {
            // Deliberately NOT protected: this must be routed into the tun to exercise the data path.
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            // The connect completed THROUGH the tunnel — the whole path works. Try to read an HTTP
            // status as a bonus positive signal, but a completed connect already proves the path, so
            // a reset/timeout on the READ (a flaky/filtered target host) is still a WIN.
            socket.soTimeout = timeoutMs
            val req = "GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
            runCatching {
                socket.getOutputStream().apply { write(req.toByteArray(Charsets.US_ASCII)); flush() }
                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine()
            }
            ProbeOutcome.Ok(0, knownExitIp)
        } catch (_: java.net.SocketTimeoutException) {
            ProbeOutcome.Fail(ProxyError.Timeout("in-tun probe ($host:$port)"))
        } catch (e: Exception) {
            ProbeOutcome.Fail(ProxyError.ProbeFailed("$host:$port ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        const val PROBE_PORT = 80
        const val PROBE_TIMEOUT_MS = 8_000
        // IP literals (never hostnames — the gate must not depend on in-tun DNS). Four diverse,
        // well-known anycast hosts on :80 so a proxy exit that filters or can't reach any single one
        // still passes on another. A residential exit unable to reach ALL of these is genuinely dead.
        val DEFAULT_TARGETS = listOf(
            "1.1.1.1" to 80,        // Cloudflare
            "8.8.8.8" to 80,        // Google
            "9.9.9.9" to 80,        // Quad9
            "208.67.222.222" to 80, // OpenDNS
        )
    }
}
