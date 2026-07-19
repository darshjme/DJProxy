package ai.darshj.djproxy.vpn

import android.os.ParcelFileDescriptor
import android.system.Os
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.net.Ipv4Packet
import ai.darshj.djproxy.net.PacketBuilder
import ai.darshj.djproxy.net.Proto
import ai.darshj.djproxy.net.UdpHeader
import ai.darshj.djproxy.net.stringToIp
import ai.darshj.djproxy.proxy.DialResult
import ai.darshj.djproxy.proxy.UpstreamDialer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.random.Random

private const val DNS_PORT = 53

/**
 * The DNS handler. There is no device resolver in the data path: the OS is told our in-tun sentinel
 * is the only DNS server (see [TunConfig.DNS_SENTINEL]), so every UDP/53 query lands in the tun.
 * This class takes the raw DNS message and tunnels it — as DNS-over-TCP (RFC 7766, 2-byte length
 * prefix) — through the proxy to [ProxyConfig.dnsServer], then hands back the answer bytes for the
 * router to synthesise a UDP reply. The upstream socket is protected via the injected dialer's
 * single protect() seam, so the query never loops back into the tunnel.
 */
class DnsInterceptor(
    private val dialer: UpstreamDialer,
    private val dnsServer: String,
) {
    private class Entry(val answer: ByteArray, val expiresAtMs: Long)

    /** (qname,qtype,qclass) -> answer, so a page's repeated lookups don't each pay a proxy round-trip. */
    private val cache = ConcurrentHashMap<String, Entry>()

    /** In-flight coalescing: identical concurrent queries share ONE upstream round-trip. */
    private val inFlight = HashMap<String, CompletableDeferred<ByteArray?>>()
    private val inFlightLock = Mutex()

    /** Caps simultaneous upstream DNS connections so a lookup flood cannot exhaust fds/threads. */
    private val gate = Semaphore(MAX_CONCURRENT)

    /** @return the DNS answer message (ID rewritten to match [query]), or null on failure (drop = fail-closed). */
    suspend fun resolve(query: ByteArray): ByteArray? {
        if (query.size < DNS_HEADER_LEN) return null
        val key = questionKey(query) ?: return null
        val now = System.currentTimeMillis()

        cache[key]?.let { entry ->
            if (entry.expiresAtMs > now) return withId(entry.answer, query)
            cache.remove(key, entry)
        }

        // Either join the outstanding lookup for this key, or become its single owner.
        val ownDeferred: CompletableDeferred<ByteArray?>?
        val joined: CompletableDeferred<ByteArray?>?
        inFlightLock.withLock {
            val existing = inFlight[key]
            if (existing != null) {
                ownDeferred = null; joined = existing
            } else {
                val d = CompletableDeferred<ByteArray?>()
                inFlight[key] = d
                ownDeferred = d; joined = null
            }
        }
        if (joined != null) return joined.await()?.let { withId(it, query) }

        val answer = try {
            gate.withPermit { doResolve(query) }
        } catch (e: Exception) {
            LogBus.w(TAG, "DNS query failed: ${e.message}"); null
        }
        if (answer != null) cache[key] = Entry(answer, now + CACHE_TTL_MS)
        inFlightLock.withLock { inFlight.remove(key) }
        ownDeferred!!.complete(answer)
        return answer?.let { withId(it, query) }
    }

    /** One real DNS-over-TCP round-trip through the proxy (RFC 7766, 2-byte length prefix). */
    private suspend fun doResolve(query: ByteArray): ByteArray? {
        val dial = dialer.connect(dnsServer, DNS_PORT)
        val socket: Socket = when (dial) {
            is DialResult.Ok -> dial.socket
            is DialResult.Fail -> {
                LogBus.w(TAG, "DNS upstream dial failed: ${dial.error.message}")
                return null
            }
        }
        return try {
            socket.soTimeout = UPSTREAM_TIMEOUT_MS
            val out = socket.getOutputStream()
            val framed = ByteArray(2 + query.size)
            framed[0] = ((query.size ushr 8) and 0xFF).toByte()
            framed[1] = (query.size and 0xFF).toByte()
            System.arraycopy(query, 0, framed, 2, query.size)
            out.write(framed)
            out.flush()

            val inp = socket.getInputStream()
            val lenBuf = readFully(inp, 2) ?: return null
            val respLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
            if (respLen <= 0 || respLen > 0xFFFF) return null
            readFully(inp, respLen)
        } catch (e: Exception) {
            LogBus.w(TAG, "DNS query failed: ${e.message}")
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Returns a copy of [answer] with its transaction ID overwritten to match [query]'s ID. */
    private fun withId(answer: ByteArray, query: ByteArray): ByteArray {
        if (answer.size < 2 || query.size < 2) return answer
        val out = answer.copyOf()
        out[0] = query[0]; out[1] = query[1]
        return out
    }

    /** Cache/coalesce key = the question section bytes (qname|qtype|qclass), excluding the ID/flags. */
    private fun questionKey(query: ByteArray): String? {
        var p = DNS_HEADER_LEN
        while (p < query.size) {
            val len = query[p].toInt() and 0xFF
            if (len == 0) { p += 1; break }
            if (len and 0xC0 != 0) return null // no compression pointers in a well-formed query name
            p += 1 + len
            if (p > query.size) return null
        }
        val end = (p + 4).coerceAtMost(query.size) // + QTYPE(2) + QCLASS(2)
        if (end <= DNS_HEADER_LEN) return null
        return String(query, DNS_HEADER_LEN, end - DNS_HEADER_LEN, Charsets.ISO_8859_1)
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(b, off, n - off)
            if (r < 0) return null
            off += r
        }
        return b
    }

    companion object {
        private const val TAG = "dns"
        private const val UPSTREAM_TIMEOUT_MS = 8_000
        private const val DNS_HEADER_LEN = 12
        private const val MAX_CONCURRENT = 16
        /** Conservative fixed cap so a burst collapses without serving badly stale records. */
        private const val CACHE_TTL_MS = 60_000L
    }
}

/**
 * The leak-policy packet pump. It is the sole reader of the real tun and bridges it to the engine
 * over an AF_UNIX SOCK_DGRAM socketpair (so both readers never race for the same fd). On the way it
 * enforces the leak model in Kotlin, where it is testable:
 *
 *  - IPv6 (version 6): dropped — blackholed in-tun (leak vector #2).
 *  - IPv4 fragments: dropped — no reassembly / evasion (leak vector #7).
 *  - IPv4 UDP to sentinel:53: tunnelled DNS-over-TCP via [DnsInterceptor], reply synthesised (vector #3).
 *  - IPv4 UDP anything else (incl. :53 to hardcoded resolvers, STUN, QUIC): dropped (vectors #3/#4).
 *  - IPv4 TCP: forwarded to the engine, which terminates it with lwIP and dials the loopback SOCKS front.
 *  - Everything else (ICMP, …): dropped.
 *
 * If the engine side EOFs/errors, [onEngineFault] fires (fail-closed: routes stay up, nothing routes
 * until the watchdog re-plumbs). If the tun side dies, that is a revoke/teardown → [onTunFault].
 */
class TunRouter(
    private val tun: ParcelFileDescriptor,
    private val engineEnd: FileDescriptor,
    private val config: ProxyConfig,
    private val dns: DnsInterceptor,
    private val counters: TunnelCounters,
    private val onTunFault: () -> Unit,
    private val onEngineFault: () -> Unit,
) {
    private val sentinelAddr = stringToIp(TunConfig.DNS_SENTINEL)

    @Volatile private var running = false
    private val tunWriteLock = Any()
    private lateinit var tunIn: FileInputStream
    private lateinit var tunOut: FileOutputStream
    private lateinit var engIn: FileInputStream
    private lateinit var engOut: FileOutputStream
    private var upThread: Thread? = null
    private var downThread: Thread? = null
    private val dnsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        running = true
        tunIn = FileInputStream(tun.fileDescriptor)
        tunOut = FileOutputStream(tun.fileDescriptor)
        engIn = FileInputStream(engineEnd)
        engOut = FileOutputStream(engineEnd)
        upThread = thread(name = "djproxy-tun-up", isDaemon = true) { pumpTunToEngine() }
        downThread = thread(name = "djproxy-tun-down", isDaemon = true) { pumpEngineToTun() }
    }

    fun stop() {
        running = false
        dnsScope.cancel()
        upThread?.interrupt()
        downThread?.interrupt()
        // Only close the socketpair end we own; the tun PFD is owned/closed by the service.
        runCatching { engIn.close() }
        runCatching { engOut.close() }
    }

    /** app -> tun -> (policy) -> engine.  Blocks per-packet on a dedicated thread. */
    private fun pumpTunToEngine() {
        val buf = ByteArray(TunConfig.MTU + 80)
        while (running) {
            val n = try {
                tunIn.read(buf)
            } catch (e: Exception) {
                if (running) { LogBus.e(TAG, "tun read failed: ${e.message}"); onTunFault() }
                return
            }
            if (n <= 0) {
                if (running) { LogBus.w(TAG, "tun closed"); onTunFault() }
                return
            }
            classify(buf, n)
        }
    }

    private fun classify(buf: ByteArray, n: Int) {
        val version = (buf[0].toInt() and 0xF0) ushr 4
        if (version == 6) return // leak vector #2: IPv6 blackholed in-tun.
        if (version != 4) return

        val ip = Ipv4Packet(buf, n)
        if (!ip.isValid()) return
        if (ip.isFragment) return // leak vector #7: refuse fragmented traffic.

        when (ip.protocol) {
            Proto.TCP -> forwardToEngine(buf, n)
            Proto.UDP -> handleUdp(ip, buf, n)
            else -> Unit // ICMP and friends: drop.
        }
    }

    private fun forwardToEngine(buf: ByteArray, n: Int) {
        try {
            engOut.write(buf, 0, n)
            counters.bytesUp.addAndGet(n.toLong())
        } catch (e: Exception) {
            if (running) { LogBus.e(TAG, "engine write failed: ${e.message}"); onEngineFault() }
        }
    }

    private fun handleUdp(ip: Ipv4Packet, buf: ByteArray, n: Int) {
        val udp = UdpHeader(buf, ip.payloadOffset, ip.payloadLength)
        if (!udp.isValid()) return

        val isTunnelledDns = ip.dstAddr == sentinelAddr && udp.dstPort == DNS_PORT
        if (isTunnelledDns) {
            // Copy out of the reusable buffer before handing to the async DNS path.
            val packet = buf.copyOf(n)
            dnsScope.launch { handleDnsPacket(packet) }
            return
        }

        // Everything else UDP (WebRTC/STUN, QUIC, direct :53 to a hardcoded resolver) is dropped.
        counters.udpDropped.incrementAndGet()
    }

    private suspend fun handleDnsPacket(packet: ByteArray) {
        val ip = Ipv4Packet(packet, packet.size)
        if (!ip.isValid()) return
        val udp = UdpHeader(packet, ip.payloadOffset, ip.payloadLength)
        if (!udp.isValid()) return
        val qOff = udp.payloadOffset
        val qLen = udp.payloadLength
        if (qLen <= 0 || qOff + qLen > packet.size) return

        val query = packet.copyOfRange(qOff, qOff + qLen)
        val answer = runCatching { dns.resolve(query) }.getOrNull() ?: return

        // A UDP answer larger than the tun MTU cannot be injected as one non-fragmentable datagram
        // (DF is set), so it would be dropped and the lookup would silently fail. Instead return a
        // TC-bit (truncated) UDP reply; the resolver cleanly upgrades to DNS-over-TCP to the sentinel,
        // which LocalSocksServer now terminates locally with the full answer.
        val payload = if (answer.size + IP_UDP_OVERHEAD > TunConfig.MTU) truncatedDnsReply(query) else answer

        // Synthesise the UDP reply: swap src/dst so it comes back FROM the sentinel TO the app.
        val reply = PacketBuilder.udp(
            srcAddr = ip.dstAddr,
            srcPort = udp.dstPort,
            dstAddr = ip.srcAddr,
            dstPort = udp.srcPort,
            payload = payload,
        )
        writeTun(reply, reply.size)
        counters.dnsQueries.incrementAndGet()
    }

    /**
     * Builds a truncated (TC=1) DNS response by echoing the query's header + question with the
     * response bits set. Well-behaved resolvers react by retrying the query over TCP.
     */
    private fun truncatedDnsReply(query: ByteArray): ByteArray {
        if (query.size < 12) return query
        val r = query.copyOf()
        r[2] = (r[2].toInt() or 0x80 or 0x02).toByte() // QR=1, TC=1
        r[3] = (r[3].toInt() or 0x80).toByte()          // RA=1
        return r
    }

    /** engine -> tun.  Downlink packets from lwIP written back to the apps. */
    private fun pumpEngineToTun() {
        val buf = ByteArray(TunConfig.MTU + 80)
        while (running) {
            val n = try {
                engIn.read(buf)
            } catch (e: Exception) {
                if (running) { LogBus.e(TAG, "engine read failed: ${e.message}"); onEngineFault() }
                return
            }
            if (n <= 0) {
                if (running) { LogBus.w(TAG, "engine closed"); onEngineFault() }
                return
            }
            writeTun(buf, n)
            counters.bytesDown.addAndGet(n.toLong())
        }
    }

    private fun writeTun(pkt: ByteArray, len: Int) {
        synchronized(tunWriteLock) {
            runCatching { tunOut.write(pkt, 0, len) }
        }
    }

    companion object {
        private const val TAG = "router"
        /** IPv4 (20) + UDP (8) header bytes added to a synthesized DNS reply payload. */
        private const val IP_UDP_OVERHEAD = 28
    }
}

