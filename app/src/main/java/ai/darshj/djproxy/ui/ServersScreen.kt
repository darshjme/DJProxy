package ai.darshj.djproxy.ui

import android.content.Context
import android.os.PowerManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.freeproxy.FreeProxyEntry
import ai.darshj.djproxy.store.ProxyOrigin
import ai.darshj.djproxy.store.ProxyStatus
import ai.darshj.djproxy.store.SavedProxy
import ai.darshj.djproxy.ui.adaptive.responsiveContentPadding
import ai.darshj.djproxy.ui.components.GlassSurface
import ai.darshj.djproxy.ui.components.ProxyRow
import ai.darshj.djproxy.ui.components.RowAction
import ai.darshj.djproxy.ui.components.RowBadge
import ai.darshj.djproxy.ui.components.relativeTime
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.DjShapes
import ai.darshj.djproxy.ui.theme.DjSpacing

/** The segments of the Servers surface (§5.2). [VpnGate] is only offered when the vpngate lane is present. */
private enum class ServersTab { Saved, Free, VpnGate }

/**
 * A single VPN Gate row's *display* data. The vpngate lane's `VpnGateServer`/`VpnGateGateway` are mapped
 * to this UI-local model at the ServersScreen call site (`ui/ProxyScreen.kt`), so this screen renders the
 * "VPN Gate" tab **without importing the vpngate package** — the coordination is through the Gateway at
 * the call site (SSOT §build_notes), never a cross-lane file reference. When the vpngate lane is absent
 * the call site passes [vpnGateAvailable] = false and an empty list, and the tab is hidden entirely.
 *
 * [directlyDialable] is true only for the rare row whose decoded `.ovpn` `config.OvpnParser.parse`
 * accepted as an embedded SOCKS/HTTP proxy; those get a Use/Save action. Every other row is OpenVPN-only
 * and offers Export/Share of the decoded profile to an external OpenVPN app — no fake tunnel.
 */
data class VpnGateRow(
    val id: String,
    val country: String,
    val host: String,
    val pingMs: Int?,
    val score: Long?,
    val speedMbps: Double?,
    val directlyDialable: Boolean,
)

/**
 * v6 (§5.2) — the Servers surface: a full route (not a sheet) hosting the proxy vault, the free public
 * list, and (when the vpngate lane is present) the VPN Gate catalog, in three segmented tabs, in the
 * same dark-glass Material 3 Expressive language and foldable-responsive via [responsiveContentPadding].
 * Spacing + shapes come from the shared [DjSpacing]/[DjShapes] tokens so the screen reads as one system
 * with Home/Settings.
 *
 * The **Saved** tab lists [savedProxies] with a live [StatusChip], a default pin, tap-to-reuse, and an
 * overflow menu (Check now / Edit / Set default / Move up-down / Delete) plus a "Check all". "Check now"
 * re-checks exactly one saved proxy — identical [StatusChip] semantics to the Free tab. The **Free
 * servers** tab shows the untrusted-server caveat, the fetched [free] list, a Refresh with **explicit**
 * loading / "Updated · N servers · <relative>" / inline error-card / empty states (driven by [freeState]),
 * and a one-tap consent gate before any free proxy is **checked, used, or saved** (§4.5). The **VPN Gate**
 * tab is an honest OpenVPN-server *browser*: it never claims to tunnel VPN Gate; it exports the decoded
 * `.ovpn` to an external OpenVPN app and only offers Use/Save for the rare directly-dialable row.
 *
 * Opening the Free tab populates the list from the local cache ONLY via [onLoadFreeCache] — it never
 * emits an outbound request. The network pull is reserved for the explicit Refresh ([onRefreshFree]).
 *
 * Battery (§3.3): an auto "check all" runs once on entering the Saved tab ONLY when not in battery
 * saver; the manual "Check all" is always allowed. The status checker itself bounds concurrency and
 * debounces, and no work happens off this visible screen.
 */
