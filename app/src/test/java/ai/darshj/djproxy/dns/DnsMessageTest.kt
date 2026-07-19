package ai.darshj.djproxy.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §3.6 DnsMessageTest — question-key parse, ID rewrite, length framing (moved out of LeakPolicy). */
class DnsMessageTest {

    @Test
    fun questionKey_isStableAcrossIdAndFlags() {
        val a = DnsMessage.buildQuery(0x1234, "example.com")
        val b = DnsMessage.buildQuery(0x9abc, "example.com")
        val ka = DnsMessage.questionKey(a)
        val kb = DnsMessage.questionKey(b)
        assertEquals("same name+type must share a cache key regardless of ID", ka, kb)
    }

    @Test
    fun questionKey_differsByName() {
        val a = DnsMessage.questionKey(DnsMessage.buildQuery(1, "a.com"))
        val b = DnsMessage.questionKey(DnsMessage.buildQuery(1, "b.com"))
        assertFalse(a == b)
    }

    @Test
    fun questionKey_rejectsTruncated() {
        assertNull(DnsMessage.questionKey(ByteArray(5)))
    }

    @Test
    fun withId_rewritesOnlyTransactionId() {
        val query = DnsMessage.buildQuery(0x4242, "one.one.one.one")
        val answer = DnsMessage.buildQuery(0x0000, "one.one.one.one")
        val fixed = DnsMessage.withId(answer, query)
        assertEquals(query[0], fixed[0])
        assertEquals(query[1], fixed[1])
        // Body after the ID is untouched.
        assertArrayEquals(answer.copyOfRange(2, answer.size), fixed.copyOfRange(2, fixed.size))
    }

    @Test
    fun frame_prefixesBigEndianLength() {
        val msg = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val framed = DnsMessage.frame(msg)
        assertEquals(5, framed.size)
        assertEquals(0, framed[0].toInt())
        assertEquals(3, framed[1].toInt())
        assertEquals(3, DnsMessage.parseLength(framed[0], framed[1]))
    }

    @Test
    fun parseLength_rejectsZero() {
        assertEquals(-1, DnsMessage.parseLength(0, 0))
    }

    @Test
    fun buildQuery_andIsResponseFor_roundTrip() {
        val q = DnsMessage.buildQuery(0x0f0f, "cloudflare.com")
        // Fabricate a response: set QR bit, keep the ID.
        val resp = q.copyOf()
        resp[2] = (resp[2].toInt() or 0x80).toByte()
        assertTrue(DnsMessage.isResponseFor(0x0f0f, resp, resp.size))
        assertFalse(DnsMessage.isResponseFor(0x0f0f, q, q.size)) // QR not set on the query
        assertFalse(DnsMessage.isResponseFor(0x0001, resp, resp.size)) // wrong id
    }

    @Test
    fun hasAnswers_readsAnCount() {
        val q = DnsMessage.buildQuery(1, "x.com")
        assertFalse(DnsMessage.hasAnswers(q, q.size)) // ANCOUNT = 0
        val withAns = q.copyOf()
        withAns[6] = 0; withAns[7] = 2 // ANCOUNT = 2
        assertTrue(DnsMessage.hasAnswers(withAns, withAns.size))
    }
}
