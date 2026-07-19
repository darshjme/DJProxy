package ai.darshj.djproxy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush

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
