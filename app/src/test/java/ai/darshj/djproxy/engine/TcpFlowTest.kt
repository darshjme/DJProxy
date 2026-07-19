package ai.darshj.djproxy.engine

import ai.darshj.djproxy.net.Ipv4Packet
import ai.darshj.djproxy.net.TcpFlag
import ai.darshj.djproxy.net.TcpHeader
import ai.darshj.djproxy.net.PacketBuilder
import ai.darshj.djproxy.net.stringToIp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive transition tests for the userspace TCP state machine [TcpFlow]: 3-way handshake,
 * in-order delivery, out-of-order refusal, window backpressure, retransmission with RST on
 * exhaustion, idle reaping, and FIN/RST teardown. Time is driven by [FakeClock] so timer behaviour
 * is deterministic.
 */
class TcpFlowTest {

    private val CLIENT = stringToIp("10.0.0.2")
    private val SERVER = stringToIp("93.184.216.34")
    private val CPORT = 44444
    private val SPORT = 80
    private val CISS = 1000L

    private class FakeClock(var t: Long = 0) : FlowClock {
        override fun nowMs(): Long = t
    }

    /** Build a client→server segment and parse it back into a [TcpHeader] the flow consumes. */
    private fun clientSeg(
        seq: Long, ack: Long, flags: Int, window: Int = 65535, payload: ByteArray = ByteArray(0),
    ): TcpHeader {
        val pkt = PacketBuilder.tcp(
            srcAddr = CLIENT, srcPort = CPORT, dstAddr = SERVER, dstPort = SPORT,
            seq = seq, ack = ack, flags = flags, window = window,
            payload = if (payload.isEmpty()) null else payload, payloadLen = payload.size,
        )
        val ip = Ipv4Packet(pkt, pkt.size)
        return TcpHeader(pkt, ip.payloadOffset, ip.payloadLength)
    }

    private fun parse(pkt: ByteArray): TcpHeader {
        val ip = Ipv4Packet(pkt, pkt.size)
        return TcpHeader(pkt, ip.payloadOffset, ip.payloadLength)
    }

    private fun newFlow(clock: FakeClock, rcvWndMax: Int = 65535, rtoMs: Long = 100, maxRetries: Int = 3, idleMs: Long = 100_000) =
        TcpFlow(
            clientAddr = CLIENT, clientPort = CPORT, serverAddr = SERVER, serverPort = SPORT,
            mss = 1460, rcvWndMax = rcvWndMax, iss = 0L,
            rtoMs = rtoMs, maxRetries = maxRetries, idleMs = idleMs, clock = clock,
        )

    /** Drive SYN, ACK; assert ESTABLISHED. Returns the flow at ESTABLISHED with rcvNxt = CISS+1. */
    private fun established(clock: FakeClock, rcvWndMax: Int = 65535): TcpFlow {
        val f = newFlow(clock, rcvWndMax)
        val synOut = f.onClientSegment(clientSeg(seq = CISS, ack = 0, flags = TcpFlag.SYN))
        assertEquals(1, synOut.size)
        val synAck = parse(synOut[0])
        assertTrue(synAck.has(TcpFlag.SYN)); assertTrue(synAck.has(TcpFlag.ACK))
        assertEquals(0L, synAck.seq)
        assertEquals(CISS + 1, synAck.ack)
        assertEquals(TcpFlow.State.SYN_RECEIVED, f.state)

        val ackOut = f.onClientSegment(clientSeg(seq = CISS + 1, ack = 1, flags = TcpFlag.ACK))
        assertTrue(ackOut.isEmpty())
        assertEquals(TcpFlow.State.ESTABLISHED, f.state)
        return f
    }

    @Test fun handshake_reaches_established() {
        established(FakeClock())
    }

    @Test fun in_order_data_is_delivered_and_acked() {
        val clock = FakeClock()
        val f = established(clock)
        val data = "hello".toByteArray()
        val out = f.onClientSegment(clientSeg(seq = CISS + 1, ack = 1, flags = TcpFlag.ACK or TcpFlag.PSH, payload = data))
        assertEquals(1, out.size)
        val ack = parse(out[0])
        assertTrue(ack.has(TcpFlag.ACK))
        assertEquals(CISS + 1 + data.size, ack.ack)          // cumulative ack advanced by 5
        assertArrayEquals(data, f.drainToUpstream())
        assertArrayEquals(ByteArray(0), f.drainToUpstream())  // drained once
    }

    @Test fun out_of_order_segment_is_refused_never_reordered() {
        val clock = FakeClock()
        val f = established(clock)
        // Gap: expected CISS+1, send CISS+100.
        val out = f.onClientSegment(clientSeg(seq = CISS + 100, ack = 1, flags = TcpFlag.ACK or TcpFlag.PSH, payload = "X".toByteArray()))
        val ack = parse(out[0])
        assertEquals(CISS + 1, ack.ack)                       // still expecting the in-order byte
        assertArrayEquals(ByteArray(0), f.drainToUpstream())   // nothing delivered out of order
    }

