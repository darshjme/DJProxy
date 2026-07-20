package ai.darshj.djproxy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.MotionTokens

/**
 * The "Block ads" toggle chip in the source strip — a deliberate structural clone of [TorToggleChip],
 * simplified: ad-blocking has no bootstrap/circuit phase, so there is no percentage or breathing arc,
 * just off ↔ on. Off = a quiet outlined pill; on = an indigo-tinted filled pill with a solid shield
 * dot. Adblock composes WITH any proxy or Tor (it is host-level CONNECT filtering, not a route), so —
 * unlike the Tor row — this chip is NEVER disabled by another source being active.
 *
 * Primitive params only (no ViewModel coupling), so the source strip owns the wiring. Hidden entirely
 * by the caller when the adblock lane is absent (`AdblockGateway.controller == null`) — honest
 * capability, same rule as Tor/location.
 */
@Composable
fun AdblockToggleChip(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onInfo: (() -> Unit)? = null,
) {
    val borderColor by animateColorAsState(
        if (enabled) DjColors.AccentIndigo else DjColors.HairlineStrong,
        tween(MotionTokens.SCREEN_MS), label = "adblock-border",
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (enabled) {
                    Brush.horizontalGradient(
                        listOf(
                            DjColors.AccentIndigo.copy(alpha = 0.22f),
                            DjColors.AccentIndigoDeep.copy(alpha = 0.14f),
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
                .clip(CircleShape)
                .background(if (enabled) DjColors.AccentIndigo else DjColors.TextTertiary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (enabled) "Ads blocked" else "Block ads",
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) DjColors.TextPrimary else DjColors.TextSecondary,
        )
        if (onInfo != null) {
            AnimatedVisibility(visible = enabled, enter = fadeIn(), exit = fadeOut()) {
                // A real 40dp interactive box (IconButton enforces the 48dp minimum touch target)
                // separates the info affordance from the Switch so it can't be mis-tapped.
                IconButton(
                    onClick = onInfo,
                    modifier = Modifier.padding(start = 4.dp).size(40.dp),
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = "About ad-blocking",
                        tint = DjColors.AccentIndigo,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DjColors.AccentIndigo,
                checkedTrackColor = DjColors.AccentIndigoDeep,
                uncheckedThumbColor = DjColors.TextSecondary,
                uncheckedTrackColor = DjColors.GlassFillStrong,
            ),
        )
    }
}
