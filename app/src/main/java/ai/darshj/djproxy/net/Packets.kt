package ai.darshj.djproxy.net

/**
 * Minimal IPv4/TCP/UDP packet parsing and construction.
 *
 * Everything here works on a caller-supplied ByteArray + offset so the tunnel read loop can
 * operate on one reusable buffer without allocating per packet.
 */
object Proto {
    const val TCP = 6
    const val UDP = 17
    const val ICMP = 1
}

object TcpFlag {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
    const val URG = 0x20
}

/** Reads a big-endian unsigned 16-bit value. */
fun ByteArray.u16(i: Int): Int = ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)

/** Reads a big-endian unsigned 32-bit value into a Long (Kotlin has no u32). */
fun ByteArray.u32(i: Int): Long =
    ((this[i].toLong() and 0xFF) shl 24) or
        ((this[i + 1].toLong() and 0xFF) shl 16) or
        ((this[i + 2].toLong() and 0xFF) shl 8) or
        (this[i + 3].toLong() and 0xFF)

fun ByteArray.put16(i: Int, v: Int) {
    this[i] = ((v ushr 8) and 0xFF).toByte()
    this[i + 1] = (v and 0xFF).toByte()
}

fun ByteArray.put32(i: Int, v: Long) {
    this[i] = ((v ushr 24) and 0xFF).toByte()
    this[i + 1] = ((v ushr 16) and 0xFF).toByte()
    this[i + 2] = ((v ushr 8) and 0xFF).toByte()
    this[i + 3] = (v and 0xFF).toByte()
}

/**
 * One's-complement checksum over [len] bytes starting at [off], seeded with [initial]
 * (used to fold in a pseudo-header).
 */
fun checksum(data: ByteArray, off: Int, len: Int, initial: Long = 0): Int {
    var sum = initial
    var i = off
    val end = off + len - 1
    while (i < end) {
        sum += data.u16(i).toLong()
        i += 2
    }
    if (i == end) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
    while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
    return (sum.inv() and 0xFFFF).toInt()
}

/** Pseudo-header partial sum for TCP/UDP checksums. */
private fun pseudoSum(src: Int, dst: Int, proto: Int, length: Int): Long {
    var sum = 0L
    sum += ((src ushr 16) and 0xFFFF).toLong()
    sum += (src and 0xFFFF).toLong()
    sum += ((dst ushr 16) and 0xFFFF).toLong()
    sum += (dst and 0xFFFF).toLong()
    sum += proto.toLong()
    sum += length.toLong()
    return sum
}

/**
 * A parsed view over an IPv4 packet. Holds no copy of the payload — [buf] stays owned by the
 * reader, so consume the fields before the buffer is reused.
 */
class Ipv4Packet(val buf: ByteArray, val length: Int) {
    val ihl: Int get() = (buf[0].toInt() and 0x0F) * 4
    val version: Int get() = (buf[0].toInt() and 0xF0) ushr 4
    val totalLength: Int get() = buf.u16(2)
    val protocol: Int get() = buf[9].toInt() and 0xFF
    val srcAddr: Int get() = buf.u32(12).toInt()
    val dstAddr: Int get() = buf.u32(16).toInt()

    /** True when this datagram is a non-final or offset fragment; we refuse to reassemble. */
    val isFragment: Boolean
        get() {
            val flagsFrag = buf.u16(6)
            val moreFragments = (flagsFrag and 0x2000) != 0
            val fragmentOffset = flagsFrag and 0x1FFF
            return moreFragments || fragmentOffset != 0
        }

    val payloadOffset: Int get() = ihl
    val payloadLength: Int get() = totalLength - ihl

    fun isValid(): Boolean =
        length >= 20 && version == 4 && ihl in 20..60 && totalLength in ihl..length
}

/** A parsed view over the TCP header sitting at [off] inside [buf]. */
class TcpHeader(val buf: ByteArray, val off: Int, val segmentLength: Int) {
    val srcPort: Int get() = buf.u16(off)
    val dstPort: Int get() = buf.u16(off + 2)
    val seq: Long get() = buf.u32(off + 4)
    val ack: Long get() = buf.u32(off + 8)
    val dataOffset: Int get() = ((buf[off + 12].toInt() and 0xF0) ushr 4) * 4
    val flags: Int get() = buf[off + 13].toInt() and 0x3F
    val window: Int get() = buf.u16(off + 14)

    val payloadOffset: Int get() = off + dataOffset
    val payloadLength: Int get() = segmentLength - dataOffset

    fun has(flag: Int): Boolean = (flags and flag) != 0
    fun isValid(): Boolean = segmentLength >= 20 && dataOffset in 20..60 && dataOffset <= segmentLength
}