    @Test fun window_shrinks_under_backpressure() {
        val clock = FakeClock()
        val f = established(clock, rcvWndMax = 4)
        assertEquals(4, f.advertisedWindow)
        // Send 4 bytes but never drain -> buffer full, window 0.
        val out1 = f.onClientSegment(clientSeg(seq = CISS + 1, ack = 1, flags = TcpFlag.ACK or TcpFlag.PSH, payload = "abcd".toByteArray()))
        assertEquals(0, f.advertisedWindow)
        assertEquals(0, parse(out1[0]).window)                // advertise 0 -> peer must stop
        // Peer ignores window and sends more; we accept nothing and re-ack same point.
        val out2 = f.onClientSegment(clientSeg(seq = CISS + 5, ack = 1, flags = TcpFlag.ACK or TcpFlag.PSH, payload = "ef".toByteArray()))
        assertEquals(CISS + 5, parse(out2[0]).ack)            // only the first 4 bytes acked
        // Drain frees the window again.
        assertArrayEquals("abcd".toByteArray(), f.drainToUpstream())
        assertEquals(4, f.advertisedWindow)
    }

    @Test fun upstream_data_is_segmented_and_acked_by_client() {
        val clock = FakeClock()
        val f = established(clock)
        val out = f.onUpstreamData("abc".toByteArray())
        assertEquals(1, out.size)
        val seg = parse(out[0])
        assertTrue(seg.has(TcpFlag.PSH)); assertTrue(seg.has(TcpFlag.ACK))
        assertEquals(1L, seg.seq)                              // iss(0) + 1, SYN-ACK consumed seq 0
        assertEquals(3, seg.payloadLength)
        // Client acks all 3 bytes (cumulative ack = seq + len); retransmit queue must clear.
        val fullAck = seg.seq + seg.payloadLength
        f.onClientSegment(clientSeg(seq = CISS + 1, ack = fullAck, flags = TcpFlag.ACK))
        clock.t += 1_000
        assertTrue(f.onTick().isEmpty())
    }

    @Test fun unacked_data_is_retransmitted_then_rst_on_exhaustion() {
        val clock = FakeClock()
        val f = established(clock)
        val out = f.onUpstreamData("Z".toByteArray())
        val firstSeq = parse(out[0]).seq

        // Before RTO: nothing.
        clock.t += 50
        assertTrue(f.onTick().isEmpty())

        // Each RTO elapse retransmits the same segment (maxRetries = 3).
        repeat(3) {
            clock.t += 100
            val re = f.onTick()
            assertEquals(1, re.size)
            assertEquals(firstSeq, parse(re[0]).seq)
        }
        // Retries exhausted -> RST, CLOSED.
        clock.t += 100
        val rst = f.onTick()
        assertEquals(1, rst.size)
        assertTrue(parse(rst[0]).has(TcpFlag.RST))
        assertTrue(f.isClosed)
    }

    @Test fun idle_flow_is_reaped_with_rst() {
        val clock = FakeClock()
        val f = established(clock, rcvWndMax = 65535)
        clock.t += 100_000                                    // past idleMs
        val out = f.onTick()
        assertEquals(1, out.size)
        assertTrue(parse(out[0]).has(TcpFlag.RST))
        assertTrue(f.isClosed)
    }

    @Test fun graceful_teardown_client_fin_then_upstream_close() {
        val clock = FakeClock()
        val f = established(clock)
        // Client FIN.
        val finOut = f.onClientSegment(clientSeg(seq = CISS + 1, ack = 1, flags = TcpFlag.ACK or TcpFlag.FIN))
        assertEquals(TcpFlow.State.CLOSE_WAIT, f.state)
        assertEquals(CISS + 2, parse(finOut[0]).ack)          // acked the FIN (consumes 1 seq)

        // Upstream closes -> we FIN.
        val ourFin = f.onUpstreamClosed()
        assertEquals(1, ourFin.size)
        assertTrue(parse(ourFin[0]).has(TcpFlag.FIN))
        assertEquals(TcpFlow.State.LAST_ACK, f.state)
        val ourFinSeq = parse(ourFin[0]).seq

        // Client acks our FIN -> CLOSED.
        f.onClientSegment(clientSeg(seq = CISS + 2, ack = ourFinSeq + 1, flags = TcpFlag.ACK))
        assertEquals(TcpFlow.State.CLOSED, f.state)
        assertTrue(f.isClosed)
    }

