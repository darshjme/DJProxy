package ai.darshj.djproxy.ui.adaptive

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's one responsive-layout primitive (owner bar: "truly responsive … adapts to the Fold7's
 * unfolded large screen … no fixed widths that break on a tablet pane"). Rather than pull in the
 * experimental material3-window-size-class artifact and thread a WindowSizeClass through every
 * composable, every screen wraps its scroll container in a `BoxWithConstraints` and asks this helper
 * for its content padding.
 *
 * On a phone-width pane (folded phone, LDPlayer) it returns the standard 20 dp gutter — pixel-identical
 * to the shipped layout, zero regression. On a wide pane (Fold7 unfolded inner display, landscape,
 * tablets) it grows the horizontal inset so the readable column centres at ~[maxContentWidth]
 * (~65–75 ch at body sizes) instead of stretching text and cards edge-to-edge into 120+ char lines.
 */
fun responsiveContentPadding(
    availableWidth: Dp,
    maxContentWidth: Dp = 600.dp,
    minSide: Dp = 20.dp,
    vertical: Dp = 24.dp,
): PaddingValues = PaddingValues(
    horizontal = responsiveSidePadding(availableWidth, maxContentWidth, minSide),
    vertical = vertical,
)

/**
 * The pure horizontal-inset calculation behind [responsiveContentPadding], split out so it is unit
 * testable on the JVM (no Density/LayoutDirection needed to read a [PaddingValues] back). Returns the
 * standard [minSide] gutter on phone-width panes, or a grown inset that centres [maxContentWidth] once
 * the pane is wide enough to fit that column plus a gutter on each side.
 */
fun responsiveSidePadding(
    availableWidth: Dp,
    maxContentWidth: Dp = 600.dp,
    minSide: Dp = 20.dp,
): Dp = if (availableWidth > maxContentWidth + minSide * 2) {
    (availableWidth - maxContentWidth) / 2
} else {
    minSide
}
