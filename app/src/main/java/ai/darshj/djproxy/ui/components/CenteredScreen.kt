package ai.darshj.djproxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.adaptive.responsiveContentPadding

/**
 * The app's one "short/fixed content" scaffold — the vertical-centering counterpart to
 * [responsiveContentPadding]'s horizontal one. Fixes the defect where Home's hero cluster rode high
 * in a top-anchoring `LazyColumn` with a dead zone below it on tall phones / the Fold7 unfolded pane:
 * that content is a small fixed set of composables, not a growing list, so it should rest at the
 * viewport's true centre — until it doesn't fit, at which point it must scroll like anything else.
 *
 * The trick is one Compose idiom, not two code paths: `fillMaxSize().verticalScroll(...)` with a
 * `Center`-biased [verticalArrangement]. When the column's content is shorter than the viewport, the
 * arrangement centres it (scroll offset stays 0, nothing to scroll). When content grows past the
 * viewport (a validation-error card, expanded advisory chips, expanded disclosure), the scroll state
 * simply has somewhere to go and the column reads as top-anchored + scrollable — no manual "does it
 * fit" measurement, no jump cut between two layouts.
 *
 * NOT for real lists (Servers' saved/free/VPN-Gate rows stay in their own top-anchored
 * `LazyColumn` + [responsiveContentPadding] — see servers-tab's build notes): this is for Home and
 * Onboarding, where the content is a short, fixed cluster. The Splash hand-off is deliberately left
 * on its own hand-rolled `Box`/`Column` instead (see `SplashHandoff`'s own comment) — its content
 * never risks overflowing 600dp in the first place, and its touch-swallow + timed beat are delicate
 * enough that trading them for `verticalScroll`'s gesture handling isn't worth it for zero visible
 * benefit.
 */
@Composable
fun CenteredScreen(
    modifier: Modifier = Modifier,
    maxContentWidth: Dp = 600.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Center,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val pad = responsiveContentPadding(maxWidth, maxContentWidth = maxContentWidth)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(pad),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}
