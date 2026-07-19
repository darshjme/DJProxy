package ai.darshj.djproxy.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.components.GlassSurface
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.vpn.LeakCheckReport
import ai.darshj.djproxy.vpn.TunnelStats
import ai.darshj.djproxy.vpn.VpnStage
import ai.darshj.djproxy.vpn.VpnState
import kotlinx.coroutines.delay

private fun dotColorFor(stage: VpnStage): Color = when (stage) {
    VpnStage.CONNECTED -> DjColors.Emerald
    VpnStage.RECONNECTING -> DjColors.Amber
    VpnStage.ERROR -> DjColors.Rose
    VpnStage.VALIDATING, VpnStage.CONNECTING, VpnStage.STOPPING -> DjColors.AccentCyan
    VpnStage.IDLE -> DjColors.TextTertiary
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format("%.1f %s", value, units[unitIndex])
}

private fun formatUptime(sinceMs: Long, nowMs: Long): String {
    if (sinceMs <= 0) return "—"
    val elapsed = ((nowMs - sinceMs) / 1000).coerceAtLeast(0)
    val h = elapsed / 3600
    val m = (elapsed % 3600) / 60
    val s = elapsed % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

/**
 * Live status card: stage badge, redacted proxy, ticking uptime, byte/connection counters, and
 * the leak self-test chips. Renders continuously off [VpnState] — nothing here owns state.
 */
@Composable
fun StatusCard(state: VpnState, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.stage, state.connectedSinceMs) {
        while (state.stage == VpnStage.CONNECTED || state.stage == VpnStage.RECONNECTING) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val dotColor by animateColorAsState(dotColorFor(state.stage), tween(300), label = "dot-color")

    GlassSurface(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Spacer(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.stage.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleMedium,
                    color = DjColors.TextPrimary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "· ${formatUptime(state.connectedSinceMs, now)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DjColors.TextTertiary,
                )
            }

            state.proxyRedacted?.let { redacted ->
                Text(
                    text = redacted,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DjColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            StatsGrid(state.stats)

            state.leakChecks?.let { report ->
                Spacer(Modifier.height(16.dp))
                LeakChips(report)
            }
        }
    }
}

@Composable
private fun StatsGrid(stats: TunnelStats) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        StatCell("Upload", formatBytes(stats.bytesUp))
        StatCell("Download", formatBytes(stats.bytesDown))
        StatCell("Active", stats.activeConnections.toString())
        StatCell("Total", stats.totalConnections.toString())
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, color = DjColors.TextPrimary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = DjColors.TextTertiary)
    }
}

@Composable
private fun LeakChips(report: LeakCheckReport) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        LeakChip("IPv6 blocked", report.ipv6Unreachable)
        LeakChip("UDP blocked", report.udpBlocked)
        LeakChip("DNS tunnelled", report.dnsTunnelled)
    }
}

@Composable
private fun LeakChip(label: String, pass: Boolean) {
    val color = if (pass) DjColors.Emerald else DjColors.Rose
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