/** A parsed view over the UDP header sitting at [off] inside [buf]. */
class UdpHeader(val buf: ByteArray, val off: Int, val datagramLength: Int) {
    val srcPort: Int get() = buf.u16(off)
    val dstPort: Int get() = buf.u16(off + 2)
    val length: Int get() = buf.u16(off + 4)

    val payloadOffset: Int get() = off + 8
    val payloadLength: Int get() = (length - 8).coerceAtMost(datagramLength - 8)

    fun isValid(): Boolean = datagramLength >= 8 && length >= 8 && length <= datagramLength
}

object PacketBuilder {

    /**
     * Builds a complete IPv4 + TCP packet.
     *
     * @param payload data to place after the TCP header; [payloadLen] bytes from [payloadOff].
     * @return a freshly allocated, fully checksummed packet ready to write to the tun device.
     */
    fun tcp(
        srcAddr: Int,
        srcPort: Int,
        dstAddr: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray? = null,
        payloadOff: Int = 0,
        payloadLen: Int = 0,
        mss: Int = -1,
    ): ByteArray {
        // A single 4-byte-aligned MSS option is the only option we ever emit (SYN-ACK).
        val optionsLen = if (mss > 0) 4 else 0
        val tcpHeaderLen = 20 + optionsLen
        val total = 20 + tcpHeaderLen + payloadLen
        val p = ByteArray(total)

        // ---- IPv4 header ----
        p[0] = 0x45                       // version 4, IHL 5
        p[1] = 0                          // DSCP/ECN
        p.put16(2, total)
        p.put16(4, 0)                     // identification (0 is fine, we never fragment)
        p.put16(6, 0x4000)                // don't fragment
        p[8] = 64                         // TTL
        p[9] = Proto.TCP.toByte()
        p.put16(10, 0)                    // checksum placeholder
        p.put32(12, srcAddr.toLong() and 0xFFFFFFFFL)
        p.put32(16, dstAddr.toLong() and 0xFFFFFFFFL)
        p.put16(10, checksum(p, 0, 20))

        // ---- TCP header ----
        val t = 20
        p.put16(t, srcPort)
        p.put16(t + 2, dstPort)
        p.put32(t + 4, seq and 0xFFFFFFFFL)
        p.put32(t + 8, ack and 0xFFFFFFFFL)
        p[t + 12] = (((tcpHeaderLen / 4) shl 4) and 0xF0).toByte()
        p[t + 13] = flags.toByte()
        p.put16(t + 14, window)
        p.put16(t + 16, 0)                // checksum placeholder
        p.put16(t + 18, 0)                // urgent pointer

        if (mss > 0) {
            p[t + 20] = 2                 // kind = MSS
            p[t + 21] = 4                 // length
            p.put16(t + 22, mss)
        }

        if (payload != null && payloadLen > 0) {
            System.arraycopy(payload, payloadOff, p, t + tcpHeaderLen, payloadLen)
        }

        val sum = pseudoSum(srcAddr, dstAddr, Proto.TCP, tcpHeaderLen + payloadLen)
        p.put16(t + 16, checksum(p, t, tcpHeaderLen + payloadLen, sum))
        return p
    }

    /** Builds a complete IPv4 + UDP packet. */
    fun udp(
        srcAddr: Int,
        srcPort: Int,
        dstAddr: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadOff: Int = 0,
        payloadLen: Int = payload.size,
    ): ByteArray {
        val udpLen = 8 + payloadLen
        val total = 20 + udpLen
        val p = ByteArray(total)

        p[0] = 0x45
        p.put16(2, total)
        p.put16(6, 0x4000)
        p[8] = 64
        p[9] = Proto.UDP.toByte()
        p.put32(12, srcAddr.toLong() and 0xFFFFFFFFL)
        p.put32(16, dstAddr.toLong() and 0xFFFFFFFFL)
        p.put16(10, checksum(p, 0, 20))

        val u = 20
        p.put16(u, srcPort)
        p.put16(u + 2, dstPort)
        p.put16(u + 4, udpLen)
        p.put16(u + 6, 0)
        System.arraycopy(payload, payloadOff, p, u + 8, payloadLen)

        val sum = pseudoSum(srcAddr, dstAddr, Proto.UDP, udpLen)
        var ck = checksum(p, u, udpLen, sum)
        if (ck == 0) ck = 0xFFFF          // RFC 768: 0 means "no checksum", so transmit as all-ones
        p.put16(u + 6, ck)
        return p
    }
}

fun ipToString(addr: Int): String =
    "${(addr ushr 24) and 0xFF}.${(addr ushr 16) and 0xFF}.${(addr ushr 8) and 0xFF}.${addr and 0xFF}"

fun stringToIp(s: String): Int {
    val parts = s.split(".")
    require(parts.size == 4) { "not an IPv4 literal: $s" }
    var v = 0
    for (p in parts) v = (v shl 8) or (p.toInt() and 0xFF)
    return v
}
