package ai.darshj.djproxy.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.darshj.djproxy.tor.TorGateway
import ai.darshj.djproxy.ui.adaptive.responsiveContentPadding
import ai.darshj.djproxy.ui.components.CenteredScreen
import ai.darshj.djproxy.ui.components.DjMonogram
import ai.darshj.djproxy.ui.components.ObsidianOrb
import ai.darshj.djproxy.ui.components.GlassSurface
import ai.darshj.djproxy.ui.components.StageLabel
import ai.darshj.djproxy.ui.sheets.ImportSheet
import ai.darshj.djproxy.ui.sheets.ManualEditSheet
import ai.darshj.djproxy.ui.sheets.SaveProxySheet
import ai.darshj.djproxy.ui.sheets.ScanSheet
import ai.darshj.djproxy.ui.sheets.ShareLanSheet
import ai.darshj.djproxy.ui.sheets.TorInfoSheet
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.DjSpacing
import ai.darshj.djproxy.ui.theme.MotionTokens
import ai.darshj.djproxy.ui.theme.djBackgroundBrush
import ai.darshj.djproxy.ui.theme.rememberAnimationsEnabled
import ai.darshj.djproxy.vpn.FeatureRegistry
import ai.darshj.djproxy.vpn.VpnStage
import ai.darshj.djproxy.vpn.VpnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v4 composition root (§2). Owns the sealed [Route] (Home / Settings / About), the Home
 * [HomeSheet], the animated Tor atmosphere, and every screen transition. Onboarding stays a pre-Home
 * gate exactly as v3. Reads all lane seams (Tor / hotspot / location via FeatureRegistry + qr /
 * config via the view model) — writes none of them.
 */
