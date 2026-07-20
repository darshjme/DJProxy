package ai.darshj.djproxy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.MotionTokens
import ai.darshj.djproxy.ui.theme.rememberAnimationsEnabled

/**
 * The Tor toggle chip in the source strip (§2). Off = a quiet outlined pill. On = a Tor-purple
 * filled pill that shows live bootstrap % while the circuit builds, then "Tor active" with a
 * breathing onion dot. Tapping the info glyph opens the TorInfo sheet. Hidden entirely by the caller
 * when the Tor lane is absent (TorGateway.controller == null) — honest capability, same rule as
 * location/hotspot.
 */
@Composable
fun TorToggleChip(
    enabled: Boolean,
    bootstrapPct: Int?,
    active: Boolean,
    onToggle: (Boolean) -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animate = rememberAnimationsEnabled()
    val infinite = rememberInfiniteTransition(label = "tor-chip")
    val breath = if (animate && active) {
        infinite.animateFloat(
            initialValue = 0.5f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(MotionTokens.BREATH_CONN_MS, easing = MotionTokens.BreathEasing),
                RepeatMode.Reverse,
            ),
            label = "tor-breath",
        ).value
    } else 1f

    val borderColor by animateColorAsState(
        if (enabled) DjColors.TorPurple else DjColors.HairlineStrong,
        tween(MotionTokens.TOR_TINT_MS), label = "tor-border",
    )

    val label = when {
        active -> "Tor active"
        enabled && bootstrapPct != null -> "Building circuit… $bootstrapPct%"
        enabled -> "Tor on"
        else -> "Tor"
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (enabled) {
                    Brush.horizontalGradient(
                        listOf(
                            DjColors.TorPurple.copy(alpha = 0.22f),
                            DjColors.TorPurpleDeep.copy(alpha = 0.14f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(listOf(DjColors.GlassFill, DjColors.GlassFill))
                },
            )
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .size(8.dp)
                .alpha(if (active) breath else 1f)
                .clip(CircleShape)
                .background(if (enabled) DjColors.TorPurple else DjColors.TextTertiary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) DjColors.TextPrimary else DjColors.TextSecondary,
        )
        AnimatedVisibility(visible = enabled, enter = fadeIn(), exit = fadeOut()) {
            // A real 40dp interactive box (IconButton also enforces the 48dp minimum touch target)
            // separates the info affordance from the Switch so it can't be mis-tapped.
            IconButton(
                onClick = onInfo,
                modifier = Modifier.padding(start = 4.dp).size(40.dp),
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "About Tor",
                    tint = DjColors.TorPurple,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DjColors.TorPurple,
                checkedTrackColor = DjColors.TorPurpleDeep,
                uncheckedThumbColor = DjColors.TextSecondary,
                uncheckedTrackColor = DjColors.GlassFillStrong,
            ),
        )
    }
}
