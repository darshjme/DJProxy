package ai.darshj.djproxy.vpn

import android.os.Build
import ai.darshj.djproxy.dns.DnsMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * The advisory post-connect health pass (§2.2). It runs the former leak probes as INDICATORS on an
 * interval (first pass immediately, then every [intervalMs]) and publishes a [HealthReport] into
 * [VpnState.health] + [VpnRuntime.lastHealthReport].
 *
 * Hard guarantees (§2.2): NO probe can throw, block the tunnel, or change [VpnStage]. Every probe
 * body is wrapped; failures degrade to [Health.UNKNOWN]/[Health.DEGRADED]. A degraded DNS check also
 * naturally advances the composite resolver's sticky head (it fails a transport → next query sticks
 * to the survivor), which IS the automatic DNS-mode fallback — never a teardown.
 */
class HealthMonitor(
    private val dns: DnsInterceptor,
    private val intervalMs: Long = HEALTH_INTERVAL_MS,
) {
    // Own crash-proof scope; a fault here can never reach bring-up or the UI process (§4.2).
    private val scope = Coro.safeScope("health")
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val report = runCatching { runPass() }.getOrElse {
                    HealthReport(checkedAtMs = System.currentTimeMillis())
                }
                publish(report)
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
    }

    private fun publish(report: HealthReport) {
        VpnRuntime.lastHealthReport = report
        // Only decorate an existing CONNECTED/RECONNECTING state — never change the stage.
        VpnRuntime.update { if (it.isUp) it.copy(health = report) else it }
        if (report.hasWarnings) {
            LogBus.i(TAG, "health advisory: v6=${report.ipv6} udp=${report.udp} dns=${report.dns} " +
                "strategy=${report.activeDnsStrategy} emuBypass=${report.emulatorBypassSuspected}")
        }
    }

    private suspend fun runPass(): HealthReport = HealthReport(
        ipv6 = checkIpv6(),
        udp = checkUdp(),
        dns = checkDns(),
        activeDnsStrategy = dns.activeLabel,
        emulatorBypassSuspected = EmulatorHeuristics.suspectBypass(),
        checkedAtMs = System.currentTimeMillis(),
    )

    /** OK = a v6 literal is unreachable (::/0 blackholed in-tun). Reachable ⇒ DEGRADED (may leak). */
    private fun checkIpv6(): Health = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getByName("2001:4860:4860::8888"), 53), 3_000)
        }
        Health.DEGRADED // it connected → IPv6 is escaping
    }.getOrDefault(Health.OK) // any failure (unreachable/timeout) is the intended blackhole

    /** OK = a UDP send to a non-sentinel resolver is dropped. A reply ⇒ DEGRADED (UDP/WebRTC leak). */
    private fun checkUdp(): Health = try {
        DatagramSocket().use { ds ->
            ds.soTimeout = 3_000
            val q = DnsMessage.buildQuery(Random.nextInt(0xFFFF), "example.com")
            ds.send(DatagramPacket(q, q.size, InetAddress.getByName("8.8.8.8"), 53))
            ds.receive(DatagramPacket(ByteArray(512), 512))
            Health.DEGRADED // got a reply → UDP is escaping
        }
    } catch (_: SocketTimeoutException) {
        Health.OK
    } catch (_: Exception) {
        Health.OK // any other failure is also "not leaking"
    }

    /** OK = a name resolves through the proxy resolver. Failure ⇒ DEGRADED (fallback already advanced). */
    private suspend fun checkDns(): Health {
        val id = Random.nextInt(0xFFFF)
        val query = DnsMessage.buildQuery(id, HEALTH_DNS_NAME)
        val answer = runCatching { dns.resolve(query) }.getOrNull() ?: return Health.DEGRADED
        val ok = DnsMessage.isResponseFor(id, answer, answer.size) && DnsMessage.hasAnswers(answer, answer.size)
        return if (ok) Health.OK else Health.DEGRADED
    }

    companion object {
        private const val TAG = "health"
        const val HEALTH_INTERVAL_MS = 60_000L
        private const val HEALTH_DNS_NAME = "one.one.one.one"
    }
}

/**
 * Best-effort emulator detection for the honest "emulator networking may bypass the VPN" advisory
 * (§7.3). Core keeps its OWN minimal heuristic so it never hard-depends on the compat lane's
 * CapabilityDetector; the advisory is non-blocking and only sets a chip.
 */
internal object EmulatorHeuristics {
    private val NEEDLES = listOf(
        "generic", "unknown", "goldfish", "ranchu", "vbox", "emulator",
        "nox", "ldplayer", "bluestacks", "genymotion", "android sdk built for",
    )

    fun isEmulator(): Boolean {
        val hay = (Build.FINGERPRINT + Build.HARDWARE + Build.PRODUCT + Build.MODEL + Build.MANUFACTURER)
            .lowercase()
        return NEEDLES.any { hay.contains(it) }
    }

    /**
     * On an emulator we cannot prove tethered/in-tun routing is honoured (LDPlayer/BlueStacks
     * frequently route around the guest VPN), so we surface a truthful "may bypass" advisory rather
     * than claim full protection. Never a gate.
     */
    fun suspectBypass(): Boolean = isEmulator()
}
