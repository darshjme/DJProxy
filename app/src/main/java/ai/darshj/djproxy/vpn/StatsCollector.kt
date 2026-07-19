package ai.darshj.djproxy.vpn

import ai.darshj.djproxy.engine.EngineController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide, thread-safe live counters for the active tunnel.
 *
 * Byte / DNS / UDP counters are fed by [TunRouter] (this lane). The connection counters are fed by
 * the proxy lane's LocalSocksServer through [onConnectionOpened]/[onConnectionClosed] — that is the
 * only cross-lane write surface, and it is intentionally minimal (two monotone/pair calls).
 */
class TunnelCounters {
    val bytesUp = AtomicLong(0)
    val bytesDown = AtomicLong(0)
    val udpDropped = AtomicLong(0)
    val dnsQueries = AtomicLong(0)
    private val totalConnections = AtomicLong(0)
    private val activeConnections = AtomicInteger(0)

    /** Call from the loopback SOCKS front when it accepts a new upstream flow. */
    fun onConnectionOpened() {
        totalConnections.incrementAndGet()
        activeConnections.incrementAndGet()
    }

    /** Call when a flow closes (never let active go negative). */
    fun onConnectionClosed() {
        activeConnections.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /** Zero everything at the start of a fresh session. */
    fun reset() {
        bytesUp.set(0); bytesDown.set(0); udpDropped.set(0); dnsQueries.set(0)
        totalConnections.set(0); activeConnections.set(0)
    }

    fun snapshot(): TunnelStats = TunnelStats(
        bytesUp = bytesUp.get(),
        bytesDown = bytesDown.get(),
        activeConnections = activeConnections.get(),
        totalConnections = totalConnections.get(),
        udpDropped = udpDropped.get(),
        dnsQueries = dnsQueries.get(),
    )
}

/**
 * Periodically folds [TunnelCounters] (and, best-effort, the native engine's own packet counters)
 * into [VpnRuntime]'s published [VpnState]. Only emits while the tunnel is up so an idle/errored
 * state is never overwritten with stale stats.
 */
class StatsCollector(
    private val scope: CoroutineScope,
    private val counters: TunnelCounters,
    private val engine: () -> EngineController?,
    private val intervalMs: Long = 1_000,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                val snap = counters.snapshot()
                // Prefer the engine's own byte counters when the router hasn't seen traffic yet.
                val eng = runCatching { engine()?.stats() }.getOrNull()
                val merged = if (eng != null && (snap.bytesUp == 0L && snap.bytesDown == 0L)) {
                    snap.copy(bytesUp = eng.txBytes, bytesDown = eng.rxBytes)
                } else snap
                VpnRuntime.update { st -> if (st.isUp) st.copy(stats = merged) else st }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