    @Test fun rst_from_client_closes_immediately() {
        val clock = FakeClock()
        val f = established(clock)
        val out = f.onClientSegment(clientSeg(seq = CISS + 1, ack = 1, flags = TcpFlag.RST))
        assertTrue(out.isEmpty())
        assertTrue(f.isClosed)
        assertFalse(f.onClientSegment(clientSeg(seq = CISS + 1, ack = 1, flags = TcpFlag.ACK)).isNotEmpty())
    }

    @Test fun syn_to_listen_without_syn_flag_is_rejected_with_rst() {
        val clock = FakeClock()
        val f = newFlow(clock)
        val out = f.onClientSegment(clientSeg(seq = CISS, ack = 0, flags = TcpFlag.ACK))
        assertEquals(1, out.size)
        assertTrue(parse(out[0]).has(TcpFlag.RST))
    }

    /**
     * Regression for the seq/ack wraparound bug: with our send sequence landing exactly on the 2^32
     * boundary, the LAST_ACK close comparison must use RFC1982 serial arithmetic. A bare `sndUna >
     * finSeq` would see 0 > 0xFFFFFFFF == false and leak the flow (never CLOSED, retxQueue never cleared).
     */
    @Test fun close_completes_across_seq_wrap_at_2pow32() {
        val clock = FakeClock()
        // iss chosen so our FIN occupies seq 0xFFFFFFFF and the ACK of it wraps sndUna to 0.
        val iss = 0xFFFFFFFEL
        val f = TcpFlow(
            clientAddr = CLIENT, clientPort = CPORT, serverAddr = SERVER, serverPort = SPORT,
            mss = 1460, rcvWndMax = 65535, iss = iss,
            rtoMs = 100, maxRetries = 3, idleMs = 100_000, clock = clock,
        )
        // Handshake.
        val synAck = parse(f.onClientSegment(clientSeg(seq = CISS, ack = 0, flags = TcpFlag.SYN))[0])
        assertEquals(iss, synAck.seq)
        f.onClientSegment(clientSeg(seq = CISS + 1, ack = (iss + 1) and 0xFFFFFFFFL, flags = TcpFlag.ACK))
        assertEquals(TcpFlow.State.ESTABLISHED, f.state)

        // Client FIN -> CLOSE_WAIT.
        f.onClientSegment(clientSeg(seq = CISS + 1, ack = (iss + 1) and 0xFFFFFFFFL, flags = TcpFlag.ACK or TcpFlag.FIN))
        assertEquals(TcpFlow.State.CLOSE_WAIT, f.state)

        // Upstream closes -> our FIN (seq 0xFFFFFFFF) -> LAST_ACK.
        val ourFin = parse(f.onUpstreamClosed()[0])
        assertTrue(ourFin.has(TcpFlag.FIN))
        assertEquals(0xFFFFFFFFL, ourFin.seq)
        assertEquals(TcpFlow.State.LAST_ACK, f.state)

        // Client ACKs our FIN; the ack value wraps to 0. Must reach CLOSED and clear the retx queue.
        val ackOfFin = (ourFin.seq + 1) and 0xFFFFFFFFL // == 0
        assertEquals(0L, ackOfFin)
        f.onClientSegment(clientSeg(seq = CISS + 2, ack = ackOfFin, flags = TcpFlag.ACK))
        assertEquals(TcpFlow.State.CLOSED, f.state)
        assertTrue(f.isClosed)
        // retxQueue cleared: a tick past RTO produces no retransmission.
        clock.t += 1_000
        assertTrue(f.onTick().isEmpty())
    }

    /**
     * A segment whose seq is below rcvNxt but whose tail extends past it (partial overlap of already
     * consumed data) must be re-ACKed at rcvNxt and deliver NOTHING new — never reordered or
     * double-delivered.
     */
    @Test fun overlapping_already_consumed_segment_reacks_without_reordering() {
        val clock = FakeClock()
        val f = established(clock)
        // Deliver 5 in-order bytes.
        f.onClientSegment(clientSeg(seq = CISS + 1, ack = 1, flags = TcpFlag.ACK or TcpFlag.PSH, payload = "abcde".toByteArray()))
        // Overlapping: starts at CISS+3 (already consumed) and runs to CISS+9 (past rcvNxt = CISS+6).
        val out = f.onClientSegment(clientSeg(seq = CISS + 3, ack = 1, flags = TcpFlag.ACK or TcpFlag.PSH, payload = "cdefgh".toByteArray()))
        assertEquals(CISS + 6, parse(out[0]).ack) // still cumulative at the in-order boundary
        // Only the clean in-order bytes were delivered, in order, exactly once.
        assertArrayEquals("abcde".toByteArray(), f.drainToUpstream())
        assertArrayEquals(ByteArray(0), f.drainToUpstream())
    }
}
