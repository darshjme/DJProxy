package ai.darshj.djproxy.diag

import ai.darshj.djproxy.BuildConfig
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Turns a finished [DiagnosticReport] into an ACTION_SENDTO `mailto:` intent aimed at the owner's
 * inbox. There is NO backend, NO SMTP, NO INTERNET-side send: this only opens the device's own mail
 * composer, pre-filled. Nothing is transmitted unless the user taps Send in their mail app.
 *
 * `ACTION_SENDTO` + a `mailto:` data URI guarantees ONLY e-mail apps match (a plain `ACTION_SEND`
 * would offer messengers/clipboard, risking a report leaving through an unexpected app).
 */
object MailIntentFactory {

    /**
     * The report recipient, sourced from [BuildConfig.DIAG_RECIPIENT] (a build-config value, not a
     * source literal), so a rebranded fork controls/blanks it in its own Gradle build instead of
     * silently mailing to the original owner. The official build ships the owner's inbox.
     */
    val RECIPIENT: String = BuildConfig.DIAG_RECIPIENT

    /** The bare `mailto:` string; kept pure so it is unit-testable without the Android framework. */
    fun mailtoUri(): String = "mailto:$RECIPIENT"

    /** Subject line used for the mail (mirrors the report's own subject). */
    fun subjectFor(report: DiagnosticReport): String = report.subject

    /**
     * Builds the composer intent. [report].body is ALREADY redacted by [ReportBuilder]; we never add
     * any credential here. Adds NEW_TASK so it can be launched from a Service/Application context
     * (e.g. the critical-failure path), not only from an Activity.
     */
    fun create(context: Context, report: DiagnosticReport): Intent =
        Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse(mailtoUri())
            putExtra(Intent.EXTRA_EMAIL, arrayOf(RECIPIENT))
            putExtra(Intent.EXTRA_SUBJECT, subjectFor(report))
            putExtra(Intent.EXTRA_TEXT, report.body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Launches the composer, wrapped in a chooser. Returns true if a mail app took it; false if the
     * device has no mail client (reported honestly — we never pretend a report was sent).
     */
    fun launch(context: Context, report: DiagnosticReport): Boolean {
        val app = context.applicationContext
        val send = create(app, report)
        val chooser = Intent.createChooser(send, "Send diagnostic report")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            if (send.resolveActivity(app.packageManager) != null) {
                app.startActivity(chooser)
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }
}
