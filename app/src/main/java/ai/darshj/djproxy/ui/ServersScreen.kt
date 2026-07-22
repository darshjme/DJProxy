package ai.darshj.djproxy.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import ai.darshj.djproxy.store.SavedOvpn
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
    // v2 country-grouping fields. Defaulted so the existing `ui/ProxyScreen.kt` mapping site keeps
    // compiling untouched; that site (owned by the orb-theme feature) should populate them from the
    // VpnGateServer (countryShort/countryLong/flagEmoji) — see the cross-feature note in the SSOT. Until
    // it does, [groupByCountry] falls back to the combined [country] string so grouping still works.
    val countryShort: String = "",
    val countryLong: String = "",
    val flag: String = "",
) {
    /** The grouping key: the ISO code when present, else the combined "flag country" display string. */
    val groupKey: String get() = countryShort.trim().ifBlank { country.trim() }

    /** Header label for this row's country group — prefers the split fields, else the combined string. */
    val groupLabel: String
        get() = "${flag.trim()} ${countryLong.trim()}".trim().ifBlank { country.trim() }.ifBlank { "Unknown" }
}

/**
 * One country section of the VPN Gate catalog (v2). Built purely by [groupByCountry] from the loaded
 * [VpnGateRow]s: rows sharing a [VpnGateRow.groupKey] collapse into a group carrying the section header
 * bits ([flag] + [countryLong]/[label]) plus the group's [bestPing] (min, ignoring unknown) and
 * [bestScore] (max) for the header pills. [rows] stay sorted score-desc / ping-asc within the group.
 */
data class VpnGateCountryGroup(
    val key: String,
    val label: String,
    val flag: String,
    val countryShort: String,
    val countryLong: String,
    val bestPing: Int?,
    val bestScore: Long?,
    val rows: List<VpnGateRow>,
)

/**
 * Pure, Compose-free grouping of the VPN Gate catalog by country (v2, testable in isolation). Rows are
 * bucketed by [VpnGateRow.groupKey]; each bucket's rows are sorted score-desc then ping-asc (unknown
 * ping last), and the buckets themselves are ordered best-score-desc then best-ping-asc — the SAME order
 * `VpnGateCsvParser` applies to the flat list (so the grouped view keeps the "best first" feel).
 */
