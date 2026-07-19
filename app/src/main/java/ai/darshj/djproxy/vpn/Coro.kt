package ai.darshj.djproxy.vpn

import ai.darshj.djproxy.vpn.seams.CriticalFailure
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The ONLY way core builds a [CoroutineScope] (§4.2). Every scope carries:
 *  - a [SupervisorJob] so one child failure never cancels its siblings, and
 *  - a [CoroutineExceptionHandler] that (a) logs the trace to [LogBus], (b) reports critical
 *    categories to [FeatureRegistry] (which is itself runCatching-wrapped), and (c) routes to a
 *    caller-supplied [onFatal] instead of letting the exception propagate to the thread's default
 *    handler and crash the process.
 *
 * This is what guarantees a fault anywhere in the tunnel degrades — it never takes down the UI process.
 */
object Coro {

    fun safeScope(
        name: String,
        dispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.Default,
        reportCategory: CriticalFailure.Category? = null,
        onFatal: (Throwable) -> Unit = {},
    ): CoroutineScope {
        val handler = CoroutineExceptionHandler { _, throwable ->
            runCatching { LogBus.e(name, "coroutine fault: ${throwable.message ?: throwable.javaClass.simpleName}") }
            if (reportCategory != null) {
                FeatureRegistry.reportCritical(
                    CriticalFailure(reportCategory, "$name: ${throwable.message ?: throwable.javaClass.simpleName}"),
                )
            }
            runCatching { onFatal(throwable) }
        }
        return CoroutineScope(SupervisorJob() + dispatcher + handler)
    }
}
