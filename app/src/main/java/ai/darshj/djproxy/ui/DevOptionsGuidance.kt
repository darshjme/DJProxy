package ai.darshj.djproxy.ui

import android.os.Build

/**
 * Single source of truth for OEM-specific "how to enable Developer Options / mock-location app" copy,
 * shared by [OnboardingSheet] (first run) and [SettingsScreen] (later) so both surfaces always agree
 * on the exact field name + path for THIS device — previously onboarding hardcoded "Build number" /
 * "System → Developer options" and could disagree with (and mislead vs) the Settings guidance on
 * Xiaomi/Samsung.
 */
internal fun manufacturerIsSamsung() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

internal fun manufacturerIsXiaomi() =
    Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) || Build.BRAND.equals("redmi", ignoreCase = true)

internal fun buildNumberFieldLabel(): String = if (manufacturerIsXiaomi()) "MIUI version" else "Build number"

internal fun devOptionsPathHint(): String = when {
    manufacturerIsSamsung() -> "Settings > Developer options (it appears near the bottom of the main " +
        "Settings list once unlocked; on older OneUI it is under Settings > About phone > Software information)."
    manufacturerIsXiaomi() -> "Settings > About phone > tap \"MIUI version\" 7 times, then " +
        "Settings > Additional settings > Developer options."
    else -> "Settings > System > Developer options (on AOSP-style phones and emulators)."
}
