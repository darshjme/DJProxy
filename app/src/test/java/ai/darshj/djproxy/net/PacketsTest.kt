package ai.darshj.djproxy.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the packet primitives the transport depends on: one's-complement checksums, IPv4/TCP/UDP
 * build↔parse round-trips, and fragment refusal (leak vector #7 in DESIGN.md §3).
 */
class PacketsTest {

    // ---- integer accessors ----------------------------------------------------------------------

    @Test fun u16_u32_roundtrip() {
        val b = ByteArray(8)
        b.put16(0, 0xBEEF)
        b.put32(2, 0xDEADC0DEL)
        assertEquals(0xBEEF, b.u16(0))
        assertEquals(0xDEADC0DEL, b.u32(2))
    }

    // ---- checksum -------------------------------------------------------------------------------

    /**
     * Classic RFC 1071 worked example: the 16-bit words 0x0001,0xf203,0xf4f5,0xf6f7 checksum to
     * 0x220d (the transmitted complement), and re-summing including that value yields 0.
     */
    @Test fun checksum_rfc1071_example() {
        val data = byteArrayOf(
            0x00, 0x01, 0xf2.toByte(), 0x03,
            0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(),
        )
        assertEquals(0x220d, checksum(data, 0, data.size))
    }

    @Test fun checksum_of_valid_header_is_zero() {
        val pkt = PacketBuilder.tcp(
            srcAddr = stringToIp("198.18.0.1"), srcPort = 12345,
            dstAddr = stringToIp("93.184.216.34"), dstPort = 80,
            seq = 100, ack = 0, flags = TcpFlag.SYN, window = 65535,
        )
        // Re-checksumming an already-checksummed IPv4 header must fold to 0.
        assertEquals(0, checksum(pkt, 0, 20))
    }

    @Test fun checksum_handles_odd_length() {
        val data = byteArrayOf(0x12, 0x34, 0x56)
        // No exception, and result is a valid 16-bit value.
        val c = checksum(data, 0, data.size)
        assertTrue(c in 0..0xFFFF)
    }

    // ---- TCP build/parse round-trip -------------------------------------------------------------

    @Test fun tcp_build_parse_roundtrip_with_payload() {
        val src = stringToIp("10.0.0.2"); val dst = stringToIp("1.1.1.1")
        val payload = "GET / HTTP/1.1\r\n\r\n".toByteArray()
        val pkt = PacketBuilder.tcp(
            srcAddr = src, srcPort = 40000,
            dstAddr = dst, dstPort = 443,
            seq = 0x11223344L, ack = 0x55667788L,
            flags = TcpFlag.ACK or TcpFlag.PSH, window = 8192,
            payload = payload, payloadLen = payload.size,
        )

        val ip = Ipv4Packet(pkt, pkt.size)
        assertTrue(ip.isValid())
        assertEquals(4, ip.version)
        assertEquals(Proto.TCP, ip.protocol)
        assertEquals(src, ip.srcAddr)
        assertEquals(dst, ip.dstAddr)
        assertFalse(ip.isFragment)

        val tcp = TcpHeader(pkt, ip.payloadOffset, ip.payloadLength)
        assertTrue(tcp.isValid())
        assertEquals(40000, tcp.srcPort)
        assertEquals(443, tcp.dstPort)
        assertEquals(0x11223344L, tcp.seq)
        assertEquals(0x55667788L, tcp.ack)
        assertTrue(tcp.has(TcpFlag.ACK))
        assertTrue(tcp.has(TcpFlag.PSH))
        assertFalse(tcp.has(TcpFlag.SYN))
        assertEquals(8192, tcp.window)
        assertEquals(payload.size, tcp.payloadLength)

        val got = pkt.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength)
        assertEquals(String(payload), String(got))

        // Full TCP checksum (with pseudo-header) must verify to zero.
        val pseudo = tcpPseudoSum(src, dst, ip.payloadLength)
        assertEquals(0, checksum(pkt, ip.payloadOffset, ip.payloadLength, pseudo))
    }

    @Test fun tcp_synack_carries_mss_option() {
        val pkt = PacketBuilder.tcp(
            srcAddr = stringToIp("198.18.0.1"), srcPort = 80,
            dstAddr = stringToIp("10.0.0.2"), dstPort = 33333,
            seq = 0, ack = 1, flags = TcpFlag.SYN or TcpFlag.ACK, window = 65535,
            mss = 1460,
        )
        val ip = Ipv4Packet(pkt, pkt.size)
        val tcp = TcpHeader(pkt, ip.payloadOffset, ip.payloadLength)
        assertEquals(24, tcp.dataOffset)            // 20 + 4-byte MSS option
        // Option bytes: kind=2, len=4, value=1460.
        val optOff = ip.payloadOffset + 20
        assertEquals(2, pkt[optOff].toInt())
        assertEquals(4, pkt[optOff + 1].toInt())
        assertEquals(1460, pkt.u16(optOff + 2))
    }

    // ---- UDP build/parse round-trip -------------------------------------------------------------

    @Test fun udp_build_parse_roundtrip() {
        val src = stringToIp("10.0.0.2"); val dst = stringToIp("8.8.8.8")
        val payload = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val pkt = PacketBuilder.udp(src, 5353, dst, 53, payload)

        val ip = Ipv4Packet(pkt, pkt.size)
        assertEquals(Proto.UDP, ip.protocol)
        val udp = UdpHeader(pkt, ip.payloadOffset, ip.payloadLength)
        assertTrue(udp.isValid())
        assertEquals(5353, udp.srcPort)
        assertEquals(53, udp.dstPort)
        assertEquals(payload.size, udp.payloadLength)
        val got = pkt.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        assertTrue(payload.contentEquals(got))
    }

    // ---- fragment refusal (leak vector #7) ------------------------------------------------------

    @Test fun fragment_flags_detected() {
        val pkt = PacketBuilder.tcp(
            srcAddr = stringToIp("10.0.0.2"), srcPort = 1,
            dstAddr = stringToIp("1.1.1.1"), dstPort = 2,
            seq = 0, ack = 0, flags = TcpFlag.ACK, window = 0,
        )
        // Not a fragment as built.
        assertFalse(Ipv4Packet(pkt, pkt.size).isFragment)

        // Set MF (more-fragments) bit.
        pkt.put16(6, 0x2000)
        assertTrue(Ipv4Packet(pkt, pkt.size).isFragment)

        // Non-zero fragment offset also counts.
        pkt.put16(6, 0x0001)
        assertTrue(Ipv4Packet(pkt, pkt.size).isFragment)
    }

    // ---- address helpers ------------------------------------------------------------------------

    @Test fun ip_string_roundtrip() {
        val a = stringToIp("203.0.113.9")
        assertEquals("203.0.113.9", ipToString(a))
        assertEquals(stringToIp("255.255.255.255"), -1)   // all-ones -> Int -1
        assertEquals("0.0.0.0", ipToString(0))
    }

    // ---- RFC 768 zero-checksum branch -----------------------------------------------------------

    /**
     * When a UDP datagram's computed one's-complement checksum folds to 0x0000, RFC 768 requires it
     * be transmitted as 0xFFFF (0 means "no checksum"). Sweeping a 2-byte payload guarantees exactly
     * one value where the raw checksum is 0; assert the wire value is 0xFFFF, never 0x0000.
     */
    @Test fun udp_zero_checksum_is_transmitted_as_all_ones() {
        val src = stringToIp("198.18.0.2"); val dst = stringToIp("10.0.0.2")
        var foundAllOnes = false
        for (v in 0..0xFFFF) {
            val payload = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
            val pkt = PacketBuilder.udp(src, 53, dst, 40000, payload)
            val cksum = pkt.u16(20 + 6) // UDP checksum field
            assertTrue("checksum field must never be transmitted as 0x0000", cksum != 0x0000)
            if (cksum == 0xFFFF) foundAllOnes = true
        }
        assertTrue("expected at least one payload whose raw checksum folds to zero -> 0xFFFF", foundAllOnes)
    }

    // ---- synthesized DNS reply (the tunnelled-DNS return path) -----------------------------------

    /**
     * The DNS return path synthesizes a UDP datagram FROM the sentinel back to the app. Round-trip it
     * through IPv4/UDP parsing and verify the full UDP checksum (with pseudo-header) folds to zero.
     */
    @Test fun synthesized_dns_reply_parses_and_checksums() {
        val sentinel = stringToIp("198.18.0.2")
        val app = stringToIp("198.18.0.1")
        val answer = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte()) + ByteArray(60) { it.toByte() }
        val pkt = PacketBuilder.udp(
            srcAddr = sentinel, srcPort = 53,
            dstAddr = app, dstPort = 51000,
            payload = answer,
        )

        val ip = Ipv4Packet(pkt, pkt.size)
        assertTrue(ip.isValid())
        assertEquals(Proto.UDP, ip.protocol)
        assertEquals(sentinel, ip.srcAddr)
        assertEquals(app, ip.dstAddr)

        val udp = UdpHeader(pkt, ip.payloadOffset, ip.payloadLength)
        assertTrue(udp.isValid())
        assertEquals(53, udp.srcPort)
        assertEquals(51000, udp.dstPort)
        assertEquals(answer.size, udp.payloadLength)
        val got = pkt.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        assertTrue(answer.contentEquals(got))

        // Full UDP checksum (pseudo-header + header + payload) verifies to zero.
        val pseudo = udpPseudoSum(sentinel, app, ip.payloadLength)
        assertEquals(0, checksum(pkt, ip.payloadOffset, ip.payloadLength, pseudo))
    }

    private fun tcpPseudoSum(src: Int, dst: Int, tcpLen: Int): Long {
        var sum = 0L
        sum += ((src ushr 16) and 0xFFFF).toLong(); sum += (src and 0xFFFF).toLong()
        sum += ((dst ushr 16) and 0xFFFF).toLong(); sum += (dst and 0xFFFF).toLong()
        sum += Proto.TCP.toLong()
        sum += tcpLen.toLong()
        return sum
    }

    private fun udpPseudoSum(src: Int, dst: Int, udpLen: Int): Long {
        var sum = 0L
        sum += ((src ushr 16) and 0xFFFF).toLong(); sum += (src and 0xFFFF).toLong()
        sum += ((dst ushr 16) and 0xFFFF).toLong(); sum += (dst and 0xFFFF).toLong()
        sum += Proto.UDP.toLong()
        sum += udpLen.toLong()
        return sum
    }
}