@Composable
fun ServersScreen(
    savedProxies: List<SavedProxy>,
    defaultId: String?,
    statuses: Map<String, ProxyStatus>,
    free: List<FreeProxyEntry>,
    freeBusy: Boolean,
    freeNote: String?,
    onBack: () -> Unit,
    onReuseSaved: (String) -> Unit,
    onEditSaved: (SavedProxy) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onSetDefault: (String?) -> Unit,
    onMoveSaved: (String, Boolean) -> Unit,
    onCheckSaved: (SavedProxy) -> Unit,
    onCheckAllSaved: (List<SavedProxy>) -> Unit,
    onCheckFree: (FreeProxyEntry) -> Unit,
    onCheckAllFree: (List<FreeProxyEntry>) -> Unit,
    onRefreshFree: (Boolean) -> Unit,
    onLoadFreeCache: () -> Unit,
    onSaveFree: (FreeProxyEntry) -> Unit,
    onApplyFree: (FreeProxyEntry) -> Unit,
    freeState: FreeRefreshState = FreeRefreshState.Idle,
    vpnGateAvailable: Boolean = false,
    vpnGate: List<VpnGateRow> = emptyList(),
    vpnGateBusy: Boolean = false,
    vpnGateError: String? = null,
    onRefreshVpnGate: () -> Unit = {},
    onUseVpnGate: (VpnGateRow) -> Unit = {},
    onSaveVpnGate: (VpnGateRow) -> Unit = {},
    onExportVpnGate: (VpnGateRow) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(ServersTab.Saved) }
    // The VPN Gate tab can disappear (lane absent / controller torn down) while it is the active tab —
    // never leave the user stranded on a hidden segment.
    val effectiveTab = if (tab == ServersTab.VpnGate && !vpnGateAvailable) ServersTab.Saved else tab

    val context = LocalContext.current
    val powerSave = remember {
        runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode ?: false
        }.getOrDefault(false)
    }

    // The one-time-per-visit untrusted-public-server consent gate (§4.5). Once accepted this session,
    // subsequent free/VPN-Gate uses/saves go straight through. Export/Share never opens a socket, so it
    // is not gated.
    var freeConsent by rememberSaveable { mutableStateOf(false) }
    var pendingFree by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Auto "check all" once the Saved list first arrives, unless the device is in battery saver.
    var autoCheckedSaved by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(savedProxies.size, powerSave) {
        if (!autoCheckedSaved && savedProxies.isNotEmpty()) {
            autoCheckedSaved = true
            if (!powerSave) onCheckAllSaved(savedProxies)
        }
    }
    // Populate the free list from the LOCAL CACHE ONLY the first time the Free tab is opened. This
    // never touches the network — a cold cache simply leaves the list empty until the user taps
    // Refresh, so merely viewing the tab emits zero outbound requests (§4.5, privacy).
    androidx.compose.runtime.LaunchedEffect(effectiveTab) {
        if (effectiveTab == ServersTab.Free && free.isEmpty() && !freeBusy) onLoadFreeCache()
    }

    fun gateFree(action: () -> Unit) {
        if (freeConsent) action() else pendingFree = action
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val pad = responsiveContentPadding(maxWidth)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = pad,
            verticalArrangement = Arrangement.spacedBy(DjSpacing.lg),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DjColors.TextPrimary)
                    }
                    Spacer(Modifier.width(DjSpacing.xs))
                    Text("Servers", style = MaterialTheme.typography.headlineSmall, color = DjColors.TextPrimary)
                }
            }

            item { SegmentedTabs(tab = effectiveTab, vpnGateAvailable = vpnGateAvailable, onSelect = { tab = it }) }

            when (effectiveTab) {
                ServersTab.Saved -> savedTab(
                    saved = savedProxies,
                    defaultId = defaultId,
                    statuses = statuses,
                    onReuse = onReuseSaved,
                    onEdit = onEditSaved,
                    onDelete = onDeleteSaved,
                    onSetDefault = onSetDefault,
                    onMove = onMoveSaved,
                    onCheck = onCheckSaved,
                    onCheckAll = { onCheckAllSaved(savedProxies) },
                )
                ServersTab.Free -> freeTab(
                    free = free,
                    busy = freeBusy,
                    note = freeNote,
                    state = freeState,
                    statuses = statuses,
                    onRefresh = { onRefreshFree(true) },
                    onCheck = { entry -> gateFree { onCheckFree(entry) } },
                    onCheckAll = { gateFree { onCheckAllFree(free) } },
                    onUse = { entry -> gateFree { onApplyFree(entry) } },
                    onSave = { entry -> gateFree { onSaveFree(entry) } },
                )
                ServersTab.VpnGate -> vpnGateTab(
                    rows = vpnGate,
                    busy = vpnGateBusy,
                    error = vpnGateError,
                    onRefresh = onRefreshVpnGate,
                    onUse = { row -> gateFree { onUseVpnGate(row) } },
                    onSave = { row -> gateFree { onSaveVpnGate(row) } },
                    onExport = onExportVpnGate,
                )
            }
        }
    }

    // The untrusted-public-server consent gate (§4.5) — mirrors the v4 import-consent gate.
    if (pendingFree != null) {
        AlertDialog(
            onDismissRequest = { pendingFree = null },
            containerColor = DjColors.CharcoalRaised,
            title = { Text("Use an untrusted public server?", color = DjColors.TextPrimary) },
            text = {
                Text(
                    "Free public proxies and VPN Gate servers are unvetted, community-listed endpoints. " +
                        "They can be slow, go offline, log your traffic, or inject content. Don't send " +
                        "sensitive data through them. For real privacy use your own proxy or Tor.",
                    color = DjColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    freeConsent = true
                    val action = pendingFree
                    pendingFree = null
                    action?.invoke()
                }) { Text("I understand — continue", color = DjColors.Amber) }
            },
            dismissButton = {
                TextButton(onClick = { pendingFree = null }) { Text("Cancel", color = DjColors.TextSecondary) }
            },
        )
    }
}