private fun groupByCountry(rows: List<VpnGateRow>): List<VpnGateCountryGroup> {
    val rowComparator = compareByDescending<VpnGateRow> { it.score ?: Long.MIN_VALUE }
        .thenBy { it.pingMs ?: Int.MAX_VALUE }
    return rows
        .groupBy { it.groupKey }
        .map { (key, groupRows) ->
            val sorted = groupRows.sortedWith(rowComparator)
            val head = sorted.first()
            VpnGateCountryGroup(
                key = key,
                label = head.groupLabel,
                flag = head.flag,
                countryShort = head.countryShort,
                countryLong = head.countryLong.ifBlank { head.country },
                bestPing = groupRows.mapNotNull { it.pingMs }.filter { it >= 0 }.minOrNull(),
                bestScore = groupRows.mapNotNull { it.score }.maxOrNull(),
                rows = sorted,
            )
        }
        .sortedWith(
            compareByDescending<VpnGateCountryGroup> { it.bestScore ?: Long.MIN_VALUE }
                .thenBy { it.bestPing ?: Int.MAX_VALUE },
        )
}

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
    freeSweep: ai.darshj.djproxy.freeproxy.SweepProgress? = null,
    vpnGateAvailable: Boolean = false,
    vpnGate: List<VpnGateRow> = emptyList(),
    vpnGateBusy: Boolean = false,
    vpnGateError: String? = null,
    onRefreshVpnGate: () -> Unit = {},
    onUseVpnGate: (VpnGateRow) -> Unit = {},
    onSaveVpnGate: (VpnGateRow) -> Unit = {},
    onExportVpnGate: (VpnGateRow) -> Unit = {},
    // v2 VPN Gate profile hand-off + saved-profile vault. All defaulted so the `ui/ProxyScreen.kt` call
    // site (orb-theme's file) keeps compiling until the integrator wires them — see the SSOT cross-feature
    // note. connect==no-handler is surfaced by [vpnGateNoOpenVpnApp] (the ViewModel flips it), and the
    // guidance dialog below shows the install-an-OpenVPN-app copy.
    savedOvpn: List<SavedOvpn> = emptyList(),
    onConnectVpnGate: (VpnGateRow) -> Unit = {},
    onSaveOvpnProfile: (VpnGateRow) -> Unit = {},
    onConnectSavedOvpn: (String) -> Unit = {},
    onShareSavedOvpn: (String) -> Unit = {},
    onDeleteSavedOvpn: (String) -> Unit = {},
    vpnGateNoOpenVpnApp: Boolean = false,
    onDismissVpnGateNoOpenVpnApp: () -> Unit = {},
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

    // v2: the VPN Gate client-side country filter (null = "All"). Purely over already-loaded rows — no
    // refetch — so it survives config changes but never opens a socket.
    var vpnGateCountryFilter by rememberSaveable { mutableStateOf<String?>(null) }

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
                    sweep = freeSweep,
                    statuses = statuses,
                    onRefresh = { onRefreshFree(true) },
                    onCheck = { entry -> gateFree { onCheckFree(entry) } },
                    onCheckAll = { gateFree { onCheckAllFree(free) } },
                    onUse = { entry -> gateFree { onApplyFree(entry) } },
                    onSave = { entry -> gateFree { onSaveFree(entry) } },
                )
                ServersTab.VpnGate -> vpnGateTab(
                    rows = vpnGate,
                    savedOvpn = savedOvpn,
                    busy = vpnGateBusy,
                    error = vpnGateError,
                    countryFilter = vpnGateCountryFilter,
                    onCountryFilter = { vpnGateCountryFilter = it },
                    onRefresh = onRefreshVpnGate,
                    onUse = { row -> gateFree { onUseVpnGate(row) } },
                    onSave = { row -> gateFree { onSaveVpnGate(row) } },
                    // Connect / Share hand the .ovpn to an EXTERNAL OpenVPN app — no socket opens here,
                    // so they are NOT behind the untrusted-server consent gate (parity with Export/Share).
                    onConnect = onConnectVpnGate,
                    onSaveProfile = { row -> gateFree { onSaveOvpnProfile(row) } },
                    onShare = onExportVpnGate,
                    onConnectSaved = onConnectSavedOvpn,
                    onShareSaved = onShareSavedOvpn,
                    onDeleteSaved = onDeleteSavedOvpn,
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

    // v2: "no OpenVPN app installed" guidance — shown when a Connect hand-off found no external handler.
    // DJProxy does NOT tunnel OpenVPN, so the honest fix is to install an app that does; the two buttons
    // open the canonical OpenVPN clients on Google Play.
    if (vpnGateNoOpenVpnApp) {
        NoOpenVpnAppDialog(onDismiss = onDismissVpnGateNoOpenVpnApp)
    }
}

