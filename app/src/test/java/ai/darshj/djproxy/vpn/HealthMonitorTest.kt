package ai.darshj.djproxy.vpn

import ai.darshj.djproxy.dns.DnsMessage
import ai.darshj.djproxy.dns.DnsResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §13 HealthMonitor / advisory model — proves the advisory path never throws and never gates, and
 * that the DNS interceptor it drives degrades to null (never crashes) on a broken resolver.
 */
class HealthMonitorTest {

    @Test
    fun healthReport_hasNoAllPassGate_andWarnsOnlyOnDegrade() {
        val clean = HealthReport(Health.OK, Health.OK, Health.OK, "DoH:443")
        assertFalse("clean report must not warn", clean.hasWarnings)

        assertTrue(HealthReport(ipv6 = Health.DEGRADED).hasWarnings)
        assertTrue(HealthReport(udp = Health.DEGRADED).hasWarnings)
        assertTrue(HealthReport(dns = Health.DEGRADED).hasWarnings)
        assertTrue(HealthReport(emulatorBypassSuspected = true).hasWarnings)

        // UNKNOWN is not a warning (probe simply couldn't decide) — never a gate.
        assertFalse(HealthReport(Health.UNKNOWN, Health.UNKNOWN, Health.UNKNOWN).hasWarnings)
    }

    @Test
    fun dnsInterceptor_neverThrows_onBrokenResolver() = runBlocking {
        val throwing = object : DnsResolver {
            override val label = "Boom"
            override suspend fun resolve(query: ByteArray): ByteArray? = throw RuntimeException("boom")
        }
        val interceptor = DnsInterceptor(throwing)
        val query = DnsMessage.buildQuery(0x1234, "example.com")
        // A throwing transport must degrade to null, not propagate — the advisory DNS check relies on this.
        assertNull(interceptor.resolve(query))
        assertEquals("Boom", interceptor.activeLabel)
    }

    @Test
    fun dnsInterceptor_rewritesIdOnSuccess() = runBlocking {
        val body = ByteArray(16).also { it[0] = 0; it[1] = 0; it[8] = 0x55 }
        val ok = object : DnsResolver {
            override val label = "DoH:443"
            override suspend fun resolve(query: ByteArray): ByteArray? = body
        }
        val interceptor = DnsInterceptor(ok)
        val query = DnsMessage.buildQuery(0xABCD, "example.com")
        val answer = interceptor.resolve(query)!!
        assertEquals(query[0], answer[0])
        assertEquals(query[1], answer[1])
        assertEquals(0x55.toByte(), answer[8])
    }
}
