package ai.darshj.djproxy.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import ai.darshj.djproxy.engine.EngineConfig
import ai.darshj.djproxy.engine.EngineController
import ai.darshj.djproxy.engine.EngineStats
import ai.darshj.djproxy.engine.EngineState
import ai.darshj.djproxy.engine.IEngineControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The out-of-process handle to the native hev loop. THIS is what makes the fail-closed guarantee
 * real: the C/lwIP transport runs inside the isolated `:engine` process ([ai.darshj.djproxy.engine.EngineService]),
 * so a native SIGSEGV/abort tears down only that process — the main process keeps owning the tun
 * ParcelFileDescriptor and its 0.0.0.0/0 + ::/0 routes, so traffic DROPS instead of egressing direct.
 *
 * A process death surfaces here as [EngineState.Crashed] (via the binder [IBinder.DeathRecipient]
 * and [ServiceConnection.onServiceDisconnected]); the [EngineWatchdog] observes that, holds the
 * routes, and rebinds/restarts with backoff. Nothing about a native crash can close the tun.
 *
 * fd numbers are not portable across processes, so the engine-side bridge fd is handed over as a
 * [ParcelFileDescriptor] across the Binder (the kernel dup()s it into `:engine`); the caller keeps
 * ownership of its own copy and closes it after [startRemote] returns.
 */
class RemoteEngine(private val context: Context) : EngineController {

    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val lock = Any()

    @Volatile private var iface: IEngineControl? = null
    @Volatile private var bound = false
    @Volatile private var readyLatch = CountDownLatch(1)

    private val deathRecipient = IBinder.DeathRecipient {
        LogBus.w(TAG, "engine process binder died")
        onEngineLost("engine process died")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val control = IEngineControl.Stub.asInterface(binder)
            iface = control
            runCatching { binder?.linkToDeath(deathRecipient, 0) }
            readyLatch.countDown()
            LogBus.i(TAG, "engine service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            LogBus.w(TAG, "engine service disconnected")
            onEngineLost("engine service disconnected")
        }
    }

    private fun onEngineLost(reason: String) {
        iface = null
        // Only a loss while we expected the engine to be up is a fault; a deliberate stop() is not.
        val current = _state.value
        if (current == EngineState.Running || current == EngineState.Starting) {
            _state.value = EngineState.Crashed(exitCode = -1, reason = reason)
        }
    }

    /**
     * Bind the `:engine` service (if needed), wait for the connection, and start the native loop
     * against [bridgeFd]. The caller retains ownership of [bridgeFd] and must close its own copy
     * after this returns (the Binder has already dup()'d it into the engine process).
     *
     * @return true once the engine RPC accepted the start; false on any bind/RPC failure (state is
     *   left [EngineState.Crashed] so the watchdog treats it as fail-closed).
     */
    fun startRemote(bridgeFd: ParcelFileDescriptor, socksPort: Int, mtu: Int, logLevel: String): Boolean {
        synchronized(lock) {
            _state.value = EngineState.Starting

            // Ensure a live connection. If we have no interface (first start or after a crash),
            // rebind fresh so a stale/one-shot latch never returns a dead binder.
            if (iface == null) {
                if (bound) {
                    runCatching { context.unbindService(connection) }
                    bound = false
                }
                readyLatch = CountDownLatch(1)
                val intent = Intent(context, ai.darshj.djproxy.engine.EngineService::class.java)
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    _state.value = EngineState.Crashed(-1, "bindService(:engine) returned false")
                    return false
                }
                if (!readyLatch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    _state.value = EngineState.Crashed(-1, "engine service bind timed out")
                    return false
                }
            }

            val control = iface ?: run {
                _state.value = EngineState.Crashed(-1, "engine service not connected")
                return false
            }

            return try {
                control.start(bridgeFd, socksPort, mtu, logLevel)
                _state.value = EngineState.Running
                LogBus.i(TAG, "engine started out-of-process (socksPort=$socksPort)")
                true
            } catch (e: RemoteException) {
                _state.value = EngineState.Crashed(-1, "engine start RPC failed: ${e.message}")
                false
            }
        }
    }

    /** Not used for the remote engine; start it via [startRemote] with a ParcelFileDescriptor. */
    override fun start(config: EngineConfig) {
        throw UnsupportedOperationException("RemoteEngine is started via startRemote(bridgeFd, ...)")
    }

    override fun stop() {
        synchronized(lock) {
            _state.value = EngineState.Stopped // set before unbind so onServiceDisconnected is not a fault
            runCatching { iface?.stop() }
            if (bound) {
                runCatching { context.unbindService(connection) }
                bound = false
            }
            iface = null
        }
    }

    override fun stats(): EngineStats {
        val control = iface ?: return EngineStats.EMPTY
        return try {
            val raw = control.stats()
            if (raw != null && raw.size >= 4) EngineStats(raw[0], raw[1], raw[2], raw[3]) else EngineStats.EMPTY
        } catch (_: Exception) {
            EngineStats.EMPTY
        }
    }

    companion object {
        private const val TAG = "engine"
        private const val BIND_TIMEOUT_MS = 5_000L
    }
}
