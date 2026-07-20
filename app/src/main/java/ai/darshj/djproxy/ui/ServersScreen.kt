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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
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
import ai.darshj.djproxy.ui.theme.DjColors

/** The two segments of the Servers surface (§5.2). */
private enum class ServersTab { Saved, Free }

/**
 * v6 (§5.2) — the Servers surface: a full route (not a sheet) hosting the proxy vault and the free
 * public list in two segmented tabs, in the same dark-glass Material 3 Expressive language and
 * foldable-responsive via [responsiveContentPadding].
 *
 * The **Saved** tab lists [savedProxies] with a live [StatusChip], a default pin, tap-to-reuse, and an
 * overflow menu (Edit / Set default / Move up-down / Delete) plus a "Check all". The **Free servers**
 * tab shows the untrusted-server caveat, the fetched [free] list, a Refresh, and a one-tap consent
 * gate before any free proxy is **checked, used, or saved** — i.e. before the device ever opens a
 * socket to an untrusted public server (§4.5). Testing reachability itself performs a live handshake
 * through the untrusted proxy, so it sits behind the same gate as Use/Save.
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
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(ServersTab.Saved) }
    val context = LocalContext.current
    val powerSave = remember {
        runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode ?: false
        }.getOrDefault(false)
    }

    // The one-time-per-visit untrusted-free-server consent gate (§4.5). Once accepted this session,
    // subsequent free uses/saves go straight through.
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
    androidx.compose.runtime.LaunchedEffect(tab) {
        if (tab == ServersTab.Free && free.isEmpty() && !freeBusy) onLoadFreeCache()
    }

    fun gateFree(action: () -> Unit) {
        if (freeConsent) action() else pendingFree = action
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val pad = responsiveContentPadding(maxWidth)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = pad,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DjColors.TextPrimary)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Servers", style = MaterialTheme.typography.headlineSmall, color = DjColors.TextPrimary)
                }
            }

            item { SegmentedTabs(tab = tab, onSelect = { tab = it }) }

            when (tab) {
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
                    statuses = statuses,
                    onRefresh = { onRefreshFree(true) },
                    onCheck = { entry -> gateFree { onCheckFree(entry) } },
                    onCheckAll = { gateFree { onCheckAllFree(free) } },
                    onUse = { entry -> gateFree { onApplyFree(entry) } },
                    onSave = { entry -> gateFree { onSaveFree(entry) } },
                )
            }
        }
    }

    // The untrusted-public-server consent gate (§4.5) — mirrors the v4 import-consent gate.
    if (pendingFree != null) {
        AlertDialog(
            onDismissRequest = { pendingFree = null },
            containerColor = DjColors.CharcoalRaised,
            title = { Text("Use an untrusted public proxy?", color = DjColors.TextPrimary) },
            text = {
                Text(
                    "Free public proxies are unvetted, community-listed servers. They can be slow, go " +
                        "offline, log your traffic, or inject content. Don't send sensitive data through " +
                        "them. For real privacy use your own proxy or Tor.",
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
                Spacer(Modifier.width(6.dp))
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
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh", color = DjColors.AccentCyan)
                }
            }
        }
    }

    note?.let {
        item {
            Text(it, style = MaterialTheme.typography.bodySmall, color = DjColors.Amber, modifier = Modifier.fillMaxWidth())
        }
    }

    if (free.isEmpty() && !busy) {
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
                RowAction("Use now", Icons.Filled.Refresh) { onUse(entry) },
                RowAction("Save to vault", Icons.Filled.Save) { onSave(entry) },
            ),
        )
    }
}

// ------------------------------------------------------------------------------------------------
// Pieces
// ------------------------------------------------------------------------------------------------

@Composable
private fun SegmentedTabs(tab: ServersTab, onSelect: (ServersTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DjColors.GlassFill)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Segment("Saved", tab == ServersTab.Saved, Modifier.weight(1f)) { onSelect(ServersTab.Saved) }
        Segment("Free servers", tab == ServersTab.Free, Modifier.weight(1f)) { onSelect(ServersTab.Free) }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (selected) 1f else 0f, label = "seg-sel")
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        DjColors.AccentCyan.copy(alpha = 0.22f * alpha),
                        DjColors.AccentIndigo.copy(alpha = 0.14f * alpha),
                    ),
                ),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) DjColors.TextPrimary else DjColors.TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
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
