package ai.darshj.djproxy.engine

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [EngineController] over the native hev-socks5-tunnel loop ([HevBridge]).
 *
 * hev's entry point ([HevBridge.runBlocking]) blocks for the whole lifetime of the tunnel, so we
 * run it on a dedicated daemon thread and translate its start/return into an [EngineState] flow the
 * watchdog observes. The design is deliberately fail-closed:
 *
 *  - [start] loads the .so (throwing here surfaces a [EngineState.Crashed] rather than a silent no-op),
 *    spawns the loop thread, and blocks only until the thread has entered native code.
 *  - When [HevBridge.runBlocking] returns, we decide Stopped vs Crashed by whether [stop] asked for it.
 *  - [stop] calls [HevBridge.quit] and joins, so a caller that stops us can rely on the fd being idle.
 *
 * Instances live in the :engine process; a native crash there tears down only that process while the
 * main process keeps the tun + routes up (traffic drops, never leaks).
 */
class HevTunnel(
    private val bridge: HevBridgeApi = DefaultHevBridge,
) : EngineController {

    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val lock = Any()
    private var loopThread: Thread? = null

    /** True between a [stop] request and the loop thread actually returning. */
    @Volatile private var stopRequested = false

    override fun start(config: EngineConfig) {
        synchronized(lock) {
            if (loopThread?.isAlive == true) {
                Log.i(TAG, "start(): loop already running, ignoring")
                return
            }
            stopRequested = false
            _state.value = EngineState.Starting

            try {
                bridge.load()
            } catch (t: Throwable) {
                Log.e(TAG, "start(): native library failed to load", t)
                _state.value = EngineState.Crashed(exitCode = -1, reason = "loadLibrary: ${t.message}")
                return
            }

            val yaml = config.toYaml()
            val tunFd = config.tunFd
            // Counted down the instant the thread has begun executing (right before it blocks in
            // native code). start() waits on it so callers see RUNNING before returning.
            val entered = CountDownLatch(1)

            val thread = Thread({
                _state.value = EngineState.Running
                entered.countDown()
                val ret = try {
                    bridge.runBlocking(yaml, tunFd)
                } catch (t: Throwable) {
                    Log.e(TAG, "runBlocking threw", t)
                    -1
                }
                synchronized(lock) {
                    loopThread = null
                    _state.value = when {
                        stopRequested -> EngineState.Stopped
                        ret == 0 -> EngineState.Stopped
                        else -> EngineState.Crashed(exitCode = ret, reason = "hev loop exited ($ret)")
                    }
                }
            }, "djproxy-hev-loop")
            thread.isDaemon = true
            loopThread = thread
            thread.start()

            // Bounded wait: we only block until the loop thread is inside native code.
            if (!entered.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "start(): loop thread did not enter native code in time")
            }
        }
    }

    override fun stop() {
        val t: Thread?
        synchronized(lock) {
            t = loopThread
            if (t == null) {
                if (_state.value !is EngineState.Crashed) _state.value = EngineState.Stopped
                return
            }
            stopRequested = true
        }
        // quit() unblocks the native loop; do it outside the lock so the loop's terminal
        // state update (which also grabs the lock) can proceed.
        try {
            bridge.quit()
        } catch (t2: Throwable) {
            Log.w(TAG, "quit() threw", t2)
        }
        try {
            t?.join(STOP_TIMEOUT_MS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun stats(): EngineStats {
        if (_state.value !is EngineState.Running) return EngineStats.EMPTY
        return try {
            val raw = bridge.statsRaw()
            if (raw.size >= 4) {
                EngineStats(
                    txPackets = raw[0],
                    txBytes = raw[1],
                    rxPackets = raw[2],
                    rxBytes = raw[3],
                )
            } else {
                EngineStats.EMPTY
            }
        } catch (t: Throwable) {
            EngineStats.EMPTY
        }
    }

    companion object {
        private const val TAG = "HevTunnel"
        private const val START_TIMEOUT_MS = 3_000L
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Seam over [HevBridge] so [HevTunnel]'s lifecycle logic is unit-testable on the JVM without the .so.
 * Production wiring is [DefaultHevBridge], which forwards to the real native object.
 */
interface HevBridgeApi {
    fun load()
    fun runBlocking(configYaml: String, tunFd: Int): Int
    fun quit()
    fun statsRaw(): LongArray
}

/** Forwards to the real native [HevBridge]. */
object DefaultHevBridge : HevBridgeApi {
    override fun load() = HevBridge.load()
    override fun runBlocking(configYaml: String, tunFd: Int): Int = HevBridge.runBlocking(configYaml, tunFd)
    override fun quit() = HevBridge.quit()
    override fun statsRaw(): LongArray = HevBridge.statsRaw()
}
