package ai.darshj.djproxy.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjAccentBrush
import ai.darshj.djproxy.ui.theme.DjAmberBrush
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.DjEmeraldBrush
import ai.darshj.djproxy.ui.theme.DjRoseBrush
import ai.darshj.djproxy.vpn.VpnStage

/** Visual + copy for each stage; kept in one place so button and status card agree. */
private fun brushFor(stage: VpnStage): Brush = when (stage) {
    VpnStage.CONNECTED -> DjEmeraldBrush
    VpnStage.RECONNECTING -> DjAmberBrush
    VpnStage.ERROR -> DjRoseBrush
    else -> DjAccentBrush
}

/** Human label for the current stage — used by both the button's a11y description and [StageLabel]. */
fun stageLabel(stage: VpnStage): String = when (stage) {
    VpnStage.IDLE -> "Connect"
    VpnStage.VALIDATING -> "Validating…"
    VpnStage.CONNECTING -> "Connecting…"
    VpnStage.CONNECTED -> "Connected — tap to stop"
    VpnStage.RECONNECTING -> "Reconnecting…"
    VpnStage.STOPPING -> "Disconnecting…"
    VpnStage.ERROR -> "Failed — tap to retry"
}

/**
 * The single primary CTA. Its gradient, pulse, and label all derive from [stage] so state and
 * visuals can never drift apart. Tapping while [VpnStage.CONNECTED] stops the tunnel; tapping
 * while idle/error re-validates and (re)applies. Disabled (but still animated) while busy.
 */
@Composable
fun ConnectButton(
    stage: VpnStage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = stage == VpnStage.VALIDATING || stage == VpnStage.CONNECTING || stage == VpnStage.STOPPING
    val pulseActive = stage == VpnStage.CONNECTED || stage == VpnStage.RECONNECTING
    val infinite = rememberInfiniteTransition(label = "connect-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val brush = brushFor(stage)
    val interactionSource = remember { MutableInteractionSource() }

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        // Outer glow ring — only breathes while connected/reconnecting.
        if (pulseActive) {
            Box(
                modifier = Modifier
                    .size(148.dp)
                    .scale(1f + pulse * 0.12f)
                    .alpha(0.28f * (1f - pulse * 0.6f))
                    .clip(CircleShape)
                    .background(brush),
            )
        }
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(brush)
                .then(if (busy) Modifier.alpha(0.7f + 0.2f * pulse) else Modifier)
                .border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = !busy,
                    onClick = onClick,
                )
                .semantics { contentDescription = stageLabel(stage) },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = stage,
                label = "connect-icon",
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.85f)) togetherWith fadeOut(tween(150))
                },
            ) { s ->
                when (s) {
                    VpnStage.VALIDATING, VpnStage.CONNECTING, VpnStage.STOPPING ->
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = DjColors.TextOnAccent,
                            strokeWidth = 3.dp,
                        )
                    VpnStage.CONNECTED -> PowerGlyph(connected = true)
                    VpnStage.ERROR -> PowerGlyph(connected = false, error = true)
                    else -> PowerGlyph(connected = false)
                }
            }
        }
    }
}

/** A minimal hand-drawn power glyph so this component has no dependency on any particular icon set. */
@Composable
private fun PowerGlyph(connected: Boolean, error: Boolean = false) {
    val color = DjColors.TextOnAccent
    Row(
        modifier = Modifier.size(width = 28.dp, height = 28.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(if (connected) 18.dp else if (error) 14.dp else 16.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
    }
}

/** Small text label under the button restating the stage in words — color alone never carries
 *  meaning here; the words always do too. */
@Composable
fun StageLabel(stage: VpnStage, modifier: Modifier = Modifier) {
    Text(
        text = stageLabel(stage),
        style = MaterialTheme.typography.titleSmall,
        color = DjColors.TextPrimary,
        modifier = modifier.padding(top = 12.dp),
    )
}
