package ai.darshj.djproxy.vpn

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** One line in the live log. [timeMs] is epoch millis; the UI formats it. */
data class LogEvent(
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * Process-wide, hot log stream the UI scrolls. Every lane emits through this; nothing writes the
 * UI log directly. Replays the last [REPLAY] lines to a freshly-opened screen and drops the oldest
 * under back-pressure so a chatty tunnel can never stall a producer.
 *
 * NOTE: the :engine process is separate — it emits over its own channel and the main process
 * re-publishes into this bus. Keep messages free of secrets; the password is never logged.
 */
object LogBus {
    private const val REPLAY = 200

    private val _events = MutableSharedFlow<LogEvent>(
        replay = REPLAY,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<LogEvent> = _events.asSharedFlow()

    fun log(level: LogLevel, tag: String, message: String) {
        _events.tryEmit(LogEvent(System.currentTimeMillis(), level, tag, message))
        // Mirror to logcat so the tunnel is diagnosable over adb. Never carries the password
        // (producers keep secrets out of messages, per this file's contract).
        val t = "djproxy/$tag"
        when (level) {
            LogLevel.DEBUG -> android.util.Log.d(t, message)
            LogLevel.INFO -> android.util.Log.i(t, message)
            LogLevel.WARN -> android.util.Log.w(t, message)
            LogLevel.ERROR -> android.util.Log.e(t, message)
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)
}
