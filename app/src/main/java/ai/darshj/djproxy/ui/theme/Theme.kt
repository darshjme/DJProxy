package ai.darshj.djproxy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp

private val DjDarkColorScheme = darkColorScheme(
    primary = DjColors.AccentCyan,
    onPrimary = DjColors.TextOnAccent,
    primaryContainer = DjColors.AccentCyanDeep,
    onPrimaryContainer = DjColors.TextPrimary,
    secondary = DjColors.AccentIndigo,
    onSecondary = DjColors.TextPrimary,
    tertiary = DjColors.Emerald,
    onTertiary = DjColors.TextOnAccent,
    background = DjColors.VoidBlack,
    onBackground = DjColors.TextPrimary,
    surface = DjColors.Charcoal,
    onSurface = DjColors.TextPrimary,
    surfaceVariant = DjColors.Slate,
    onSurfaceVariant = DjColors.TextSecondary,
    outline = DjColors.HairlineStrong,
    outlineVariant = DjColors.HairlineLight,
    error = DjColors.Rose,
    onError = DjColors.TextOnAccent,
    errorContainer = DjColors.RoseDeep,
    onErrorContainer = DjColors.TextPrimary,
)

/** Full-bleed background wash behind every screen: a faint radial bloom of the brand gradient
 *  sinking into void-black, so glass surfaces have depth to refract against. */
val DjBackgroundBrush: Brush
    get() = Brush.radialGradient(
        colors = listOf(
            DjColors.AccentIndigo.copy(alpha = 0.10f),
            DjColors.Charcoal,
            DjColors.VoidBlack,
        ),
        radius = 1400f,
    )

/**
 * v4 expressive full-bleed wash, now animatable. [torTint] (0..1) lerps the inner brand stop
 * indigo -> Tor purple so the whole atmosphere drifts as Tor engages (§1.1). torTint = 0 reproduces
 * the v3 [DjBackgroundBrush] exactly, so nothing regresses for the non-Tor path.
 */
fun djBackgroundBrush(torTint: Float = 0f): Brush {
    val inner = lerp(
        DjColors.AccentIndigo.copy(alpha = 0.10f),
        DjColors.TorPurple.copy(alpha = 0.16f),
        torTint.coerceIn(0f, 1f),
    )
    return Brush.radialGradient(
        colors = listOf(inner, DjColors.Charcoal, DjColors.VoidBlack),
        radius = 1400f,
    )
}

/** The Tor-mode gradient (source strip chip fill, Tor lock, onion pill). */
val DjTorBrush: Brush
    get() = Brush.linearGradient(listOf(DjColors.TorPurple, DjColors.TorPurpleDeep))

/**
 * The signature ring sweep: a cyan -> violet -> indigo -> cyan tri-tone sweepGradient that reads as
 * a single continuous loop of light travelling around the connect ring.
 */
fun djBrandTriBrush(center: Offset): Brush = Brush.sweepGradient(
    0.0f to DjColors.AccentCyan,
    0.33f to DjColors.AccentViolet,
    0.66f to DjColors.AccentIndigo,
    1.0f to DjColors.AccentCyan,
    center = center,
)

/** The brand gradient used for the primary CTA, progress rings, and accent strokes. */
val DjAccentBrush: Brush
    get() = Brush.linearGradient(listOf(DjColors.AccentCyan, DjColors.AccentIndigo))

val DjEmeraldBrush: Brush
    get() = Brush.linearGradient(listOf(DjColors.Emerald, DjColors.EmeraldDeep))

val DjAmberBrush: Brush
    get() = Brush.linearGradient(listOf(DjColors.Amber, DjColors.AmberDeep))

val DjRoseBrush: Brush
    get() = Brush.linearGradient(listOf(DjColors.Rose, DjColors.RoseDeep))

@Composable
fun DJProxyTheme(
    // Product is dark-first by explicit requirement — DJProxy does not ship a light theme, so
    // the scheme is fixed regardless of system setting.
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DjDarkColorScheme,
        typography = DjTypography,
        content = content,
    )
}
