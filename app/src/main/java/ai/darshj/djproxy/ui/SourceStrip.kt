package ai.darshj.djproxy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.MotionTokens

/**
 * TIER 1 SOURCE (§2): one adaptive row — Paste · Scan · Import — plus the Tor toggle. The three
 * source buttons are large rounded-square "big shape" containers with a tri-tone border; Tor is a
 * toggle chip. When Tor is ON the three sources are disabled with an honest one-liner, because a
 * Tor tunnel and a custom proxy are mutually exclusive in v4 (chaining is out of scope).
 */
@Composable
fun SourceStrip(
    onPaste: () -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onOpenServers: () -> Unit,
    torAvailable: Boolean,
    torEnabled: Boolean,
    torBootstrapPct: Int?,
    torActive: Boolean,
    onToggleTor: (Boolean) -> Unit,
    onTorInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourcesEnabled = !torEnabled

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Opens the manual host/port editor (ManualEditSheet, "Edit proxy"); label matches the sheet
            // it lands on. Copy-paste of a full proxy line lives on the Import sheet's Paste tab.
            SourceButton(
                icon = Icons.Filled.Edit,
                label = "Edit",
                enabled = sourcesEnabled,
                onClick = onPaste,
                modifier = Modifier.weight(1f),
            )
            SourceButton(
                icon = Icons.Filled.QrCodeScanner,
                label = "Scan",
                enabled = sourcesEnabled,
                onClick = onScan,
                modifier = Modifier.weight(1f),
            )
            SourceButton(
                icon = Icons.Filled.Download,
                label = "Import",
                enabled = sourcesEnabled,
                onClick = onImport,
                modifier = Modifier.weight(1f),
            )
        }

        // v6 (§5.1): the Servers entry — the vault of saved proxies + the free public list. Always
        // enabled (independent of Tor mode) so the user can browse/save even while a Tor session runs.
        ServersEntry(onClick = onOpenServers)

        if (torAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Route through Tor",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DjColors.TextSecondary,
                )
                TorToggleChip(
                    enabled = torEnabled,
                    bootstrapPct = torBootstrapPct,
                    active = torActive,
                    onToggle = onToggleTor,
                    onInfo = onTorInfo,
                )
            }
            AnimatedVisibility(visible = torEnabled, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    "Using Tor — turn off to use a custom proxy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DjColors.TorPurple,
                )
            }
        }
    }
}

/** A full-width entry into the Servers surface (saved vault + free public list). Glass row, chevron. */
@Composable
private fun ServersEntry(onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(DjColors.GlassFillStrong, DjColors.GlassFill)))
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(DjColors.AccentIndigo.copy(alpha = 0.45f), DjColors.AccentCyan.copy(alpha = 0.30f)),
                ),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Dns, contentDescription = null, tint = DjColors.AccentCyan, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Servers", style = MaterialTheme.typography.titleSmall, color = DjColors.TextPrimary)
            Text(
                "Saved proxies & free public servers",
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextSecondary,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = DjColors.TextSecondary,
        )
    }
}

/** A large expressive "big shape" source container: tri-tone border, icon, label, press morph. */
@Composable
private fun SourceButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, MotionTokens.SpatialSpring, label = "src-press")
    // shape morph on press: corners tighten as the container is pushed (expressive squircle -> rounder)
    val corner by animateDpAsState(if (pressed) 26.dp else 20.dp, label = "src-corner")
    val shape = RoundedCornerShape(corner)

    Column(
        modifier = modifier
            .scale(press)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(DjColors.GlassFillStrong, DjColors.GlassFill)))
            .border(
                1.dp,
                Brush.linearGradient(listOf(DjColors.AccentCyan.copy(alpha = 0.55f), DjColors.AccentIndigo.copy(alpha = 0.35f))),
                shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .height(76.dp)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = DjColors.AccentCyan, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = DjColors.TextPrimary)
    }
}
