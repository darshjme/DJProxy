package ai.darshj.djproxy.vpn

import ai.darshj.djproxy.dns.LoopbackHttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §13 ConnectivityProbeTest — the multi-target CONNECTED criterion. A completed connect (with or
 * without a parsed HTTP status) to ANY target = Ok (the data path works); every target refused = Fail.
 * Never throws.
 */
class ConnectivityProbeTest {

    @Test
    fun okWhenStatusLineParsedAndPassesThroughExitIp() = runBlocking {
        val resp = "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val server = LoopbackHttpServer(resp)
        server.start()
        server.use {
            val probe = ConnectivityProbe(
                targets = listOf("127.0.0.1" to server.port),
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
    fun okWhenFirstTargetRefusedButAnotherReachable() = runBlocking {
        // Simulates a residential exit that filters one probe IP: first target refused, second works.
        val resp = "HTTP/1.1 301 Moved\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val server = LoopbackHttpServer(resp)
        server.start()
        server.use {
            val probe = ConnectivityProbe(
                targets = listOf("127.0.0.1" to 1, "127.0.0.1" to server.port),
                attempts = 1,
                timeoutMs = 2_000,
            )
            assertTrue(probe.run() is ProbeOutcome.Ok)
        }
    }

    @Test
    fun failWhenAllTargetsRefused() = runBlocking {
        // Nothing listening on these loopback ports → every target refused fast → Fail.
        val probe = ConnectivityProbe(
            targets = listOf("127.0.0.1" to 1, "127.0.0.1" to 2),
            attempts = 1,
            timeoutMs = 1_000,
        )
        assertTrue("expected Fail, got …", probe.run() is ProbeOutcome.Fail)
    }
}
