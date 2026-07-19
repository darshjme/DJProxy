package ai.darshj.djproxy.diag

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the single user preference this lane owns: whether DJProxy may auto-open the mail
 * composer on a critical failure. Default OFF (opt-in) — a failure otherwise just parks a report
 * the user can send manually from Settings, so nothing ever leaves the device without an explicit
 * user action.
 *
 * Process-singleton so the settings toggle and the [DiagnosticsSink] observe the SAME state.
 */
class DiagPrefs private constructor(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("djproxy_diag", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(sp.getBoolean(KEY_ENABLED, false))

    /** True = a critical failure may auto-open the mail composer (user still taps Send). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        sp.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    companion object {
        private const val KEY_ENABLED = "diag_reports_enabled"

        @Volatile
        private var instance: DiagPrefs? = null

        fun get(context: Context): DiagPrefs =
            instance ?: synchronized(this) {
                instance ?: DiagPrefs(context).also { instance = it }
            }
    }
}
