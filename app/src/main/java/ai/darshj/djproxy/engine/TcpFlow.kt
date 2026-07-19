package ai.darshj.djproxy.engine

import ai.darshj.djproxy.net.PacketBuilder
import ai.darshj.djproxy.net.TcpFlag
import ai.darshj.djproxy.net.TcpHeader

/**
 * A single-flow userspace TCP state machine — the Kotlin-side correctness reference for the
 * hybrid transport.
 *
 * In production the vendored hev/lwIP stack terminates TCP in C at line rate. This class implements
 * the same connection semantics in pure, deterministic Kotlin as an exhaustively unit-testable
 * correctness reference. It is NOT currently wired as a runtime fallback: if the native engine fails
 * to load, the tunnel goes Crashed and fails closed (routes held, nothing forwards) — it does not
 * silently fall back to this path. It models the tunnel endpoint that *impersonates the destination
 * server* toward the on-device app (the initiator):
 *
 *   app (client)  --SYN-->            we reply SYN-ACK   (SYN_RECEIVED)
 *                 --ACK-->            (ESTABLISHED)
 *                 <==data, ACKs==>    seq/ack tracked, cumulative ACK, in-order only
 *                 --FIN-->            (CLOSE_WAIT) ; upstream EOF -> we FIN (LAST_ACK) -> ACK -> CLOSED
 *
 * Correctness invariants (never violated):
 *  - Bytes are delivered to the upstream in strict order. An out-of-order segment (seq != rcvNxt) is
 *    dropped and NOT acknowledged as new data, so the peer retransmits — we never reorder a stream.
 *  - Duplicate/overlapping segments already consumed are re-ACKed but not re-delivered.
 *  - We advertise a receive window equal to remaining inbound-buffer space, so a slow upstream applies
 *    real backpressure: the window shrinks (to 0) and the peer stops sending — no unbounded buffering.
 *  - Data we send to the peer is retransmitted on RTO up to [maxRetries]; exceeding it RSTs the flow.
 *  - An idle flow past [idleMs] is reaped with an RST.
 *
 * This class is intentionally free of threads, sockets, and I/O: feed it events, read the packets it
 * wants sent. All time comes from [clock], so tests drive retransmit/reaping deterministically.
 */