/** The install-an-OpenVPN-app dialog (v2). Opens the canonical clients on Play; honest-scope copy. */
@Composable
private fun NoOpenVpnAppDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun openPlay(pkg: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DjColors.CharcoalRaised,
        title = { Text("No OpenVPN app found", color = DjColors.TextPrimary) },
        text = {
            Text(
                "DJProxy speaks SOCKS5/HTTP, not OpenVPN — it hands the profile to an external OpenVPN " +
                    "app to connect. Install OpenVPN Connect or OpenVPN for Android, then tap Connect again.",
                color = DjColors.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = { openPlay("net.openvpn.openvpn") }) {
                Text("OpenVPN Connect", color = DjColors.AccentCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = { openPlay("de.blinkt.openvpn") }) {
                Text("OpenVPN for Android", color = DjColors.TextSecondary)
            }
        },
    )
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
    sweep: ai.darshj.djproxy.freeproxy.SweepProgress?,
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
                when {
                    sweep != null && sweep.inFlight ->
                        "${sweep.live} live · checking ${sweep.checked}/${sweep.total}"
                    free.isEmpty() -> "No servers loaded"
                    else -> "${free.size} live public servers"
                },
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
    if (free.isEmpty() && !busy && state !is FreeRefreshState.Error && (sweep == null || !sweep.inFlight)) {
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
            subtitle = buildString {
                append("${entry.type.scheme}://${entry.host}:${entry.port}  ·  ${entry.sourceLabel}")
                entry.latencyMs?.let { append("  ·  ${it} ms") }
            },
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
    savedOvpn: List<SavedOvpn>,
    busy: Boolean,
    error: String?,
    countryFilter: String?,
    onCountryFilter: (String?) -> Unit,
    onRefresh: () -> Unit,
    onUse: (VpnGateRow) -> Unit,
    onSave: (VpnGateRow) -> Unit,
    onConnect: (VpnGateRow) -> Unit,
    onSaveProfile: (VpnGateRow) -> Unit,
    onShare: (VpnGateRow) -> Unit,
    onConnectSaved: (String) -> Unit,
    onShareSaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
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
        // Even with no catalog rows, still surface any saved profiles so they aren't stranded.
        savedProfilesSection(savedOvpn, onConnectSaved, onShareSaved, onDeleteSaved)
        if (savedOvpn.isEmpty()) {
            item {
                EmptyState(
                    title = "No VPN Gate servers yet",
                    body = "Tap Refresh to pull the public VPN Gate list. Connect routes your device through a " +
                        "server using DJProxy's built-in OpenVPN engine (some ciphers aren't supported yet).",
                )
            }
        }
        return
    }

    // v2: group the loaded catalog by country. The filter (null = All) is purely client-side over the
    // already-loaded rows — never a refetch. A stale filter key that no longer matches degrades to All.
    val groups = groupByCountry(rows)
    val effectiveFilter = countryFilter?.takeIf { f -> groups.any { it.key == f } }
    val shownGroups = effectiveFilter?.let { f -> groups.filter { it.key == f } } ?: groups

    if (groups.size > 1) {
        item {
            VpnGateCountryFilterRow(
                groups = groups,
                selected = effectiveFilter,
                onSelect = onCountryFilter,
            )
        }
    }

    shownGroups.forEach { group ->
        item(key = "grp-${group.key}") { VpnGateGroupHeader(group) }
        items(group.rows, key = { it.id }) { row ->
            VpnGateRowItem(
                row = row,
                onUse = onUse,
                onSave = onSave,
                onConnect = onConnect,
                onSaveProfile = onSaveProfile,
                onShare = onShare,
            )
        }
    }

    savedProfilesSection(savedOvpn, onConnectSaved, onShareSaved, onDeleteSaved)
}

/** The 'Saved profiles' sub-section: reused for both the populated and empty-catalog states. */
private fun androidx.compose.foundation.lazy.LazyListScope.savedProfilesSection(
    savedOvpn: List<SavedOvpn>,
    onConnectSaved: (String) -> Unit,
    onShareSaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
) {
    if (savedOvpn.isEmpty()) return
    item(key = "saved-header") {
        Text(
            "Saved profiles · ${savedOvpn.size}",
            style = MaterialTheme.typography.labelLarge,
            color = DjColors.TextTertiary,
            modifier = Modifier.fillMaxWidth().padding(top = DjSpacing.sm),
        )
    }
    items(savedOvpn, key = { "saved-${it.id}" }) { profile ->
        SavedOvpnRowItem(
            profile = profile,
            onConnect = { onConnectSaved(profile.id) },
            onShare = { onShareSaved(profile.id) },
            onDelete = { onDeleteSaved(profile.id) },
        )
    }
}

@Composable
private fun VpnGateCountryFilterRow(
    groups: List<VpnGateCountryGroup>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        labelColor = DjColors.TextSecondary,
        selectedContainerColor = DjColors.AccentCyan.copy(alpha = 0.22f),
        selectedLabelColor = DjColors.TextPrimary,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DjSpacing.sm),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All") },
            colors = chipColors,
        )
        groups.forEach { group ->
            val label = "${group.flag} ${group.countryShort.ifBlank { group.countryLong }}".trim()
                .ifBlank { group.label }
            FilterChip(
                selected = selected == group.key,
                onClick = { onSelect(if (selected == group.key) null else group.key) },
                label = { Text(label, maxLines = 1) },
                colors = chipColors,
            )
        }
    }
}

