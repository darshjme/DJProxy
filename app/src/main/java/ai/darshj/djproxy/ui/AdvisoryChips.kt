package ai.darshj.djproxy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.vpn.Health
import ai.darshj.djproxy.vpn.HealthReport

/**
 * Non-blocking advisory health chips (fixes v2 failure #1 — leak checks used to hard-gate
 * CONNECTED with scary red UI even when the tunnel genuinely worked). These render whatever
 * [HealthReport] core last published; they never imply the tunnel is down, and there is
 * deliberately no "all pass" gate anywhere in this file — a chip going amber never disables the
 * Connect/Stop button or hides the status card.
 *
 * Colour language: cyan = healthy/expected, amber = degraded (advisory only), grey = not yet
 * checked. Every chip also carries a state word ("OK"/"degraded"/"checking"/"alert") appended to its
 * label and merged into a single accessibility description, never colour alone.
 */
/**
 * ui-lane entry-animation wrapper (§1.6): the advisory chips cascade in (fade + scale-up) the moment
 * core publishes a [HealthReport], and animate out when it clears. The underlying [AdvisoryChipsRow]
 * is reused verbatim — only its appearance is wrapped here, so the advisory/blocking wall stays
 * intact and this never becomes a gate.
 */
@Composable
fun AnimatedAdvisoryChips(health: HealthReport?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = health != null,
        enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.85f, animationSpec = tween(300)),
        exit = fadeOut(tween(150)),
    ) {
        AdvisoryChipsRow(health = health, modifier = modifier)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdvisoryChipsRow(health: HealthReport?, modifier: Modifier = Modifier) {
    if (health == null) {
        Text(
            "Running the first health check…",
            style = MaterialTheme.typography.bodySmall,
            color = DjColors.TextTertiary,
            modifier = modifier,
        )
        return
    }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HealthChip(label = "IPv6 blocked", state = health.ipv6)
        HealthChip(label = "UDP blocked", state = health.udp)
        HealthChip(
            label = if (health.activeDnsStrategy.isNotBlank()) "DNS via ${health.activeDnsStrategy}" else "DNS via proxy",
            state = health.dns,
        )
        if (health.emulatorBypassSuspected) {
            HealthChip(label = "Emulator may bypass VPN", state = Health.DEGRADED, forceColor = DjColors.Rose)
        }
    }
}

@Composable
private fun HealthChip(label: String, state: Health, forceColor: Color? = null) {
    val color = forceColor ?: when (state) {
        Health.OK -> DjColors.AccentCyan
        Health.DEGRADED -> DjColors.Amber
        Health.UNKNOWN -> DjColors.TextTertiary
    }
    val stateWord = if (forceColor != null) {
        "alert"
    } else {
        when (state) {
            Health.OK -> "OK"
            Health.DEGRADED -> "degraded"
            Health.UNKNOWN -> "checking"
        }
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $stateWord" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text("$label · $stateWord", style = MaterialTheme.typography.labelMedium, color = color)
    }
}