// ------------------------------------------------------------------------------------------------
// Saved tab
// ------------------------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.savedTab(
    saved: List<SavedProxy>,
    defaultId: String?,
    statuses: Map<String, ProxyStatus>,
    onReuse: (String) -> Unit,
    onEdit: (SavedProxy) -> Unit,
    onDelete: (String) -> Unit,
    onSetDefault: (String?) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onCheck: (SavedProxy) -> Unit,
    onCheckAll: () -> Unit,
) {
    if (saved.isEmpty()) {
        item {
            EmptyState(
                title = "No saved proxies yet",
                body = "Connect a proxy and tap \"Save to vault\" in the editor, or save one from the Free " +
                    "servers tab. Saved proxies appear here to reuse in one tap.",
            )
        }
        return
    }

    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${saved.size} saved",
                style = MaterialTheme.typography.labelLarge,
                color = DjColors.TextTertiary,
            )
            TextButton(onClick = onCheckAll) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = DjColors.AccentCyan)
                Spacer(Modifier.width(DjSpacing.sm))
                Text("Check all", color = DjColors.AccentCyan)
            }
        }
    }

    itemsIndexed(saved) { index, proxy ->
        val isDefault = proxy.id == defaultId
        val badges = buildList {
            if (isDefault) add(RowBadge("Default", DjColors.AccentCyan))
            if (proxy.origin == ProxyOrigin.FREE_PUBLIC) add(RowBadge("Public", DjColors.Amber))
        }
        ProxyRow(
            title = proxy.name,
            subtitle = redactedOf(proxy),
            status = statuses[proxy.id] ?: ProxyStatus.Unknown,
            primaryLabel = "Connect with ${proxy.name}",
            badges = badges,
            onPrimary = { onReuse(proxy.id) },
            onRecheck = { onCheck(proxy) },
            actions = buildList {
                // Explicit "Check now" parity with the Free tab: re-runs the same live pre-flight and
                // flips this row's StatusChip to Checking → Alive/Dead, without tapping the chip.
                add(RowAction("Check now", Icons.Filled.Refresh) { onCheck(proxy) })
                add(RowAction("Edit", Icons.Filled.Edit) { onEdit(proxy) })
                if (isDefault) {
                    add(RowAction("Clear default", Icons.Filled.StarBorder) { onSetDefault(null) })
                } else {
                    add(RowAction("Set as default", Icons.Filled.Star) { onSetDefault(proxy.id) })
                }
                if (index > 0) add(RowAction("Move up", Icons.Filled.ArrowUpward) { onMove(proxy.id, true) })
                if (index < saved.lastIndex) add(RowAction("Move down", Icons.Filled.ArrowDownward) { onMove(proxy.id, false) })
                add(RowAction("Delete", Icons.Filled.Delete, destructive = true) { onDelete(proxy.id) })
            },
        )
    }
}