@Composable
private fun VpnGateGroupHeader(group: VpnGateCountryGroup) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = DjSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DjSpacing.sm),
    ) {
        Text(
            group.label,
            style = MaterialTheme.typography.titleSmall,
            color = DjColors.TextPrimary,
        )
        MetricPill("${group.rows.size} servers", DjColors.AccentIndigo)
        Spacer(Modifier.weight(1f))
        group.bestPing?.let { MetricPill("best ${it} ms", DjColors.AccentCyan) }
        group.bestScore?.let { MetricPill("score $it", DjColors.Emerald) }
    }
}

@Composable
private fun VpnGateRowItem(
    row: VpnGateRow,
    onUse: (VpnGateRow) -> Unit,
    onSave: (VpnGateRow) -> Unit,
    onConnect: (VpnGateRow) -> Unit,
    onSaveProfile: (VpnGateRow) -> Unit,
    onShare: (VpnGateRow) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DjSpacing.sm)) {
                Text(
                    row.host,
                    style = MaterialTheme.typography.titleSmall,
                    color = DjColors.TextPrimary,
                )
                if (row.directlyDialable) MetricPill("Directly usable", DjColors.Emerald)
            }
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
                    "Connect routes your device through this server via DJProxy's engine. Share opens it in an external OpenVPN app.",
                    style = MaterialTheme.typography.labelSmall,
                    color = DjColors.Amber,
                    modifier = Modifier.padding(top = DjSpacing.sm),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = DjSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(DjSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.directlyDialable) {
                    // The rare row that embeds a SOCKS/HTTP proxy: keep the in-app Use + proxy-vault Save.
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
                } else {
                    // OpenVPN-only: primary Connect (hand to external app) + Save profile to the .ovpn vault.
                    TextButton(onClick = { onConnect(row) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp), tint = DjColors.AccentCyan)
                        Spacer(Modifier.width(DjSpacing.xs))
                        Text("Connect", color = DjColors.AccentCyan)
                    }
                    TextButton(onClick = { onSaveProfile(row) }) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp), tint = DjColors.AccentCyan)
                        Spacer(Modifier.width(DjSpacing.xs))
                        Text("Save profile", color = DjColors.AccentCyan)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onShare(row) }) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = DjColors.TextSecondary)
                    Spacer(Modifier.width(DjSpacing.xs))
                    Text("Share", color = DjColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun SavedOvpnRowItem(
    profile: SavedOvpn,
    onConnect: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DjSpacing.sm)) {
                Text(
                    "${profile.flagEmoji} ${profile.name}".trim(),
                    style = MaterialTheme.typography.titleSmall,
                    color = DjColors.TextPrimary,
                )
            }
            Text(
                "${profile.countryLong.ifBlank { "Unknown" }}  ·  ${profile.hostName}",
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = DjSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(DjSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onConnect) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp), tint = DjColors.AccentCyan)
                    Spacer(Modifier.width(DjSpacing.xs))
                    Text("Connect", color = DjColors.AccentCyan)
                }
                TextButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = DjColors.TextSecondary)
                    Spacer(Modifier.width(DjSpacing.xs))
                    Text("Share", color = DjColors.TextSecondary)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = DjColors.Rose)
                    Spacer(Modifier.width(DjSpacing.xs))
                    Text("Delete", color = DjColors.Rose)
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
                "Tap Connect to route your whole device through one of these OpenVPN servers using DJProxy's " +
                    "built-in engine. Some servers use a cipher the engine can't do yet — you'll get a " +
                    "\"try another server\" message, not a hang. Save keeps a profile for later; Share hands it " +
                    "to an external OpenVPN app as a fallback. These are untrusted public servers.",
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
