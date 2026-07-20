package ai.darshj.djproxy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.components.GlassSurface
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.MotionTokens
import ai.darshj.djproxy.vpn.LogEvent
import ai.darshj.djproxy.vpn.VpnState

/**
 * TIER 0 collapsed "Details" disclosure (§2). Home at rest shows only the hero + a single line of
 * truth; the full [StatusCard] (with animated counters) and the [LogView] live in here, tucked away
 * so the connect surface stays calm. Expanding rotates the chevron and reveals both with a spring.
 */
@Composable
fun DetailsDisclosure(
    state: VpnState,
    logs: List<LogEvent>,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(MotionTokens.SCREEN_MS), label = "chevron")

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (expanded) "Hide details" else "Details, stats & live log",
                style = MaterialTheme.typography.titleSmall,
                color = DjColors.TextSecondary,
            )
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse details" else "Expand details",
                tint = DjColors.TextSecondary,
                modifier = Modifier.rotate(chevron),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(MotionTokens.SCREEN_MS)) + expandVertically(tween(MotionTokens.SCREEN_MS)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // showChips=false: the advisory chips already render at the Home hero, so keep them out
                // of this embedded card to avoid a duplicate chip row on one scroll.
                StatusCard(state = state, modifier = Modifier.fillMaxWidth(), showChips = false)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Live log",
                        style = MaterialTheme.typography.titleMedium,
                        color = DjColors.TextPrimary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 320.dp),
                        contentPadding = 0.dp,
                    ) {
                        LogView(events = logs, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
