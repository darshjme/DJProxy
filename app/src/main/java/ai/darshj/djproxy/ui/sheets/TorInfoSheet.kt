package ai.darshj.djproxy.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.tor.TorGateway
import ai.darshj.djproxy.tor.TorPhase
import ai.darshj.djproxy.ui.theme.DjColors

/**
 * TIER 2 TorInfo sheet (§8). Shows the live Tor state from the tor lane's `TorGateway.controller`:
 * bootstrap progress while building, then "Tor active — .onion enabled" once the circuit is up, with
 * the honest note that `.onion` sites can now be opened in Chrome (they route over the existing
 * MapDNS + SOCKS5 domain-CONNECT path). Read-only surface; the toggle itself lives in the source strip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorInfoSheet(onDismiss: () -> Unit) {
    val controller = TorGateway.controller
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DjColors.CharcoalRaised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(DjColors.TorPurple),
                )
                Text("Tor onion routing", style = MaterialTheme.typography.headlineSmall, color = DjColors.TextPrimary)
            }

            if (controller == null) {
                Text(
                    "Tor isn't available in this build.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DjColors.TextSecondary,
                )
                Spacer(Modifier.height(6.dp))
                return@Column
            }

            val active by controller.active.collectAsState()
            val progress by controller.bootstrapProgress.collectAsState()
            val phase by controller.phase.collectAsState()

            when {
                active -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(DjColors.TorPurple.copy(alpha = 0.14f))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Tor active — .onion enabled", style = MaterialTheme.typography.titleMedium, color = DjColors.TorPurple)
                        Text(
                            "Your whole device is routing through Tor. You can browse .onion sites in Chrome " +
                                "right now — they resolve through the tunnel's MapDNS + SOCKS5 path. Expect " +
                                "slower speeds; that's Tor, not DJProxy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = DjColors.TextSecondary,
                        )
                    }
                }
                phase == TorPhase.BOOTSTRAPPING -> {
                    Text(
                        "Building the Tor circuit… $progress%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DjColors.TextPrimary,
                    )
                    LinearProgressIndicator(
                        progress = { (progress.coerceIn(0, 100)) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = DjColors.TorPurple,
                        trackColor = DjColors.TorPurple.copy(alpha = 0.18f),
                    )
                    Text(
                        "Tor bootstraps a chain of relays before any traffic flows. This can take a few " +
                            "seconds to a minute on a slow network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DjColors.TextTertiary,
                    )
                }
                phase == TorPhase.FAILED -> {
                    // Honest capability: do NOT show a fake "0%" progress bar when nothing is bootstrapping.
                    Text(
                        "Tor couldn't start",
                        style = MaterialTheme.typography.titleMedium,
                        color = DjColors.Amber,
                    )
                    Text(
                        "The last attempt to build a circuit failed — usually no network, a blocked " +
                            "connection, or Tor being throttled. Turn the Tor toggle off and on to try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DjColors.TextSecondary,
                    )
                }
                else -> {
                    Text(
                        "Tor is off",
                        style = MaterialTheme.typography.titleMedium,
                        color = DjColors.TextPrimary,
                    )
                    Text(
                        "Enable “Route through Tor” from the source strip and tap the ring to build a " +
                            "circuit. Once it's up, your whole device — including .onion sites — routes " +
                            "through Tor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DjColors.TextTertiary,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
