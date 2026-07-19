package ai.darshj.djproxy.vpn

import android.content.Context
import android.os.Build
import ai.darshj.djproxy.vpn.seams.CriticalFailure
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The global safety net (§4.1). Installed once from [ai.darshj.djproxy.DjProxyApp]; it is the last
 * thing to run before the platform's own default handler. On ANY uncaught throwable on ANY thread it:
 *  1. writes the stack + minimal build/device info to `filesDir/last_crash.txt` (for the diagnostic
 *     report — the diagnostics lane reads it),
 *  2. reports category [CriticalFailure.Category.UNCAUGHT] to [FeatureRegistry] (runCatching-wrapped),
 *  3. delegates to the previous default handler so the platform STILL records the crash.
 *
 * It never swallows silently and never itself throws (every step is guarded).
 */
object CrashCatcher {

    const val CRASH_FILE = "last_crash.txt"

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(appContext, thread, throwable) }
            runCatching {
                FeatureRegistry.reportCritical(
                    CriticalFailure(
                        category = CriticalFailure.Category.UNCAUGHT,
                        reason = "${throwable.javaClass.name}: ${throwable.message ?: ""} on ${thread.name}",
                    ),
                )
            }
            runCatching { LogBus.e(TAG, "UNCAUGHT on ${thread.name}: ${throwable.message}") }
            // Delegate so the OS still records + kills the process (never mask the crash).
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val text = buildString {
            append("DJProxy crash @ ").append(System.currentTimeMillis()).append('\n')
            append("thread: ").append(thread.name).append('\n')
            append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("abis: ").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
            append("----\n")
            append(sw.toString())
        }
        runCatching { File(context.filesDir, CRASH_FILE).writeText(text) }
    }

    private const val TAG = "crash"
}
