package ai.darshj.djproxy.freeproxy

import ai.darshj.djproxy.proxy.PreflightValidator
import ai.darshj.djproxy.proxy.SocketProtector
import ai.darshj.djproxy.proxy.ValidationResult
import ai.darshj.djproxy.proxy.Validator
import ai.darshj.djproxy.vpn.VpnRuntime
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Live progress of one health sweep: how many candidates have finished checking, the sweep total, and
 * how many came back GREEN so far. Drives the "Checking 47/600 · 12 live" line in the Free tab.
 */
data class SweepProgress(val checked: Int, val total: Int, val live: Int) {
    /** True while a sweep is running (some candidates still unchecked). */
    val inFlight: Boolean get() = total > 0 && checked < total
}

/**
 * Concurrent health sweep over the free-proxy CANDIDATE pool. This is the "only show working, green
 * proxies" engine behind the Free tab overhaul: the raw merged pool (~600 candidates) goes in, and
 * ONLY the entries that pass the app's REAL pre-flight — TCP connect + SOCKS5/HTTP-CONNECT handshake
 * + a probe request THROUGH the proxy, i.e. [PreflightValidator] — come out, latency-sorted fastest
 * first and enriched with `latencyMs` / `exitIp` / `lastCheckedAt` / `alive = true` via `copy()`.
 *
 * GREEN has exactly ONE definition in this app: [ValidationResult.Success]. This class deliberately
 * reuses that vocabulary and the frozen validator — it invents no second checker. Concurrency is the
 * same bounded [Semaphore.withPermit] fan-out as `store.ValidatorStatusChecker.checkAll`, just wider
 * (public candidates are mostly dead, so a 28-wide sweep at ~4 s timeouts finishes ~600 candidates in
 * well under two minutes worst-case). Sockets go through the ONE protect() seam ([VpnRuntime.protector])
 * so a sweep is correct whether or not a tunnel happens to be up, and never brings one up itself.
 *
 * Reactive surface for the UI:
 *  - [progress] — checked/total/live counters, updated after every candidate lands.
 *  - [liveSoFar] — the green entries found so far, latency-sorted, so the list fills progressively
 *    instead of staying blank until the sweep ends.
 *
 * A green result still does NOT mean trustworthy — [FreeProxyEntry.UNTRUSTED_CAVEAT] stands.
 *
 * @param validatorFactory builds a [Validator] per check from a [SocketProtector]; defaults to the
 *   real [PreflightValidator] with SHORT sweep timeouts (4 s connect + 4 s io — a free proxy slower
 *   than that is not worth listing). Tests inject a fake validator here, exactly like
 *   `ValidatorStatusChecker`.
 * @param protector the ONE protect() seam; defaults to [VpnRuntime.protector].
 * @param maxConcurrency bounded fan-out width for the sweep.
 * @param powerSaveMode when true at sweep start, the sweep is skipped entirely (returns empty,
 *   progress shows 0/0) — battery-respectful, mirroring `ValidatorStatusChecker.checkStale`.
 * @param perProxyCapMs belt-and-suspenders hard cap per candidate; the validator's own connect/io
 *   timeouts are the real bound, this cancels a pathological straggler.
 * @param clock injectable time source for deterministic tests (stamps [FreeProxyEntry.lastCheckedAt]).
 */
class FreeProxyHealthChecker(
    private val validatorFactory: (SocketProtector) -> Validator = { p ->
        PreflightValidator(
            protector = p,
            connectTimeoutMs = SWEEP_CONNECT_TIMEOUT_MS,
            ioTimeoutMs = SWEEP_IO_TIMEOUT_MS,
        )
    },
    private val protector: SocketProtector = VpnRuntime.protector,
    private val maxConcurrency: Int = DEFAULT_CONCURRENCY,
    private val powerSaveMode: () -> Boolean = { false },
    private val perProxyCapMs: Long = PER_PROXY_CAP_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _progress = MutableStateFlow(SweepProgress(checked = 0, total = 0, live = 0))

    /** Live sweep counters, updated as each candidate finishes ("Checking 47/600 · 12 live"). */
    val progress: StateFlow<SweepProgress> = _progress.asStateFlow()

    private val _liveSoFar = MutableStateFlow<List<FreeProxyEntry>>(emptyList())

    /** GREEN entries found so far in the current sweep, latency-sorted — streams as each one lands. */
    val liveSoFar: StateFlow<List<FreeProxyEntry>> = _liveSoFar.asStateFlow()

    private val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
    private val publishLock = Mutex()

    /**
     * Validate every candidate in [pool] concurrently (bounded by [maxConcurrency]) and return ONLY
     * the alive entries, sorted by [FreeProxyEntry.latencyMs] ascending (fastest first). Each returned
     * entry is a `copy()` carrying `alive = true`, its measured `latencyMs`, the probe-observed
     * `exitIp` (when the endpoint reflected one), and `lastCheckedAt`.
     *
     * Cancellation-safe: the whole sweep is a single [coroutineScope]; cancelling the caller cancels
     * every in-flight check. Under [powerSaveMode] the sweep is skipped and returns empty.
     */
    suspend fun sweep(pool: List<FreeProxyEntry>): List<FreeProxyEntry> {
        publishLock.withLock {
            _liveSoFar.value = emptyList()
            _progress.value = SweepProgress(checked = 0, total = if (powerSaveMode()) 0 else pool.size, live = 0)
        }
        if (pool.isEmpty() || powerSaveMode()) return emptyList()

        coroutineScope {
            pool.forEach { entry ->
                launch {
                    semaphore.withPermit {
                        val result = withTimeoutOrNull(perProxyCapMs) {
                            validatorFactory(protector).validate(entry.toConfig())
                        }
                        val green = result as? ValidationResult.Success
                        publishLock.withLock {
                            val p = _progress.value
                            if (green != null) {
                                val enriched = entry.copy(
                                    alive = true,
                                    latencyMs = green.latencyMs,
                                    exitIp = green.exitIp,
                                    lastCheckedAt = clock(),
                                )
                                _liveSoFar.value = (_liveSoFar.value + enriched)
                                    .sortedBy { it.latencyMs ?: Long.MAX_VALUE }
                                _progress.value = p.copy(checked = p.checked + 1, live = p.live + 1)
                            } else {
                                _progress.value = p.copy(checked = p.checked + 1)
                            }
                        }
                    }
                }
            }
        }
        return _liveSoFar.value
    }

    companion object {
        /** Wider than the vault checker's 6 — a sweep is a one-shot burst over mostly-dead hosts. */
        const val DEFAULT_CONCURRENCY = 28

        /** Short sweep timeouts: a free proxy slower than 4 s to connect/answer is not worth listing. */
        const val SWEEP_CONNECT_TIMEOUT_MS = 4_000
        const val SWEEP_IO_TIMEOUT_MS = 4_000

        /** Hard per-candidate cap (belt-and-suspenders over the validator's own timeouts). */
        const val PER_PROXY_CAP_MS = 5_000L
    }
}