/**
 * The on-device leak self-test that must pass before the service declares CONNECTED. Every probe
 * runs from this app's own (un-protected) sockets, so it exercises the real tun path exactly as any
 * other app would. Produces the [LeakCheckReport] gating the transition to CONNECTED.
 */
class LeakSelfTest(private val config: ProxyConfig) {

    suspend fun run(): LeakCheckReport = withContext(Dispatchers.IO) {
        val ipv6 = checkIpv6Unreachable()
        val udp = checkUdpBlocked()
        val dns = checkDnsTunnelled()
        LeakCheckReport(
            ipv6Unreachable = ipv6,
            udpBlocked = udp,
            dnsTunnelled = dns,
            checkedAtMs = System.currentTimeMillis(),
        ).also {
            LogBus.i(TAG, "leak self-test: v6-unreachable=$ipv6 udp-blocked=$udp dns-tunnelled=$dns")
        }
    }

    /** A v6 literal must be unreachable: ::/0 is captured and blackholed, so connect must fail/timeout. */
    private fun checkIpv6Unreachable(): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getByName("2001:4860:4860::8888"), 53), 3_000)
            }
            false // it connected -> IPv6 is leaking.
        } catch (_: Exception) {
            true // any failure (timeout / unreachable) is the pass condition.
        }
    }

    /** A UDP send to a non-sentinel resolver must be dropped (kills WebRTC/QUIC + direct-resolver DNS). */
    private fun checkUdpBlocked(): Boolean {
        return try {
            DatagramSocket().use { ds ->
                ds.soTimeout = 3_000
                val q = buildDnsQuery(Random.nextInt(0xFFFF), "example.com")
                ds.send(DatagramPacket(q, q.size, InetAddress.getByName("8.8.8.8"), DNS_PORT))
                ds.receive(DatagramPacket(ByteArray(512), 512))
                false // got a reply -> UDP is escaping.
            }
        } catch (_: SocketTimeoutException) {
            true // dropped -> pass.
        } catch (_: Exception) {
            true // any other failure is also "not leaking".
        }
    }

    /** A UDP query to the sentinel must come back with a valid answer, proving DNS is tunnelled. */
    private fun checkDnsTunnelled(): Boolean {
        return try {
            DatagramSocket().use { ds ->
                ds.soTimeout = 8_000
                val id = Random.nextInt(0xFFFF)
                val q = buildDnsQuery(id, "one.one.one.one")
                ds.send(DatagramPacket(q, q.size, InetAddress.getByName(TunConfig.DNS_SENTINEL), DNS_PORT))
                val resp = ByteArray(1500)
                val dp = DatagramPacket(resp, resp.size)
                ds.receive(dp)
                isDnsResponseFor(id, resp, dp.length)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isDnsResponseFor(id: Int, resp: ByteArray, len: Int): Boolean {
        if (len < 12) return false
        val respId = ((resp[0].toInt() and 0xFF) shl 8) or (resp[1].toInt() and 0xFF)
        val qr = (resp[2].toInt() and 0x80) != 0 // response bit
        return respId == id && qr
    }

    private fun buildDnsQuery(id: Int, name: String, qtype: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id ushr 8) and 0xFF); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00)            // flags: standard query, recursion desired
        out.write(0x00); out.write(0x01)            // QDCOUNT = 1
        out.write(0x00); out.write(0x00)            // ANCOUNT
        out.write(0x00); out.write(0x00)            // NSCOUNT
        out.write(0x00); out.write(0x00)            // ARCOUNT
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0x00)                             // root label
        out.write((qtype ushr 8) and 0xFF); out.write(qtype and 0xFF)
        out.write(0x00); out.write(0x01)            // QCLASS = IN
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "leaktest"
    }
}

/** Helper to close a raw socketpair fd from the service side without importing Os everywhere. */
internal fun closeQuietly(fd: FileDescriptor?) {
    if (fd == null) return
    runCatching { Os.close(fd) }
}
