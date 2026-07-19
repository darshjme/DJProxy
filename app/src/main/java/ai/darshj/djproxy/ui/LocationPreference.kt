package ai.darshj.djproxy.ui

import android.content.Context

/**
 * Pure, side-effect-free persisted flag for the user's EXPLICIT choice on GPS location matching
 * (a.k.a. location spoofing). This is the single source of truth for whether the user opted in —
 * both [OnboardingSheet] (first-run choice) and [SettingsScreen] (later toggle) read/write it, and
 * the location lane (`ai.darshj.djproxy.location.*`) is expected to gate every mock-location publish
 * behind [isEnabled] so spoofing NEVER runs unless the user chose it, regardless of whether the
 * mock-location app-op grant happens to be set.
 *
 * Deliberately dependency-free (just [Context] + SharedPreferences) so any lane can read it without
 * pulling in ui/ Compose code.
 */
object LocationPreference {
    private const val PREFS = "djproxy_location_pref"
    private const val KEY_ENABLED = "location_matching_enabled"
    private const val KEY_CHOICE_MADE = "location_matching_choice_made"

    /** True only if the user explicitly opted in to GPS location matching. Defaults to false/off. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** True once the user has made an explicit choice (Yes or No) at least once. */
    fun hasChosen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CHOICE_MADE, false)

    /** Persists the user's explicit choice and marks it as made. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putBoolean(KEY_CHOICE_MADE, true)
            .apply()
    }
}