class TcpFlow(
    val clientAddr: Int,
    val clientPort: Int,
    val serverAddr: Int,
    val serverPort: Int,
    val mss: Int = 1460,
    val rcvWndMax: Int = 64 * 1024,
    private val iss: Long = 0L,
    private val rtoMs: Long = 500L,
    private val maxRetries: Int = 5,
    private val idleMs: Long = 30_000L,
    private val clock: FlowClock = SystemFlowClock,
) {
    enum class State { LISTEN, SYN_RECEIVED, ESTABLISHED, CLOSE_WAIT, LAST_ACK, FIN_WAIT, CLOSED }

    var state: State = State.LISTEN
        private set

    // --- receive side (client -> us -> upstream) ---
    private var rcvNxt: Long = 0                 // next client seq we expect (valid once SYN seen)
    private val toUpstream = ArrayDeque<Byte>()  // in-order bytes awaiting forward to the proxy
    private var clientFinSeq: Long = -1          // seq occupied by the client's FIN, once known

    // --- send side (upstream -> us -> client) ---
    private var sndUna: Long = iss               // oldest unacknowledged seq we sent
    private var sndNxt: Long = iss               // next seq we will assign
    private var peerWnd: Int = 0                 // client's advertised receive window
    private var finSent: Boolean = false
    private var finSeq: Long = -1

    /** One outstanding segment we sent to the client and may need to retransmit. */
    private class Unacked(
        val seq: Long,
        val payload: ByteArray,
        val flags: Int,
        var sentAtMs: Long,
        var retries: Int,
    )

    private val retxQueue = ArrayDeque<Unacked>()
    private var lastActivityMs: Long = 0

    /** The last receive window we advertised to the peer; drives the proactive window-update ACK. */
    private var lastAdvWnd: Int = rcvWndMax

    /**
     * Space left in the inbound buffer; this is exactly the window we advertise to the peer,
     * clamped to the 16-bit TCP window field (we do not implement window scaling).
     */
    val advertisedWindow: Int
        get() = (rcvWndMax - toUpstream.size).coerceIn(0, 0xFFFF)

    val isClosed: Boolean get() = state == State.CLOSED

    // ---------------------------------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------------------------------

    /**
     * Feed a TCP segment that arrived FROM the client (app). Returns the packets we want written back
     * to the client (SYN-ACK / ACKs / RST), already fully built and checksummed.
     */
    fun onClientSegment(h: TcpHeader): List<ByteArray> {
        if (state == State.CLOSED) return emptyList()
        touch()
        peerWnd = h.window

        if (h.has(TcpFlag.RST)) {
            state = State.CLOSED
            retxQueue.clear()
            return emptyList()
        }

        return when (state) {
            State.LISTEN -> onListen(h)
            State.SYN_RECEIVED -> onSynReceived(h)
            State.ESTABLISHED -> onEstablished(h)
            State.CLOSE_WAIT -> { processAck(h); emptyList() }
            State.LAST_ACK -> {
                processAck(h)
                // RFC1982 serial arithmetic — a bare `>` breaks when the FIN seq is near the 2^32 wrap.
                if (seqGt(sndUna, finSeq)) { state = State.CLOSED; retxQueue.clear() }
                emptyList()
            }
            State.FIN_WAIT -> onFinWait(h)
            State.CLOSED -> emptyList()
        }
    }

    private fun onListen(h: TcpHeader): List<ByteArray> {
        if (!h.has(TcpFlag.SYN)) {
            return listOf(buildRst(seq = 0, ack = seqPlusLen(h)))
        }
        rcvNxt = (h.seq + 1) and MASK32           // SYN occupies one sequence number
        sndUna = iss
        sndNxt = iss
        state = State.SYN_RECEIVED
        // SYN-ACK consumes one seq number.
        val synAck = build(
            seq = sndNxt, ack = rcvNxt,
            flags = TcpFlag.SYN or TcpFlag.ACK,
            payload = EMPTY, withMss = true,
        )
        sndNxt = (sndNxt + 1) and MASK32
        enqueueUnacked(iss, EMPTY, TcpFlag.SYN or TcpFlag.ACK)
        return listOf(synAck)
    }

    private fun onSynReceived(h: TcpHeader): List<ByteArray> {
        if (h.has(TcpFlag.SYN) && !h.has(TcpFlag.ACK)) {
            // Retransmitted SYN — resend our SYN-ACK.
            return listOf(build(seq = iss, ack = rcvNxt, flags = TcpFlag.SYN or TcpFlag.ACK, payload = EMPTY, withMss = true))
        }
        if (h.has(TcpFlag.ACK)) {
            processAck(h)                          // acks our SYN-ACK
            state = State.ESTABLISHED
            return consumeData(h)
        }
        return emptyList()
    }

    private fun onEstablished(h: TcpHeader): List<ByteArray> {
        processAck(h)
        val out = consumeData(h).toMutableList()
        if (h.has(TcpFlag.FIN) && seqInOrder(h)) {
            // FIN only takes effect once all prior data is in order and consumed.
            val dataLen = h.payloadLength
            val finSeqNo = (h.seq + dataLen) and MASK32
            if (finSeqNo == rcvNxt) {
                clientFinSeq = rcvNxt
                rcvNxt = (rcvNxt + 1) and MASK32   // FIN consumes one seq
                state = State.CLOSE_WAIT
                out += build(seq = sndNxt, ack = rcvNxt, flags = TcpFlag.ACK, payload = EMPTY)
            }
        }
        return out
    }

    private fun onFinWait(h: TcpHeader): List<ByteArray> {
        processAck(h)
        val out = consumeData(h).toMutableList()
        if (finSent && seqGt(sndUna, finSeq)) {
            // Our FIN is acked. If the peer also FINs we complete the close.
            if (h.has(TcpFlag.FIN)) {
                rcvNxt = (rcvNxt + 1) and MASK32
                out += build(seq = sndNxt, ack = rcvNxt, flags = TcpFlag.ACK, payload = EMPTY)
            }
            state = State.CLOSED
            retxQueue.clear()
        }
        return out
    }

    /**
     * Deliver payload bytes to the upstream buffer if they are in order and fit the window; produce
     * the resulting ACK. Out-of-order or window-exceeding data is dropped and re-ACKed at rcvNxt so
     * the peer retransmits — never reordered, never silently accepted beyond the advertised window.
     */
    private fun consumeData(h: TcpHeader): List<ByteArray> {
        val len = h.payloadLength
        if (len <= 0) return emptyList()

        if (h.seq == rcvNxt) {
            val accept = len.coerceAtMost(advertisedWindow)
            if (accept > 0) {
                val base = h.payloadOffset
                for (i in 0 until accept) toUpstream.addLast(h.buf[base + i])
                rcvNxt = (rcvNxt + accept) and MASK32
            }
            // ACK exactly what we accepted (cumulative), advertising the shrunk window.
            return listOf(build(seq = sndNxt, ack = rcvNxt, flags = TcpFlag.ACK, payload = EMPTY))
        }

        // Out-of-order or already-seen: re-ACK current rcvNxt, deliver nothing.
        return listOf(build(seq = sndNxt, ack = rcvNxt, flags = TcpFlag.ACK, payload = EMPTY))
    }

    /** Advance sndUna over data/SYN/FIN the client acknowledged and drop those from the retx queue. */
    private fun processAck(h: TcpHeader) {
        if (!h.has(TcpFlag.ACK)) return
        val ack = h.ack
        if (seqGt(ack, sndUna) && !seqGt(ack, sndNxt)) {
            sndUna = ack
            while (retxQueue.isNotEmpty()) {
                val u = retxQueue.first()
                val end = (u.seq + segLen(u)) and MASK32
                if (!seqGt(end, sndUna)) retxQueue.removeFirst() else break
            }
        }
    }

    /**
     * Enqueue application bytes (that arrived FROM the upstream/proxy) for delivery to the client.
     * Returns the data segments to send now, honoring the peer's advertised window and [mss].
     */
    fun onUpstreamData(data: ByteArray): List<ByteArray> {
        if (state != State.ESTABLISHED && state != State.CLOSE_WAIT) return emptyList()
        touch()
        val out = ArrayList<ByteArray>()
        var off = 0
        while (off < data.size) {
            val inFlight = ((sndNxt - sndUna) and MASK32).toInt()
            val room = (peerWnd - inFlight).coerceAtLeast(0)
            if (room <= 0) break                    // peer window full — backpressure toward upstream
            val chunk = minOf(mss, room, data.size - off)
            if (chunk <= 0) break
            val seg = data.copyOfRange(off, off + chunk)
            val pkt = build(seq = sndNxt, ack = rcvNxt, flags = TcpFlag.ACK or TcpFlag.PSH, payload = seg)
            enqueueUnacked(sndNxt, seg, TcpFlag.ACK or TcpFlag.PSH)
            sndNxt = (sndNxt + chunk) and MASK32
            out += pkt
            off += chunk
        }
        return out
    }

    /** The upstream closed (proxy EOF). Send our FIN to the client. */
    fun onUpstreamClosed(): List<ByteArray> {
        if (finSent || state == State.CLOSED) return emptyList()
        touch()
        finSent = true
        finSeq = sndNxt
        val pkt = build(seq = sndNxt, ack = rcvNxt, flags = TcpFlag.ACK or TcpFlag.FIN, payload = EMPTY)
        enqueueUnacked(sndNxt, EMPTY, TcpFlag.ACK or TcpFlag.FIN)
        sndNxt = (sndNxt + 1) and MASK32
        state = if (state == State.CLOSE_WAIT) State.LAST_ACK else State.FIN_WAIT
        return listOf(pkt)
    }

    /**
     * Timer tick: retransmit the oldest unacked segment whose RTO elapsed, and reap idle flows.
     * Returns packets to (re)send; may transition to CLOSED (emitting an RST) on exhausted retries or
     * idle timeout.
     */
    fun onTick(): List<ByteArray> {
        if (state == State.CLOSED) return emptyList()
        val now = clock.nowMs()

        if (now - lastActivityMs >= idleMs) {
            val rst = buildRst(seq = sndNxt, ack = rcvNxt)
            state = State.CLOSED
            retxQueue.clear()
            return listOf(rst)
        }

        // Proactive window-update: if we previously advertised a zero window and the upstream has
        // since drained our buffer, tell the peer now instead of waiting on its zero-window persist
        // timer (which would add multi-second stalls, or a permanent stall against a non-probing peer).
        if (lastAdvWnd == 0 && advertisedWindow > 0 &&
            (state == State.ESTABLISHED || state == State.CLOSE_WAIT)
        ) {
            return listOf(build(seq = sndNxt, ack = rcvNxt, flags = TcpFlag.ACK, payload = EMPTY))
        }

        val head = retxQueue.firstOrNull() ?: return emptyList()
        if (now - head.sentAtMs < rtoMs) return emptyList()

        if (head.retries >= maxRetries) {
            val rst = buildRst(seq = sndNxt, ack = rcvNxt)
            state = State.CLOSED
            retxQueue.clear()
            return listOf(rst)
        }
        head.retries += 1
        head.sentAtMs = now
        return listOf(build(seq = head.seq, ack = rcvNxt, flags = head.flags, payload = head.payload, withMss = head.flags and TcpFlag.SYN != 0))
    }

    /** Drain in-order bytes accumulated from the client, to be written to the upstream proxy. */
    fun drainToUpstream(): ByteArray {
        if (toUpstream.isEmpty()) return EMPTY
        val out = ByteArray(toUpstream.size)
        for (i in out.indices) out[i] = toUpstream.removeFirst()
        return out
    }

    /** True once the client has sent its FIN (no more inbound data will arrive). */
    fun clientClosed(): Boolean = clientFinSeq >= 0 && toUpstream.isEmpty()

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun enqueueUnacked(seq: Long, payload: ByteArray, flags: Int) {
        retxQueue.addLast(Unacked(seq, payload, flags, clock.nowMs(), 0))
    }

    private fun segLen(u: Unacked): Int {
        var l = u.payload.size
        if (u.flags and TcpFlag.SYN != 0) l += 1
        if (u.flags and TcpFlag.FIN != 0) l += 1
        return l
    }

    private fun build(seq: Long, ack: Long, flags: Int, payload: ByteArray, withMss: Boolean = false): ByteArray {
        // Record exactly what we last told the peer about our window (drives the window-update ACK).
        lastAdvWnd = advertisedWindow
        return PacketBuilder.tcp(
            srcAddr = serverAddr, srcPort = serverPort,
            dstAddr = clientAddr, dstPort = clientPort,
            seq = seq, ack = ack, flags = flags,
            window = advertisedWindow,
            payload = if (payload.isEmpty()) null else payload,
            payloadLen = payload.size,
            mss = if (withMss) mss else -1,
        )
    }

    private fun buildRst(seq: Long, ack: Long): ByteArray =
        PacketBuilder.tcp(
            srcAddr = serverAddr, srcPort = serverPort,
            dstAddr = clientAddr, dstPort = clientPort,
            seq = seq, ack = ack, flags = TcpFlag.RST or TcpFlag.ACK,
            window = 0, payload = null,
        )

    private fun seqInOrder(h: TcpHeader): Boolean = h.seq == rcvNxt || h.payloadLength == 0
    private fun seqPlusLen(h: TcpHeader): Long =
        (h.seq + h.payloadLength + (if (h.has(TcpFlag.SYN)) 1 else 0) + (if (h.has(TcpFlag.FIN)) 1 else 0)) and MASK32

    private fun touch() { lastActivityMs = clock.nowMs() }

    companion object {
        private const val MASK32 = 0xFFFFFFFFL
        private val EMPTY = ByteArray(0)

        /** Serial-number greater-than on the 32-bit sequence space (RFC 1982). */
        private fun seqGt(a: Long, b: Long): Boolean {
            val d = ((a - b) and MASK32)
            return d != 0L && d < 0x80000000L
        }
    }
}

/** Monotonic millisecond clock; swapped for a fake in tests to drive timers deterministically. */
interface FlowClock {
    fun nowMs(): Long
}

object SystemFlowClock : FlowClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
