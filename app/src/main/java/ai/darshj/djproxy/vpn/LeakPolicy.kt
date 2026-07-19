package ai.darshj.djproxy.vpn

import android.os.ParcelFileDescriptor
import android.system.Os
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.dns.DnsMessage
import ai.darshj.djproxy.dns.DnsResolver
import ai.darshj.djproxy.net.Ipv4Packet
import ai.darshj.djproxy.net.PacketBuilder
import ai.darshj.djproxy.net.Proto
import ai.darshj.djproxy.net.UdpHeader
import ai.darshj.djproxy.net.stringToIp
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
import java.util.concurrent.ConcurrentHashMap
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

private const val DNS_PORT = 53

/**
 * The DNS handler. There is no device resolver in the data path: the OS is told our in-tun sentinel
 * is the only DNS server (see [TunConfig.DNS_SENTINEL]), so every UDP/53 query lands in the tun.
 *
 * v3: this class no longer speaks TCP itself. It owns the caching / in-flight coalescing / ID-rewrite
 * and delegates the actual transport to a pluggable [DnsResolver] (default = the composite
 * DoH:443 ▸ DoT:853 ▸ TCP:53 resolver, all resolving at the proxy EXIT so there is no DNS geo-leak).
 * The resolver is obtained from [FeatureRegistry.dnsResolverFactory]; tests inject a fake.
 */
class DnsInterceptor(
    private val resolver: DnsResolver,
) {
    private class Entry(val answer: ByteArray, val expiresAtMs: Long)

    /** (qname,qtype,qclass) -> answer, so a page's repeated lookups don't each pay a proxy round-trip. */
    private val cache = ConcurrentHashMap<String, Entry>()

    /** In-flight coalescing: identical concurrent queries share ONE upstream round-trip. */
    private val inFlight = HashMap<String, CompletableDeferred<ByteArray?>>()
    private val inFlightLock = Mutex()

    /** Caps simultaneous upstream DNS connections so a lookup flood cannot exhaust fds/threads. */
    private val gate = Semaphore(MAX_CONCURRENT)

    /** The transport currently serving, for [HealthReport.activeDnsStrategy]. */
    val activeLabel: String get() = resolver.label

    /** @return the DNS answer message (ID rewritten to match [query]), or null on failure (drop = fail-closed). */
    suspend fun resolve(query: ByteArray): ByteArray? {
        if (query.size < DnsMessage.HEADER_LEN) return null
        val key = DnsMessage.questionKey(query) ?: return null
        val now = System.currentTimeMillis()

        cache[key]?.let { entry ->
            if (entry.expiresAtMs > now) return DnsMessage.withId(entry.answer, query)
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
        if (joined != null) return joined.await()?.let { DnsMessage.withId(it, query) }

        val answer = try {
            gate.withPermit { resolver.resolve(query) }
        } catch (e: Exception) {
            LogBus.w(TAG, "DNS query failed: ${e.message}"); null
        }
        if (answer != null) cache[key] = Entry(answer, now + CACHE_TTL_MS)
        inFlightLock.withLock { inFlight.remove(key) }
        ownDeferred!!.complete(answer)
        return answer?.let { DnsMessage.withId(it, query) }
    }

    companion object {
        private const val TAG = "dns"
        private const val MAX_CONCURRENT = 16
        /** Conservative fixed cap so a burst collapses without serving badly stale records. */
        private const val CACHE_TTL_MS = 60_000L

        /**
         * Builds an interceptor over the core-default (or test-overridden) [DnsResolver] from
         * [FeatureRegistry]. This is the ONLY construction path core uses so DNS transport stays
         * pluggable.
         */
        fun create(config: ProxyConfig, dialer: UpstreamDialer): DnsInterceptor =
            DnsInterceptor(FeatureRegistry.dnsResolverFactory(config, dialer))
    }
}

/**
 * The leak-policy packet pump. It is the sole reader of the real tun and bridges it to the engine
 * over an AF_UNIX SOCK_DGRAM socketpair (so both readers never race for the same fd). On the way it
 * enforces the leak model in Kotlin, where it is testable:
 *
 *  - IPv6 (version 6): dropped — blackholed in-tun (leak vector #2).
 *  - IPv4 fragments: dropped — no reassembly / evasion (leak vector #7).
 *  - IPv4 UDP to sentinel:53: tunnelled via [DnsInterceptor], reply synthesised (vector #3).
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
        upThread = kotlin.concurrent.thread(name = "djproxy-tun-up", isDaemon = true) { pumpTunToEngine() }
        downThread = kotlin.concurrent.thread(name = "djproxy-tun-down", isDaemon = true) { pumpEngineToTun() }
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
            val total = counters.bytesUp.addAndGet(n.toLong())
            // DIAGNOSTIC (§probe): confirm the router→engine socketpair link actually carries the
            // first uplink packet. A silent break here (hev never reads) is invisible otherwise.
            if (total == n.toLong()) LogBus.i(TAG, "first packet forwarded to engine ($n bytes)")
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

/** Helper to close a raw socketpair fd from the service side without importing Os everywhere. */
internal fun closeQuietly(fd: FileDescriptor?) {
    if (fd == null) return
    runCatching { Os.close(fd) }
}