// ------------------------------------------------------------------------------------------------
// Free servers tab
// ------------------------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.freeTab(
    free: List<FreeProxyEntry>,
    busy: Boolean,
    note: String?,
    state: FreeRefreshState,
    statuses: Map<String, ProxyStatus>,
    onRefresh: () -> Unit,
    onCheck: (FreeProxyEntry) -> Unit,
    onCheckAll: () -> Unit,
    onUse: (FreeProxyEntry) -> Unit,
    onSave: (FreeProxyEntry) -> Unit,
) {
    item { FreeCaveatCard() }

    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (free.isEmpty()) "No servers loaded" else "${free.size} public servers",
                style = MaterialTheme.typography.labelLarge,
                color = DjColors.TextTertiary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (free.isNotEmpty()) {
                    TextButton(onClick = onCheckAll) {
                        Text("Check all", color = DjColors.AccentCyan)
                    }
                }
                TextButton(onClick = onRefresh, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = DjColors.AccentCyan,
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = DjColors.AccentCyan)
                    }
                    Spacer(Modifier.width(DjSpacing.sm))
                    Text("Refresh", color = DjColors.AccentCyan)
                }
            }
        }
    }

    // Explicit refresh state — the fix for the "Refresh looks broken" report. A visible progression:
    // spinner-line → "Updated · N servers · <relative>" → inline error CARD, instead of a lone amber note.
    item { FreeRefreshBanner(state = state, note = note) }

    // Only fall back to the generic empty card when there is genuinely nothing AND no error is already
    // being surfaced (an error shows its own card above, so we must not paper over it with "No servers").
    if (free.isEmpty() && !busy && state !is FreeRefreshState.Error) {
        item {
            EmptyState(
                title = "No free servers yet",
                body = "Tap Refresh to pull a maintained public SOCKS5/HTTP list. These are untrusted " +
                    "community servers — use them only for non-sensitive traffic.",
            )
        }
        return
    }

    items(free, key = { it.key }) { entry ->
        ProxyRow(
            title = entry.host,
            subtitle = "${entry.type.scheme}://${entry.host}:${entry.port}  ·  ${entry.sourceLabel}",
            status = statuses[entry.key] ?: ProxyStatus.Unknown,
            primaryLabel = "Use ${entry.host} now",
            badges = listOf(RowBadge("Public", DjColors.Amber)),
            onPrimary = { onUse(entry) },
            onRecheck = { onCheck(entry) },
            actions = listOf(
                RowAction("Check now", Icons.Filled.Refresh) { onCheck(entry) },
                RowAction("Use now", Icons.Filled.Refresh) { onUse(entry) },
                RowAction("Save to vault", Icons.Filled.Save) { onSave(entry) },
            ),
        )
    }
}

