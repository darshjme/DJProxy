package ai.darshj.djproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjAccentBrush
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.DjShapes
import ai.darshj.djproxy.ui.theme.DjSpacing
import ai.darshj.djproxy.ui.theme.MotionTokens

/**
 * The DJProxy Material-3-Expressive button hierarchy (§ui-center). Three families, one visual
 * language, so a screen never mixes stock Material `Button`/`OutlinedButton` chrome with the app's
 * glass/gradient aesthetic:
 *
 *   - [DjButton]        — filled, brand-gradient CTA. The ONE primary action per surface
 *                          (Onboarding's "Enable location matching" / "Continue", a future single
 *                          confirm action). `TextOnAccent` content colour for contrast on the fill.
 *   - [DjTonalButton]    — the glass-tonal utility button already proven by `SourceStrip`'s
 *                          `SourceButton` / `ServersEntry` (frosted fill, tri-tone hairline border,
 *                          corner press-morph between [DjShapes.SourceRestRadius] and
 *                          [DjShapes.SourcePressedRadius]) — generalised here so every secondary
 *                          utility action (Edit/Scan/Import, Servers entry, "Check all"/"Refresh")
 *                          shares one implementation instead of each screen hand-rolling its own.
 *   - [DjOutlineButton]  — bare hairline outline, no fill. Dismiss / secondary / "skip" actions.
 *
 * All three take a `content: @Composable RowScope -> Unit` slot (the same shape as Material's
 * `Button`/`OutlinedButton` API) so call sites can lay out icon-then-label, label-only, or a denser
 * icon+two-line-text row exactly as `SourceButton` and `ServersEntry` already do — the family
 * supplies shape/fill/border/press-feedback/content-colour, never a fixed internal layout.
 */
@Composable
fun DjButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = DjShapes.Button,
    contentPadding: PaddingValues = PaddingValues(horizontal = DjSpacing.xl, vertical = DjSpacing.md),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, MotionTokens.SpatialSpring, label = "dj-button-press")

    Row(
        modifier = modifier
            .scale(press)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(DjAccentBrush)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides DjColors.TextOnAccent) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

/**
 * Glass-tonal secondary/utility button — a named, reusable version of the container `SourceButton`
 * and `ServersEntry` already build by hand in `SourceStrip`. Corner radius animates between
 * [DjShapes.SourceRestRadius] (20dp) and [DjShapes.SourcePressedRadius] (26dp) on press, matching
 * the exact squish those two call sites already had — visual continuity, now named and shared.
 */
@Composable
fun DjTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = DjSpacing.lg, vertical = DjSpacing.md),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, MotionTokens.SpatialSpring, label = "dj-tonal-press")
    val corner by animateDpAsState(
        if (pressed) DjShapes.SourcePressedRadius else DjShapes.SourceRestRadius,
        label = "dj-tonal-corner",
    )
    val shape = RoundedCornerShape(corner)

    Row(
        modifier = modifier
            .scale(press)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(DjColors.GlassFillStrong, DjColors.GlassFill)))
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(DjColors.AccentCyan.copy(alpha = 0.55f), DjColors.AccentIndigo.copy(alpha = 0.35f)),
                ),
                shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(DjSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides DjColors.TextPrimary) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

/**
 * Bare hairline outline, no fill — dismiss / secondary / "skip" actions. [contentColor] defaults to
 * `TextSecondary` (the plain dismiss case) but is overridable — a deep-link/utility outline button
 * that wants to read as an accent affordance (e.g. Settings' "Open Developer options") can pass
 * `DjColors.AccentCyan` without needing a fourth button family just for colour.
 */
@Composable
fun DjOutlineButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = DjShapes.Button,
    contentColor: Color = DjColors.TextSecondary,
    contentPadding: PaddingValues = PaddingValues(horizontal = DjSpacing.lg, vertical = DjSpacing.md),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.97f else 1f, MotionTokens.SpatialSpring, label = "dj-outline-press")

    Row(
        modifier = modifier
            .scale(press)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .border(1.dp, DjColors.HairlineStrong, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}
