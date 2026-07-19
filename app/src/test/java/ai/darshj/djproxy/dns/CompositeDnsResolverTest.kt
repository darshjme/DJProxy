package ai.darshj.djproxy.dns

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** §3.6 CompositeDnsResolverTest — ordered fallback, sticky winner, all-fail → null (no throw). */
class CompositeDnsResolverTest {

    private val query = DnsMessage.buildQuery(0x1111, "example.com")

    @Test
    fun fallsThroughToFirstWorkingTransport() = runBlocking {
        val answer = byteArrayOf(7, 7, 7, 7)
        val doh = FakeResolver("DoH:443", null)
        val dot = FakeResolver("DoT:853", null)
        val tcp = FakeResolver("TCP:53", answer)
        val composite = CompositeDnsResolver(listOf(doh, dot, tcp))

        val out = composite.resolve(query)
        assertArrayEquals(answer, out)
        assertEquals(1, doh.calls)
        assertEquals(1, dot.calls)
        assertEquals(1, tcp.calls)
        assertEquals("TCP:53", composite.currentLabel)
    }

    @Test
    fun stickyHeadAvoidsRepayingFailures() = runBlocking {
        val answer = byteArrayOf(1)
        val doh = FakeResolver("DoH:443", null)
        val tcp = FakeResolver("TCP:53", answer)
        val composite = CompositeDnsResolver(listOf(doh, tcp))

        composite.resolve(query)      // DoH fails once, TCP wins and becomes head
        composite.resolve(query)      // should start at TCP (head), not re-pay DoH

        assertEquals("DoH must be tried only on the first pass", 1, doh.calls)
        assertEquals(2, tcp.calls)
        assertEquals("TCP:53", composite.currentLabel)
    }

    @Test
    fun allFailReturnsNullNeverThrows() = runBlocking {
        val composite = CompositeDnsResolver(
            listOf(FakeResolver("DoH:443", null), FakeResolver("DoT:853", null), FakeResolver("TCP:53", null)),
        )
        assertNull(composite.resolve(query))
    }

    @Test
    fun defaultLabelIsPrimaryBeforeAnyResolve() {
        val composite = CompositeDnsResolver(
            listOf(FakeResolver("DoH:443", byteArrayOf(1)), FakeResolver("TCP:53", byteArrayOf(2))),
        )
        assertEquals("DoH:443", composite.currentLabel)
    }
}