@Composable
fun ProxyScreen(viewModel: ProxyViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val controllerReady by viewModel.controllerReady.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val torMode by viewModel.torMode.collectAsStateWithLifecycle()
    val torBootstrap by viewModel.torBootstrap.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    val importChoices by viewModel.importChoices.collectAsStateWithLifecycle()
    val importBusy by viewModel.importBusy.collectAsStateWithLifecycle()
    // v6 vault / status / free-list state.
    val savedProxies by viewModel.savedProxies.collectAsStateWithLifecycle()
    val defaultId by viewModel.defaultId.collectAsStateWithLifecycle()
    val proxyStatuses by viewModel.proxyStatuses.collectAsStateWithLifecycle()
    val freeProxies by viewModel.freeProxies.collectAsStateWithLifecycle()
    val freeProxyBusy by viewModel.freeProxyBusy.collectAsStateWithLifecycle()
    val freeProxyNote by viewModel.freeProxyNote.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    // v6 explicit free-refresh state (spinner -> "Updated · N · <relative>" -> inline error) + adblock.
    val freeRefreshState by viewModel.freeRefreshState.collectAsStateWithLifecycle()
    val blockAdsMode by viewModel.blockAdsMode.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    // VPN Gate catalog (vpngate lane): read via its Gateway holder — null when the lane is absent, which
    // hides the whole VPN Gate tab. Falls back to inert empty/idle flows so the reads stay valid either
    // way. The Registrar publishes this at process start, so in a full build the controller is present.
    val vpnGateCtl = ai.darshj.djproxy.vpngate.VpnGateGateway.controller
    val vpnGateServers by (
        vpnGateCtl?.servers
            ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<ai.darshj.djproxy.vpngate.VpnGateServer>()) }
        ).collectAsStateWithLifecycle()
    val vpnGateState by (
        vpnGateCtl?.refreshState
            ?: remember {
                kotlinx.coroutines.flow.MutableStateFlow<ai.darshj.djproxy.vpngate.VpnGateRefreshState>(
                    ai.darshj.djproxy.vpngate.VpnGateRefreshState.Idle,
                )
            }
        ).collectAsStateWithLifecycle()
    // Populate the VPN Gate list from the local cache on first composition — no outbound tap (parity
    // with the free tab's on-entry cache load); the network pull stays behind the explicit Refresh.
    LaunchedEffect(vpnGateCtl) { vpnGateCtl?.loadCache() }
    // v2 VPN Gate: saved-.ovpn vault + the "no OpenVPN app installed" one-shot guidance flag.
    val savedOvpn by viewModel.savedOvpn.collectAsStateWithLifecycle()
    val vpnGateNoOpenVpnApp by viewModel.vpnGateNoOpenVpnApp.collectAsStateWithLifecycle()

    var route by rememberSaveable(stateSaver = RouteSaver) { mutableStateOf<Route>(Route.Home) }
    var sheet by rememberSaveable { mutableStateOf(HomeSheet.None) }
    // When the SaveProxy sheet is saving a FREE_PUBLIC entry (vs. the current editor config), the
    // pending entry is held here so the sheet can seed its name + carry the public origin.
    var pendingFreeSave by remember { mutableStateOf<ai.darshj.djproxy.freeproxy.FreeProxyEntry?>(null) }

    val onboardingContext = androidx.compose.ui.platform.LocalContext.current
    var showOnboarding by rememberSaveable { mutableStateOf(OnboardingState.shouldShow(onboardingContext)) }

    // Tor availability is honest capability: hidden entirely when the lane is absent.
    var torAvailable by remember { mutableStateOf(TorGateway.controller != null) }
    var diagnosticsAvailable by remember { mutableStateOf(FeatureRegistry.criticalFailureSink != null) }
    // adblock capability is honest, same rule as Tor: the Block-ads chip is hidden until the lane attaches.
    var adblockAvailable by remember { mutableStateOf(ai.darshj.djproxy.adblock.AdblockGateway.controller != null) }
    LaunchedEffect(Unit) {
        while (true) {
            torAvailable = TorGateway.controller != null
            diagnosticsAvailable = FeatureRegistry.criticalFailureSink != null
            adblockAvailable = ai.darshj.djproxy.adblock.AdblockGateway.controller != null
            delay(2000)
        }
    }

    // A parsed external import (deep link / share / .ovpn / paste) raises the Import sheet so the user
    // can review the target before the one confirmation tap — never a silent auto-connect (§11).
    LaunchedEffect(importPreview, importChoices) {
        if (importPreview != null || importChoices != null) {
            route = Route.Home
            sheet = HomeSheet.Import
        }
    }

    // Tor atmosphere: the whole background drifts indigo -> Tor purple while Tor is engaged (§1.1, §8).
    val torActive = torMode && vpnState.stage == VpnStage.CONNECTED
    val torTint by animateFloatAsState(
        targetValue = if (torMode || torBootstrap != null) 1f else 0f,
        animationSpec = tween(MotionTokens.TOR_TINT_MS),
        label = "tor-tint",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(djBackgroundBrush(torTint)),
    ) {
        if (showOnboarding) {
            OnboardingSheet(onFinish = {
                OnboardingState.markSeen(onboardingContext)
                showOnboarding = false
            })
            return@Box
        }

        AnimatedContent(
            targetState = route,
            label = "route",
            transitionSpec = {
                val forward = targetState != Route.Home
                (slideInHorizontally(tween(MotionTokens.SCREEN_MS)) { w -> if (forward) w else -w } +
                    fadeIn(tween(MotionTokens.SCREEN_MS))) togetherWith
                    fadeOut(tween(MotionTokens.SCREEN_MS))
            },
        ) { r ->
            when (r) {
                Route.Home -> HomeContent(
                    viewModel = viewModel,
                    ui = ui,
                    vpnState = vpnState,
                    controllerReady = controllerReady,
                    logs = logs,
                    torMode = torMode,
                    torBootstrap = torBootstrap,
                    torAvailable = torAvailable,
                    torActive = torActive,
                    diagnosticsAvailable = diagnosticsAvailable,
                    adblockAvailable = adblockAvailable,
                    blockAdsMode = blockAdsMode,
                    onOpenSettings = { route = Route.Settings },
                    onOpenServers = { route = Route.Servers },
                    onOpenSheet = { sheet = it },
                )
                Route.Servers -> ServersScreen(
                    savedProxies = savedProxies,
                    defaultId = defaultId,
                    statuses = proxyStatuses,
                    free = freeProxies,
                    freeBusy = freeProxyBusy,
                    freeNote = freeProxyNote,
                    onBack = { route = Route.Home },
                    onReuseSaved = { id ->
                        viewModel.applySaved(id)
                        route = Route.Home
                    },
                    onEditSaved = { proxy ->
                        viewModel.beginEditSaved(proxy)
                        route = Route.Home
                        sheet = HomeSheet.ManualEdit
                    },
                    onDeleteSaved = viewModel::deleteSaved,
                    onSetDefault = viewModel::setDefault,
                    onMoveSaved = viewModel::moveSaved,
                    onCheckSaved = viewModel::checkSaved,
                    onCheckAllSaved = viewModel::checkAllSaved,
                    onCheckFree = viewModel::checkFree,
                    onCheckAllFree = viewModel::checkAllFree,
                    onRefreshFree = viewModel::refreshFreeProxies,
                    onLoadFreeCache = viewModel::loadFreeProxiesFromCache,
                    onSaveFree = { entry ->
                        pendingFreeSave = entry
                        sheet = HomeSheet.SaveProxy
                    },
                    onApplyFree = { entry ->
                        viewModel.applyFreeProxy(entry)
                        route = Route.Home
                    },
                    freeState = freeRefreshState,
                    vpnGateAvailable = vpnGateCtl != null,
                    vpnGate = vpnGateServers.map { s ->
                        VpnGateRow(
                            id = s.key,
                            country = (s.flagEmoji + " " + s.countryLong).trim(),
                            host = s.hostName,
                            pingMs = s.ping.takeIf { it >= 0 },
                            score = s.score.takeIf { it > 0 },
                            speedMbps = s.speedMbps.takeIf { s.speed > 0 },
                            directlyDialable = s.directlyDialable,
                            countryShort = s.countryShort,
                            countryLong = s.countryLong,
                            flag = s.flagEmoji,
                        )
                    },
                    vpnGateBusy = vpnGateState is ai.darshj.djproxy.vpngate.VpnGateRefreshState.Loading,
                    vpnGateError = (vpnGateState as? ai.darshj.djproxy.vpngate.VpnGateRefreshState.Error)?.reason,
                    onRefreshVpnGate = { scope.launch { vpnGateCtl?.refresh() } },
                    onUseVpnGate = { row ->
                        vpnGateServers.firstOrNull { it.key == row.id }?.let { viewModel.useVpnGate(it) }
                        route = Route.Home
                    },
                    onSaveVpnGate = { row ->
                        vpnGateServers.firstOrNull { it.key == row.id }?.let { viewModel.saveVpnGate(it) }
                    },
                    onExportVpnGate = { row ->
                        vpnGateServers.firstOrNull { it.key == row.id }?.let { vpnGateCtl?.shareOvpn(onboardingContext, it) }
                    },
                    // v2: one-tap "Connect (open in OpenVPN app)" for a catalog row, save its .ovpn to the
                    // vault, and the saved-profile row actions (connect / share / delete) + the
                    // "install an OpenVPN app" guidance dialog. Kills the old export→manual-reimport dance.
                    savedOvpn = savedOvpn,
                    onConnectVpnGate = { row ->
                        // v9: Connect IN-APP — use the OpenVPN server as a proxy via the embedded engine,
                        // then show the connect orb on Home (mirrors tapping a normal proxy / Tor).
                        vpnGateServers.firstOrNull { it.key == row.id }?.let {
                            viewModel.connectVpnGateEngine(it)
                            route = Route.Home
                        }
                    },
                    onSaveOvpnProfile = { row ->
                        vpnGateServers.firstOrNull { it.key == row.id }?.let { viewModel.saveOvpnGate(it) }
                    },
                    onConnectSavedOvpn = { id ->
                        viewModel.connectSavedOvpnEngine(id)
                        route = Route.Home
                    },
                    onShareSavedOvpn = { id -> viewModel.shareSavedOvpn(onboardingContext, id) },
                    onDeleteSavedOvpn = viewModel::deleteSavedOvpn,
                    vpnGateNoOpenVpnApp = vpnGateNoOpenVpnApp,
                    onDismissVpnGateNoOpenVpnApp = viewModel::dismissVpnGateNoOpenVpnApp,
                )
                Route.Settings -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = responsiveContentPadding(maxWidth),
                    ) {
                        item {
                            SettingsScreen(
                                vpnState = vpnState,
                                onBack = { route = Route.Home },
                                onOpenAbout = { route = Route.About },
                                onOpenServers = { route = Route.Servers },
                            )
                        }
                    }
                }
                Route.About -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = responsiveContentPadding(maxWidth),
                    ) {
                        item { AboutContent(onBack = { route = Route.Settings }) }
                    }
                }
            }
        }

        // Sheets (Tier 2) — hosted above whatever route is showing.
        when (sheet) {
            HomeSheet.Import -> ImportSheet(
                preview = importPreview,
                choices = importChoices,
                error = ui.validationError,
                busy = importBusy,
                onIngest = viewModel::ingestExternal,
                onChoose = viewModel::chooseImport,
                onConnectPreview = {
                    viewModel.onApply()
                    viewModel.consumeImportPreview()
                },
                onOpenScan = { sheet = HomeSheet.Scan },
                onDismiss = {
                    viewModel.consumeImportPreview()
                    viewModel.clearImportChoices()
                    viewModel.dismissValidationError()
                    sheet = HomeSheet.None
                },
            )
            HomeSheet.Scan -> ScanSheet(
                onResult = { raw -> viewModel.ingestExternal(raw, autoConnect = false) },
                onDismiss = {
                    viewModel.dismissValidationError()
                    sheet = HomeSheet.None
                },
                rejectionMessage = ui.validationError?.message,
                onRetry = viewModel::dismissValidationError,
            )
            HomeSheet.ManualEdit -> ManualEditSheet(
                config = ui.config,
                pasteText = ui.pasteText,
                pasteError = ui.pasteError,
                onConfigChange = viewModel::onConfigChanged,
                onPasteChange = viewModel::onPasteTextChanged,
                onConnect = viewModel::onApply,
                onDismiss = {
                    if (editing != null) viewModel.cancelEdit()
                    sheet = HomeSheet.None
                },
                editingName = editing?.name,
                onEditNameChange = viewModel::setEditingName,
                onCommitEdit = viewModel::commitEdit,
                // Author mode only: raise the SaveProxy sheet to name & persist the current config.
                onSaveToVault = if (editing == null && ui.config.host.isNotBlank()) {
                    { pendingFreeSave = null; sheet = HomeSheet.SaveProxy }
                } else {
                    null
                },
            )
            HomeSheet.SaveProxy -> {
                val freeEntry = pendingFreeSave
                SaveProxySheet(
                    redacted = freeEntry?.let { "${it.type.scheme}://${it.host}:${it.port}" }
                        ?: ui.config.redacted(),
                    suggestedName = freeEntry?.host ?: ui.config.host,
                    showPublicCaveat = freeEntry != null,
                    onSave = { name ->
                        if (freeEntry != null) {
                            viewModel.saveFreeProxy(freeEntry, name)
                        } else {
                            viewModel.saveCurrent(name)
                        }
                    },
                    onDismiss = {
                        pendingFreeSave = null
                        sheet = HomeSheet.None
                    },
                )
            }
            HomeSheet.ShareLan -> ShareLanSheet(onDismiss = { sheet = HomeSheet.None })
            HomeSheet.TorInfo -> TorInfoSheet(onDismiss = { sheet = HomeSheet.None })
            HomeSheet.None -> Unit
        }
    }
}

