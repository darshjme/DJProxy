package ai.darshj.djproxy.vpn

import ai.darshj.djproxy.engine.EngineController
import ai.darshj.djproxy.engine.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the fail-closed guarantee across engine faults, doze, and link changes.
 *
 * When the engine crashes (native exit) or the router's engine side EOFs, the tun and its routes
 * are HELD — nothing is torn down — so traffic drops instead of leaking. The watchdog then re-plumbs
 * the engine with capped exponential backoff. Only if every attempt is exhausted does it give up and
 * fail the whole tunnel closed (routes come down last, never leaking in the interim).
 *
 * [reconnect] is expected to rebuild the socketpair + engine + router and return true once the
 * engine is Running again. It is invoked serially; concurrent faults are coalesced.
 */
class EngineWatchdog(
    private val scope: CoroutineScope,
    private val engine: EngineController,
    private val reconnect: suspend () -> Boolean,
    private val onGiveUp: () -> Unit,
) {
    private val reconnecting = AtomicBoolean(false)
    private var observer: Job? = null
    private var recovery: Job? = null

    /** Start observing the engine's own state; a Crashed state is treated as a fault. */
    fun start() {
        observer = scope.launch {
            engine.state.collect { st ->
                if (st is EngineState.Crashed) {
                    LogBus.w(TAG, "engine crashed (exit=${st.exitCode}: ${st.reason})")
                    reportFault()
                }
            }
        }
    }

    /** Report a fault from any source (router EOF, engine crash). Idempotent while a recovery runs. */
    fun reportFault() {
        if (!reconnecting.compareAndSet(false, true)) return
        recovery = scope.launch {
            try {
                for ((attempt, backoff) in BACKOFFS.withIndex()) {
                    if (!isActive) return@launch
                    // Routes are held: mark reconnecting so the UI shows fail-closed, not "connected".
                    VpnRuntime.update { if (it.isUp) it.copy(stage = VpnStage.RECONNECTING) else it }
                    LogBus.w(TAG, "reconnect attempt ${attempt + 1}/${BACKOFFS.size} in ${backoff}ms")
                    delay(backoff)
                    val ok = runCatching { reconnect() }.getOrElse {
                        LogBus.e(TAG, "reconnect threw: ${it.message}"); false
                    }
                    if (ok) {
                        LogBus.i(TAG, "engine recovered")
                        VpnRuntime.update { it.copy(stage = VpnStage.CONNECTED, error = null) }
                        return@launch
                    }
                }
                LogBus.e(TAG, "engine unrecoverable after ${BACKOFFS.size} attempts — failing closed")
                onGiveUp()
            } finally {
                reconnecting.set(false)
            }
        }
    }

    fun stop() {
        observer?.cancel()
        recovery?.cancel()
        observer = null
        recovery = null
    }

    companion object {
        private const val TAG = "watchdog"
        private val BACKOFFS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
    }
}
