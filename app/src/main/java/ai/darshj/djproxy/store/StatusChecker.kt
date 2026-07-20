package ai.darshj.djproxy.store

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.proxy.PreflightValidator
import ai.darshj.djproxy.proxy.SocketProtector
import ai.darshj.djproxy.proxy.Validator
import ai.darshj.djproxy.proxy.ValidationResult
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

/**
 * Per-proxy liveness. Publishes a reactive [statuses] map the Servers screen observes.
 *
 * It runs the EXISTING pre-flight [proxy.PreflightValidator] DIRECTLY — a real TCP connect + real
 * SOCKS5/HTTP handshake + real `/generate_204` probe THROUGH the proxy. It NEVER calls
 * `VpnController.apply()`, never starts `DjVpnService`, never builds a tun: status is pure pre-flight.
 */
interface StatusChecker {

    /** Live status map keyed by [SavedProxy.id] (or a free-proxy key). */
    val statuses: StateFlow<Map<String, ProxyStatus>>

    /** Check ONE target now. Publishes Checking → Reachable/Unreachable into [statuses]. */
    suspend fun check(key: String, config: ProxyConfig): ProxyStatus

    /** Bounded-concurrency "check all". Manual invocation is always allowed (no debounce). */
    suspend fun checkAll(targets: List<Pair<String, ProxyConfig>>)
}

/**
 * The production [StatusChecker], wired to the frozen validator + the single existing protect() seam.
 *
 * @param validatorFactory builds a [Validator] per check from a [SocketProtector]; defaults to the
 *   real [PreflightValidator]. Tests inject a fake validator here.
 * @param protector the ONE protect() seam ([VpnRuntime.protector]): a no-op returning true when no
 *   tunnel is up (ordinary direct socket), and — if a tunnel happens to be live — it protects the
 *   check socket out of the tun via the single existing `VpnService.protect()` call site. So checks
 *   are correct in every state and still bring NO tunnel up.
 * @param maxConcurrency bounded fan-out for [checkAll] (battery-respectful).
 * @param staleMs auto-refresh horizon: [checkStale] only re-checks entries older than this / Unknown.
 * @param minIntervalMs per-key debounce for auto paths ([checkStale]); manual [check]/[checkAll]
 *   bypass it.
 * @param powerSaveMode guard used by [checkStale]; when true, auto-refresh is skipped (manual still
 *   works). Defaults to "never in power-save" for pure JVM/testable construction.
 * @param clock injectable time source for deterministic tests.
 */
class ValidatorStatusChecker(
    private val validatorFactory: (SocketProtector) -> Validator = { p -> PreflightValidator(protector = p) },
    private val protector: SocketProtector = VpnRuntime.protector,
    private val maxConcurrency: Int = DEFAULT_CONCURRENCY,
    private val staleMs: Long = STALE_MS,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val powerSaveMode: () -> Boolean = { false },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : StatusChecker {

    private val _statuses = MutableStateFlow<Map<String, ProxyStatus>>(emptyMap())
    override val statuses: StateFlow<Map<String, ProxyStatus>> = _statuses.asStateFlow()

    private val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
    private val publishLock = Mutex()

    /** Last time (millis) a check STARTED per key — used only for the auto debounce. */
    private val lastStartedAt = mutableMapOf<String, Long>()
    private val startedLock = Mutex()

    override suspend fun check(key: String, config: ProxyConfig): ProxyStatus {
        markStarted(key)
        publish(key, ProxyStatus.Checking)
        val status = runValidator(config)
        publish(key, status)
        return status
    }

    override suspend fun checkAll(targets: List<Pair<String, ProxyConfig>>) {
        if (targets.isEmpty()) return
        // Mark Checking up front so the whole visible batch shows spinners immediately.
        targets.forEach { (key, _) -> publish(key, ProxyStatus.Checking) }
        coroutineScope {
            targets.forEach { (key, config) ->
                launch {
                    semaphore.withPermit {
                        markStarted(key)
                        publish(key, runValidator(config))
                    }
                }
            }
        }
    }

    /**
     * Battery-respectful auto path used by the Servers screen while visible: checks only entries that
     * are [ProxyStatus.Unknown] or older than [staleMs], debounced per key by [minIntervalMs], and
     * skipped entirely under power-save. Returns the subset actually scheduled (for tests/telemetry).
     */
    suspend fun checkStale(targets: List<Pair<String, ProxyConfig>>): List<String> {
        if (powerSaveMode()) return emptyList()
        val now = clock()
        val due = targets.filter { (key, _) -> isDue(key, now) }
        if (due.isEmpty()) return emptyList()
        checkAll(due)
        return due.map { it.first }
    }

    /** Drop cached status/debounce for keys no longer present (e.g. deleted proxies). */
    suspend fun retain(keys: Set<String>) {
        publishLock.withLock {
            _statuses.value = _statuses.value.filterKeys { it in keys }
        }
        startedLock.withLock { lastStartedAt.keys.retainAll(keys) }
    }

    private fun isDue(key: String, now: Long): Boolean {
        val lastStart = lastStartedAt[key]
        if (lastStart != null && now - lastStart < minIntervalMs) return false
        return when (val s = _statuses.value[key]) {
            null, ProxyStatus.Unknown -> true
            ProxyStatus.Checking -> false
            is ProxyStatus.Reachable -> now - s.checkedAt >= staleMs
            is ProxyStatus.Unreachable -> now - s.checkedAt >= staleMs
        }
    }

    private suspend fun runValidator(config: ProxyConfig): ProxyStatus =
        when (val res = validatorFactory(protector).validate(config)) {
            is ValidationResult.Success ->
                ProxyStatus.Reachable(res.latencyMs, res.exitIp, clock())
            is ValidationResult.Failure ->
                ProxyStatus.Unreachable(res.error.message, res.error.hint, clock())
        }

    private suspend fun publish(key: String, status: ProxyStatus) = publishLock.withLock {
        _statuses.value = _statuses.value + (key to status)
    }

    private suspend fun markStarted(key: String) = startedLock.withLock {
        lastStartedAt[key] = clock()
    }

    companion object {
        const val DEFAULT_CONCURRENCY = 6
        const val STALE_MS = 5 * 60 * 1000L
        const val MIN_INTERVAL_MS = 60 * 1000L
    }
}
