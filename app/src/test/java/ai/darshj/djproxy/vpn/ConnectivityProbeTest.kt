package ai.darshj.djproxy.vpn

import ai.darshj.djproxy.dns.LoopbackHttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §13 ConnectivityProbeTest — the new CONNECTED criterion. A parsed HTTP status line = Ok (the whole
 * data path works); a refused connection = Fail. Never throws.
 */
class ConnectivityProbeTest {

    @Test
    fun okWhenStatusLineParsedAndPassesThroughExitIp() = runBlocking {
        val resp = "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val server = LoopbackHttpServer(resp)
        server.start()
        server.use {
            val probe = ConnectivityProbe(
                host = "127.0.0.1",
                port = server.port,
                attempts = 1,
                timeoutMs = 2_000,
                knownExitIp = "203.0.113.7",
            )
            val outcome = probe.run()
            assertTrue("expected Ok, got $outcome", outcome is ProbeOutcome.Ok)
            assertEquals("203.0.113.7", (outcome as ProbeOutcome.Ok).exitIp)
        }
    }

    @Test
    fun failWhenConnectionRefused() = runBlocking {
        // Port 1 on loopback has nothing listening → refused fast.
        val probe = ConnectivityProbe(host = "127.0.0.1", port = 1, attempts = 1, timeoutMs = 1_000)
        val outcome = probe.run()
        assertTrue("expected Fail, got $outcome", outcome is ProbeOutcome.Fail)
    }
}
