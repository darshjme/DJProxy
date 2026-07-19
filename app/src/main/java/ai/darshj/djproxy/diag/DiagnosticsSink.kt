package ai.darshj.djproxy.diag

import ai.darshj.djproxy.vpn.LogBus
import ai.darshj.djproxy.vpn.seams.CriticalFailure
import ai.darshj.djproxy.vpn.seams.CriticalFailureSink
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Public API the UI lane calls (the ui lane never imports the sink's internals). Holds the last
 * report a critical failure produced so the UI can offer a one-tap "send" banner, and exposes the
 * manual "Send diagnostic report" action used by the settings panel.
 */
object Diagnostics {

    private val _pending = MutableStateFlow<DiagnosticReport?>(null)

    /** Non-null when a critical failure has parked a ready-to-send report the UI should offer. */
    val pending: StateFlow<DiagnosticReport?> = _pending.asStateFlow()

    internal fun setPending(report: DiagnosticReport?) { _pending.value = report }

    /** UI calls this after it has offered (and the user dismissed or sent) the pending report. */
    fun clearPending() { _pending.value = null }

    /**
     * User-initiated report (Settings ▸ "Send diagnostic report"). Collects a fresh, redacted report
     * and opens the mail composer. Returns true if a mail app accepted it, false if none exists.
     */
    fun sendManualReport(context: Context): Boolean {
        val report = ReportBuilder.collect(context, failure = null)
        return MailIntentFactory.launch(context, report)
    }

    /** Opens the composer for an already-built report (e.g. the pending critical-failure report). */
    fun send(context: Context, report: DiagnosticReport): Boolean =
        MailIntentFactory.launch(context, report)
}

/**
 * The [CriticalFailureSink] the diagnostics lane registers into [FeatureRegistry]. Core calls
 * [onCriticalFailure] from bring-up / the uncaught handler, always inside runCatching — so this MUST
 * return fast and MUST NOT throw. All heavy work (report assembly, disk I/O, launching the composer)
 * is posted to this lane's own supervised scope.
 */
class DiagnosticsSink(context: Context) : CriticalFailureSink {

    private val app = context.applicationContext

    // This lane's own scope: a fault here can never touch core (SupervisorJob + swallow-on-fault).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCriticalFailure(failure: CriticalFailure) {
        // Fast + non-throwing: hand off immediately, never do work on the caller's thread.
        runCatching {
            scope.launch {
                runCatching {
                    val report = ReportBuilder.collect(app, failure)

                    // Park it for the UI to offer, and persist a copy so it survives a UI restart.
                    Diagnostics.setPending(report)
                    runCatching {
                        File(app.filesDir, LAST_REPORT_FILE).writeText(report.body)
                    }

                    LogBus.w(TAG, "critical failure captured (${failure.category}); report ready")

                    // Only auto-open the composer if the user opted in; otherwise the report simply
                    // waits in Settings. Nothing ever leaves the device without an explicit tap.
                    if (DiagPrefs.get(app).enabled.value) {
                        val launched = Diagnostics.send(app, report)
                        LogBus.i(TAG, if (launched) "opened mail composer" else "no mail app available")
                    }
                }.onFailure { LogBus.e(TAG, "report build failed: ${it.message}") }
            }
        }
    }

    companion object {
        private const val TAG = "diag"
        const val LAST_REPORT_FILE = "last_diag_report.txt"
    }
}
