package ai.darshj.djproxy.ui

/**
 * The ui lane's dependency-free navigation model (§2). Replaces the v3 boolean soup
 * (`showOnboarding` / `showSettings`) with one `rememberSaveable` sealed [Route] plus, on Home, a
 * nullable [HomeSheet]. No navigation library is pulled in — a single-Activity Compose app with
 * three destinations does not need one, and this keeps the surface auditable.
 *
 * Onboarding is NOT a Route: it stays a pre-Home gate exactly as v3 (a first-run boolean), so the
 * hardened onboarding flow is untouched.
 */
sealed interface Route {
    data object Home : Route
    data object Settings : Route
    data object About : Route
}

/** Saver so the current [Route] survives config changes / process death without a nav library. */
val RouteSaver: androidx.compose.runtime.saveable.Saver<Route, String> =
    androidx.compose.runtime.saveable.Saver(
        save = { route ->
            when (route) {
                Route.Home -> "home"
                Route.Settings -> "settings"
                Route.About -> "about"
            }
        },
        restore = { key ->
            when (key) {
                "settings" -> Route.Settings
                "about" -> Route.About
                else -> Route.Home
            }
        },
    )

/** The bottom sheets Home can raise (Tier 2). [None] means no sheet is open. */
enum class HomeSheet { None, Import, Scan, ManualEdit, ShareLan, TorInfo }
