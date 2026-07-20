package ai.darshj.djproxy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.components.DjTonalButton
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.DjSpacing

/**
 * TIER 1 SOURCE (§2): one adaptive row — Paste · Scan · Import — plus the Tor toggle. The three
 * source buttons are large rounded-square "big shape" containers with a tri-tone border; Tor is a
 * toggle chip. When Tor is ON the three sources are disabled with an honest one-liner, because a
 * Tor tunnel and a custom proxy are mutually exclusive in v4 (chaining is out of scope).
 *
 * §ui-center: [SourceButton] and [ServersEntry] used to hand-roll their own glass fill / tri-tone
 * border / press-morph corner — now both are [DjTonalButton] call sites, so this screen's utility
 * actions share one implementation (and one press feel) with every other tonal button in the app
 * instead of drifting into their own bespoke chrome.
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
    adblockAvailable: Boolean = false,
    adblockEnabled: Boolean = false,
    onToggleAdblock: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sourcesEnabled = !torEnabled

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DjSpacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DjSpacing.md),
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

        // adblock lane (§Block-ads): an INDEPENDENT toggle row — deliberately NOT gated by
        // sourcesEnabled, because ad/tracker sinkholing composes WITH any proxy or Tor session (it is
        // never mutually exclusive with a source the way Tor is). Rendered only when the lane is
        // present (AdblockGateway.controller != null, threaded in as [adblockAvailable]).
        if (adblockAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Block ads & trackers",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DjColors.TextSecondary,
                )
                AdblockToggleChip(
                    enabled = adblockEnabled,
                    onToggle = onToggleAdblock,
                )
            }
        }
    }
}

/** A full-width entry into the Servers surface (saved vault + free public list). Glass row, chevron. */
@Composable
private fun ServersEntry(onClick: () -> Unit) {
    DjTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = DjSpacing.lg, vertical = DjSpacing.md),
    ) {
        Icon(Icons.Filled.Dns, contentDescription = null, tint = DjColors.AccentCyan, modifier = Modifier.size(22.dp))
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
    DjTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(76.dp),
        contentPadding = PaddingValues(vertical = DjSpacing.md),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DjSpacing.xs, Alignment.CenterVertically),
        ) {
            Icon(icon, contentDescription = null, tint = DjColors.AccentCyan, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = DjColors.TextPrimary)
        }
    }
}