@Composable
private fun HomeContent(
    viewModel: ProxyViewModel,
    ui: ProxyUiState,
    vpnState: VpnState,
    controllerReady: Boolean,
    logs: List<ai.darshj.djproxy.vpn.LogEvent>,
    torMode: Boolean,
    torBootstrap: Int?,
    torAvailable: Boolean,
    torActive: Boolean,
    diagnosticsAvailable: Boolean,
    adblockAvailable: Boolean,
    blockAdsMode: Boolean,
    onOpenSettings: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenSheet: (HomeSheet) -> Unit,
) {
    val connected = vpnState.stage == VpnStage.CONNECTED || vpnState.stage == VpnStage.RECONNECTING
    // Over Tor the applied config is the internal loopback socks5://127.0.0.1:9050 — show an honest
    // "Tor circuit" identity for the single line of truth instead of exposing localhost plumbing.
    // Whenever Tor mode is engaged the applied config is always the internal loopback
    // socks5://127.0.0.1:9050 — show an honest "Tor circuit" identity for the WHOLE session (bootstrap,
    // the post-bootstrap tunnel bring-up, and active) instead of ever leaking localhost plumbing during
    // the CONNECTING window.
    val redactedLine = if (torMode || torBootstrap != null) {
        "Tor circuit"
    } else {
        vpnState.proxyRedacted ?: ui.config.takeIf { it.host.isNotBlank() }?.redacted()
    }
    // On a fresh launch there is genuinely nothing to connect to yet — used to steer the first ring
    // tap to "add a source" instead of punishing it with a validation error.
    val hasSource = ui.config.host.isNotBlank() || torMode

    // §ui-center: CenteredScreen replaces the old top-anchoring BoxWithConstraints+LazyColumn. Home's
    // content is a small fixed cluster (header, ring hero, source strip, at most one error card / chip
    // row / disclosure) — not a growing list — so it belongs at the viewport's true vertical centre at
    // rest, and only falls back to top-anchored scroll once the error card / expanded chips / expanded
    // disclosure push it past the viewport. `Arrangement.spacedBy(.., CenterVertically)` gives both the
    // 20dp gap the old LazyColumn had AND the whole-group centring in one arrangement.
    CenteredScreen(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(DjSpacing.xl, Alignment.CenterVertically),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The big DJ monogram — same tri-tone brand sweep as the splash + ConnectRing, so
                    // splash -> home -> about all carry one consistent mark.
                    DjMonogram(size = 36.dp)
                    Spacer(Modifier.width(DjSpacing.sm))
                    Text("DJProxy", style = MaterialTheme.typography.displayMedium, color = DjColors.TextPrimary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Device-wide, fail-closed proxy tunnel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DjColors.TextSecondary,
                    )
                    Text(
                        "  ·  by darshj.ai",
                        style = MaterialTheme.typography.labelSmall,
                        color = DjColors.TextTertiary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onOpenSheet(HomeSheet.ShareLan) }) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "Share connection",
                        tint = DjColors.TextSecondary,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = DjColors.TextSecondary)
                }
            }
        }

        // TIER 0 HERO — the ring + one live line of truth.
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(contentAlignment = Alignment.Center) {
                // The hero owns more of the canvas on the unfolded Fold7 (up to 280dp) instead of
                // floating small in empty space; identical 200dp on a folded phone.
                val ringSize = (maxWidth * 0.6f).coerceIn(200.dp, 280.dp)
                if (torActive) TorOnionOrbit(size = ringSize * 1.12f)
                ObsidianOrb(
                    stage = vpnState.stage,
                    onClick = {
                        // First run (no source, not connected): the first tap steers to "add a
                        // proxy" instead of dropping a validation error under the orb.
                        if (!hasSource &&
                            vpnState.stage != VpnStage.CONNECTED &&
                            vpnState.stage != VpnStage.RECONNECTING
                        ) {
                            onOpenSheet(HomeSheet.ManualEdit)
                        } else {
                            viewModel.onRingTap()
                        }
                    },
                    orbSize = ringSize,
                    torBootstrapPct = torBootstrap,
                )
            }
            Spacer(Modifier.height(8.dp))
            // Reserve a stable height so the ring doesn't jitter up/down as the sub-label lines
            // appear/disappear across idle -> connecting -> connected -> tor-bootstrap.
            Column(
                modifier = Modifier.heightIn(min = 84.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (torBootstrap != null) {
                    Text(
                        "Building Tor circuit… $torBootstrap%",
                        style = MaterialTheme.typography.titleSmall,
                        color = DjColors.TorPurple,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    // Make the cancel gesture discoverable — the ring accepts a tap to abort the
                    // bootstrap so it is never a silent, uncancellable wait.
                    Text(
                        "Tap the ring to cancel",
                        style = MaterialTheme.typography.bodySmall,
                        color = DjColors.TextTertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    StageLabel(stage = vpnState.stage)
                }
                redactedLine?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DjColors.TextTertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // True first-run empty state — guide the user to add a source instead of a blank ring.
                if (redactedLine == null && vpnState.stage == VpnStage.IDLE && !hasSource && torBootstrap == null) {
                    Text(
                        "Add a proxy to begin — tap the ring, or use Edit / Scan / Import below",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DjColors.TextSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (!controllerReady) {
                    Text(
                        "Preparing VPN service…",
                        style = MaterialTheme.typography.bodySmall,
                        color = DjColors.TextTertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            AnimatedVisibility(visible = torActive, enter = fadeIn() + scaleIn(initialScale = 0.85f), exit = fadeOut()) {
                TorActivePill(modifier = Modifier.padding(top = 12.dp), onClick = { onOpenSheet(HomeSheet.TorInfo) })
            }
        }

        // TIER 1 SOURCE
        SourceStrip(
            onPaste = { onOpenSheet(HomeSheet.ManualEdit) },
            onScan = { onOpenSheet(HomeSheet.Scan) },
            onImport = { onOpenSheet(HomeSheet.Import) },
            onOpenServers = onOpenServers,
            torAvailable = torAvailable,
            torEnabled = torMode,
            torBootstrapPct = torBootstrap,
            torActive = torActive,
            onToggleTor = viewModel::setTorMode,
            onTorInfo = { onOpenSheet(HomeSheet.TorInfo) },
            adblockAvailable = adblockAvailable,
            adblockEnabled = blockAdsMode,
            onToggleAdblock = viewModel::setAdBlockMode,
        )

        // v12: one-tap FREE VPN via Cloudflare WARP (WireGuard). The always-works free route — no
        // server to pick, no signup: registers a free anonymous WARP account once, then routes the
        // whole device through it (verified end-to-end: warp=on). Hidden if the WG engine is absent.
        val wgAvailable = remember { ai.darshj.djproxy.wireguard.WgEngineGateway.controller != null }
        val wgConnecting by viewModel.vpnGateConnecting.collectAsStateWithLifecycle()
        if (wgAvailable) {
            androidx.compose.material3.FilledTonalButton(
                onClick = { viewModel.connectWarp() },
                enabled = controllerReady && !wgConnecting && !connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (wgConnecting) "Connecting Free VPN…" else "🛡  Free VPN  ·  Cloudflare WARP",
                    style = MaterialTheme.typography.titleSmall,
                    color = DjColors.TextPrimary,
                )
            }
        }

        // Inline blocking error card (walled apart from advisory chips, §3).
        AnimatedVisibility(
            visible = ui.validationError != null,
            enter = fadeIn(tween(220)) + expandVertically(tween(220)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
        ) {
            ui.validationError?.let { error ->
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    borderBrush = androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(DjColors.Rose, DjColors.RoseDeep),
                    ),
                    fill = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(DjColors.Rose.copy(alpha = 0.14f), DjColors.Rose.copy(alpha = 0.05f)),
                    ),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(error.message, style = MaterialTheme.typography.titleSmall, color = DjColors.TextPrimary)
                        Text(
                            error.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = DjColors.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            if (diagnosticsAvailable) {
                                TextButton(onClick = viewModel::sendErrorReport) {
                                    Text("Send report", color = DjColors.AccentCyan)
                                }
                            }
                            TextButton(onClick = viewModel::dismissValidationError) {
                                Text("Dismiss", color = DjColors.Rose)
                            }
                        }
                    }
                }
            }
        }

        // Advisory chips — connected-only on Home, animated entry.
        AnimatedVisibility(visible = connected, enter = fadeIn(), exit = fadeOut()) {
            AnimatedAdvisoryChips(health = vpnState.health, modifier = Modifier.fillMaxWidth())
        }

        // TIER 0 collapsed details (status card + stats + log).
        DetailsDisclosure(state = vpnState, logs = logs, modifier = Modifier.fillMaxWidth())
    }
}

/** The Tor "onion layers" orbit — a slow dashed concentric ring behind the hero while Tor is up. */
@Composable
private fun TorOnionOrbit(size: Dp = 224.dp) {
    val animate = rememberAnimationsEnabled()
    val infinite = rememberInfiniteTransition(label = "onion-orbit")
    val turn = if (animate) {
        infinite.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(MotionTokens.ONION_ORBIT_MS)),
            label = "onion-turn",
        ).value
    } else 0f
    Canvas(modifier = Modifier.size(size)) {
        val stroke = Stroke(
            width = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 16f)),
        )
        rotate(turn) {
            drawArc(
                color = DjColors.TorPurple.copy(alpha = 0.5f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                style = stroke,
            )
        }
    }
}