// ------------------------------------------------------------------------------------------------
// VPN Gate tab (honest OpenVPN-server browser — NOT a tunnel)
// ------------------------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.vpnGateTab(
    rows: List<VpnGateRow>,
    busy: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onUse: (VpnGateRow) -> Unit,
    onSave: (VpnGateRow) -> Unit,
    onExport: (VpnGateRow) -> Unit,
) {
    item { VpnGateCaveatCard() }

    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (rows.isEmpty()) "No servers loaded" else "${rows.size} VPN Gate servers",
                style = MaterialTheme.typography.labelLarge,
                color = DjColors.TextTertiary,
            )
            TextButton(onClick = onRefresh, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = DjColors.AccentCyan,
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = DjColors.AccentCyan)
                }
                Spacer(Modifier.width(DjSpacing.sm))
                Text("Refresh", color = DjColors.AccentCyan)
            }
        }
    }

    error?.let { item { ErrorCard(title = "Couldn't refresh VPN Gate", body = it) } }

    if (rows.isEmpty() && !busy && error == null) {
        item {
            EmptyState(
                title = "No VPN Gate servers yet",
                body = "Tap Refresh to pull the public VPN Gate list. These publish OpenVPN profiles — " +
                    "export one and open it in an external OpenVPN app. DJProxy does not tunnel VPN Gate itself.",
            )
        }
        return
    }

    items(rows, key = { it.id }) { row ->
        VpnGateRowItem(row = row, onUse = onUse, onSave = onSave, onExport = onExport)
    }
}

