package ai.darshj.djproxy.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The single source of truth for layout-gap spacing (the spacing analogue of [DjColors],
 * [MotionTokens], and [DjShapes]). Six named steps replace the bare `18.dp` / `20.dp` / `22.dp`
 * literals that had drifted across Home / Settings / Onboarding — each call site now reads a
 * governed step instead of a number nobody can trace back to a decision. When a screen's existing
 * literal doesn't land exactly on a step, the call site adopts the closest one (documented inline
 * at that call site) rather than adding a seventh bespoke value.
 */
object DjSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
}