/** The rising "Tor active · .onion enabled" pill. */
@Composable
private fun TorActivePill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val animate = rememberAnimationsEnabled()
    val infinite = rememberInfiniteTransition(label = "tor-pill")
    val breath = if (animate) {
        infinite.animateFloat(
            initialValue = 0.9f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(MotionTokens.BREATH_CONN_MS, easing = MotionTokens.BreathEasing),
                RepeatMode.Reverse,
            ),
            label = "tor-pill-breath",
        ).value
    } else 1f
    Row(
        modifier = modifier
            .scale(breath)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = "Tor active, dot onion enabled. Show Tor details."
            }
            .background(DjColors.TorPurple.copy(alpha = 0.18f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(DjColors.TorPurple),
        )
        Spacer(Modifier.width(8.dp))
        Text("Tor active · .onion enabled", style = MaterialTheme.typography.labelLarge, color = DjColors.TorPurple)
    }
}

@Composable
private fun AboutContent(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = DjColors.TextPrimary,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("About", style = MaterialTheme.typography.headlineSmall, color = DjColors.TextPrimary)
        }
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = DjSpacing.lg), contentAlignment = Alignment.Center) {
            // The same DJ monogram as the header + splash, at the "large" size this composable is
            // meant to also serve About/Splash at — brand consistency across every surface it appears.
            DjMonogram(size = 72.dp)
        }
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DJProxy by Darshj.ai", style = MaterialTheme.typography.titleMedium, color = DjColors.TextPrimary)
                Text(
                    "Version ${ai.darshj.djproxy.BuildConfig.VERSION_NAME} " +
                        "(build ${ai.darshj.djproxy.BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DjColors.TextSecondary,
                )
                Text("Created by Darshankumar Joshi", style = MaterialTheme.typography.bodyMedium, color = DjColors.TextPrimary)
                // Real, tappable affordances — no more link-coloured dead text.
                Text(
                    "darshj.ai",
                    style = MaterialTheme.typography.bodySmall,
                    color = DjColors.AccentCyan,
                    modifier = Modifier.clickable { uriHandler.openUri("https://darshj.ai") },
                )
                Text(
                    "Device-wide, fail-closed SOCKS/HTTP proxy tunnel with optional Tor onion routing. " +
                        "MIT-licensed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DjColors.TextSecondary,
                )
                Text(
                    "Source · github.com/darshjme/DJProxy",
                    style = MaterialTheme.typography.bodySmall,
                    color = DjColors.AccentCyan,
                    modifier = Modifier.clickable { uriHandler.openUri("https://github.com/darshjme/DJProxy") },
                )
                Text(
                    "Bundled: hev-socks5-tunnel · Tor (guardianproject) · ZXing · CameraX. See each " +
                        "project's license in the repository.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DjColors.TextTertiary,
                )
            }
        }
    }
}