@Composable
private fun VpnGateRowItem(
    row: VpnGateRow,
    onUse: (VpnGateRow) -> Unit,
    onSave: (VpnGateRow) -> Unit,
    onExport: (VpnGateRow) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DjSpacing.sm)) {
                Text(
                    row.country.ifBlank { "Unknown" },
                    style = MaterialTheme.typography.titleSmall,
                    color = DjColors.TextPrimary,
                )
                if (row.directlyDialable) MetricPill("Directly usable", DjColors.Emerald)
            }
            Text(
                row.host,
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier.padding(top = DjSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(DjSpacing.sm),
            ) {
                row.pingMs?.let { MetricPill("${it} ms", DjColors.AccentCyan) }
                row.score?.let { MetricPill("score $it", DjColors.AccentIndigo) }
                row.speedMbps?.let { MetricPill("${"%.1f".format(it)} Mbps", DjColors.Emerald) }
            }

            if (!row.directlyDialable) {
                Text(
                    "OpenVPN-only — open in an external OpenVPN app.",
                    style = MaterialTheme.typography.labelSmall,
                    color = DjColors.Amber,
                    modifier = Modifier.padding(top = DjSpacing.sm),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = DjSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(DjSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.directlyDialable) {
                    TextButton(onClick = { onUse(row) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = DjColors.AccentCyan)
                        Spacer(Modifier.width(DjSpacing.xs))
                        Text("Use now", color = DjColors.AccentCyan)
                    }
                    TextButton(onClick = { onSave(row) }) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp), tint = DjColors.AccentCyan)
                        Spacer(Modifier.width(DjSpacing.xs))
                        Text("Save", color = DjColors.AccentCyan)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onExport(row) }) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = DjColors.TextSecondary)
                    Spacer(Modifier.width(DjSpacing.xs))
                    Text("Export .ovpn", color = DjColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun VpnGateCaveatCard() {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        borderBrush = Brush.linearGradient(listOf(DjColors.Amber.copy(alpha = 0.55f), DjColors.AmberDeep.copy(alpha = 0.35f))),
        fill = Brush.verticalGradient(listOf(DjColors.Amber.copy(alpha = 0.12f), DjColors.Amber.copy(alpha = 0.04f))),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text("VPN Gate — OpenVPN servers", style = MaterialTheme.typography.titleSmall, color = DjColors.TextPrimary)
            Text(
                "DJProxy speaks SOCKS5/HTTP, not OpenVPN, so it can't tunnel these directly. Export a " +
                    "server's profile and open it in an external OpenVPN app. A few servers also expose a " +
                    "directly-usable proxy — those show a Use action. These are untrusted public servers.",
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Pieces
// ------------------------------------------------------------------------------------------------

@Composable
private fun SegmentedTabs(tab: ServersTab, vpnGateAvailable: Boolean, onSelect: (ServersTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DjShapes.TabShell)
            .background(DjColors.GlassFill)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Segment("Saved", tab == ServersTab.Saved, Modifier.weight(1f)) { onSelect(ServersTab.Saved) }
        Segment("Free servers", tab == ServersTab.Free, Modifier.weight(1f)) { onSelect(ServersTab.Free) }
        if (vpnGateAvailable) {
            Segment("VPN Gate", tab == ServersTab.VpnGate, Modifier.weight(1f)) { onSelect(ServersTab.VpnGate) }
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (selected) 1f else 0f, label = "seg-sel")
    Box(
        modifier = modifier
            .clip(DjShapes.Tab)
            .background(
                Brush.verticalGradient(
                    listOf(
                        DjColors.AccentCyan.copy(alpha = 0.22f * alpha),
                        DjColors.AccentIndigo.copy(alpha = 0.14f * alpha),
                    ),
                ),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) DjColors.TextPrimary else DjColors.TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * The explicit free-refresh confirmation strip. Renders the state driven by [state]:
 *  - [FreeRefreshState.Loading]  → an inline spinner + "Refreshing public list…".
 *  - [FreeRefreshState.Success]  → "Updated · N servers · <relative>" (emerald fresh / amber cached).
 *  - [FreeRefreshState.Error]    → an inline error CARD (not a lone bodySmall) with the honest reason.
 *  - [FreeRefreshState.Empty]    → nothing here (the generic empty card renders below).
 *  - [FreeRefreshState.Idle]     → legacy [note] fallback, so an un-wired call site never regresses.
 */
@Composable
private fun FreeRefreshBanner(state: FreeRefreshState, note: String?) {
    when (state) {
        FreeRefreshState.Loading -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DjSpacing.sm),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = DjColors.AccentCyan)
            Text("Refreshing public list…", style = MaterialTheme.typography.bodySmall, color = DjColors.TextSecondary)
        }

        is FreeRefreshState.Success -> Column(Modifier.fillMaxWidth()) {
            Text(
                "Updated · ${state.count} servers · ${relativeTime(state.fetchedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.fromCache) DjColors.Amber else DjColors.Emerald,
            )
            if (state.fromCache) {
                Text(
                    "Showing the last cached list — couldn't refresh right now.",
                    style = MaterialTheme.typography.labelSmall,
                    color = DjColors.TextTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        is FreeRefreshState.Error -> ErrorCard(
            title = "Couldn't refresh the public list",
            body = state.reason,
        )

        FreeRefreshState.Empty -> Unit

        FreeRefreshState.Idle -> note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = DjColors.Amber, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MetricPill(label: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(DjShapes.Pill)
            .background(accent.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = accent)
    }
}

@Composable
private fun FreeCaveatCard() {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        borderBrush = Brush.linearGradient(listOf(DjColors.Amber.copy(alpha = 0.55f), DjColors.AmberDeep.copy(alpha = 0.35f))),
        fill = Brush.verticalGradient(listOf(DjColors.Amber.copy(alpha = 0.12f), DjColors.Amber.copy(alpha = 0.04f))),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text("Untrusted public proxies", style = MaterialTheme.typography.titleSmall, color = DjColors.TextPrimary)
            Text(
                "These are unvetted, community-listed servers. They can be slow, go offline, log traffic, " +
                    "or inject content. Don't send sensitive data. For real privacy use your own proxy or Tor.",
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ErrorCard(title: String, body: String) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        borderBrush = Brush.linearGradient(listOf(DjColors.Rose.copy(alpha = 0.55f), DjColors.Rose.copy(alpha = 0.30f))),
        fill = Brush.verticalGradient(listOf(DjColors.Rose.copy(alpha = 0.12f), DjColors.Rose.copy(alpha = 0.04f))),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = DjColors.TextPrimary)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    GlassSurface(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = DjColors.TextPrimary)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** The redacted `type://[•••@]host:port` line for a saved proxy — mirrors [ProxyConfig.redacted]. */
private fun redactedOf(proxy: SavedProxy): String {
    val auth = if (proxy.username.isNotBlank() || proxy.hasPassword) "•••@" else ""
    return "${proxy.type.scheme}://$auth${proxy.host}:${proxy.port}"
}
