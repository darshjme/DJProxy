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
    private val host: String = PROBE_HOST,
    private val port: Int = PROBE_PORT,
    private val path: String = PROBE_PATH,
    private val timeoutMs: Int = PROBE_TIMEOUT_MS,
    private val attempts: Int = 2,
    private val backoffMs: Long = 1_500,
    /** Exit IP known from pre-flight validation, surfaced to callers/seams (§10 step 9). */
    private val knownExitIp: String? = null,
) {
    suspend fun run(): ProbeOutcome = withContext(Dispatchers.IO) {
        var lastError: ProxyError = ProxyError.Timeout("connectivity probe")
        repeat(attempts) { attempt ->
            if (attempt > 0) delay(backoffMs)
            val started = System.currentTimeMillis()
            val outcome = attemptOnce()
            if (outcome is ProbeOutcome.Ok) {
                return@withContext ProbeOutcome.Ok(System.currentTimeMillis() - started, knownExitIp)
            }
            lastError = (outcome as ProbeOutcome.Fail).error
        }
        ProbeOutcome.Fail(lastError)
    }

    private fun attemptOnce(): ProbeOutcome {
        val socket = Socket()
        return try {
            // Deliberately NOT protected: this must be routed into the tun to exercise the data path.
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val req = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(req.toByteArray(Charsets.US_ASCII)); flush() }
            val statusLine = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                .readLine()
            if (statusLine != null && statusLine.startsWith("HTTP/")) {
                ProbeOutcome.Ok(0, knownExitIp)
            } else {
                ProbeOutcome.Fail(ProxyError.ProbeFailed("no HTTP status line through tun"))
            }
        } catch (_: java.net.SocketTimeoutException) {
            ProbeOutcome.Fail(ProxyError.Timeout("in-tun probe"))
        } catch (e: Exception) {
            ProbeOutcome.Fail(ProxyError.ProbeFailed(e.message ?: e.javaClass.simpleName))
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        // IP literal (not a hostname): the data-path gate must not depend on in-tun DNS. 1.1.1.1:80
        // returns an HTTP status line (a 301 redirect to https) through any working proxy exit.
        const val PROBE_HOST = "1.1.1.1"
        const val PROBE_PORT = 80
        const val PROBE_PATH = "/"
        const val PROBE_TIMEOUT_MS = 8_000
    }
}
