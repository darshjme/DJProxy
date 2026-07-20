package ai.darshj.djproxy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.darshj.djproxy.config.ConfigImporter
import ai.darshj.djproxy.config.ImportResult
import ai.darshj.djproxy.config.NamedConfig
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyParser
import ai.darshj.djproxy.freeproxy.FreeProxyEntry
import ai.darshj.djproxy.freeproxy.FreeProxyResult
import ai.darshj.djproxy.freeproxy.FreeProxySource
import ai.darshj.djproxy.proxy.ProxyError
import ai.darshj.djproxy.proxy.ValidationResult
import ai.darshj.djproxy.store.ProxyOrigin
import ai.darshj.djproxy.store.ProxyStatus
import ai.darshj.djproxy.store.ProxyStore
import ai.darshj.djproxy.store.SavedProxy
import ai.darshj.djproxy.store.StatusChecker
import ai.darshj.djproxy.tor.TorGateway
import ai.darshj.djproxy.tor.TorStartResult
import ai.darshj.djproxy.vpn.FeatureRegistry
import ai.darshj.djproxy.vpn.LogEvent
import ai.darshj.djproxy.vpn.VpnController
import ai.darshj.djproxy.vpn.VpnStage
import ai.darshj.djproxy.vpn.VpnState
import ai.darshj.djproxy.vpn.LogBus
import ai.darshj.djproxy.vpn.seams.CriticalFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val LOG_HISTORY_CAP = 400
/** Outer safety backstop around Tor start() only. Must stay ABOVE OnionProxyManager's own worst case
 *  (BIND_TIMEOUT 20s + BOOTSTRAP_TIMEOUT 120s = 140s) so the manager's specific timeout reason
 *  surfaces first; this generic VM-level cap is a pure last resort so a wedged engine can never leave
 *  the PREPARING_TOR arc spinning forever. */
private const val TOR_BOOTSTRAP_TIMEOUT_MS = 150_000L

/** Everything the paste box + fields + inline error card need, independent of the tunnel's own
 *  [VpnState] (which the screen observes separately from [VpnController.state]). */
data class ProxyUiState(
    val pasteText: String = "",
    val config: ProxyConfig = ProxyConfig(),
    val pasteError: String? = null,
    val validationError: ProxyError? = null,
)

/**
 * v6 servers-tab: the explicit outcome of the last free-list Refresh (or cache-load), so the Free tab
 * can render a real loading → count+timestamp → inline-error progression instead of a single silent
 * amber note. [Success.fetchedAt] is threaded straight out of [FreeProxyResult.Ok.fetchedAt] so the UI
 * can show "Updated · N servers · <relative time>"; [Success.fromCache] distinguishes a fresh pull from
 * a served-stale fallback; [Error] carries the honest failure reason for the inline error card. [Idle]
 * is the pre-wiring / never-refreshed default (the UI falls back to the legacy note in that state).
 */
sealed interface FreeRefreshState {
    data object Idle : FreeRefreshState
    data object Loading : FreeRefreshState
    data class Success(val count: Int, val fetchedAt: Long, val fromCache: Boolean) : FreeRefreshState
    data object Empty : FreeRefreshState
    data class Error(val reason: String) : FreeRefreshState
}

/**
 * A parsed single-config import waiting for the user's one confirmation tap (§11 security seam).
 * Deep-link / share-target / .ovpn opens NEVER auto-connect silently — they raise the Import sheet
 * with this preview and the user taps Connect. [autoConnect] only pre-emphasises the Connect action
 * (e.g. `djproxy://connect`); it does not bypass the tap.
 */
data class ImportPreview(val config: ProxyConfig, val autoConnect: Boolean)

/**
 * v6: the in-flight "edit a saved proxy" session. Non-null while the manual editor is editing an
 * existing vault entry (vs. authoring a brand-new one). [name] is edited live in the sheet and
 * committed back through [ProxyStore.update] on save. The password (if any) is materialised into the
 * live [ProxyUiState.config] by [ProxyStore.resolve] when the edit begins, so the editor prefills the
 * real credentials and re-encrypts them on save via the store — the VM never persists plaintext.
 */
data class EditContext(val id: String, val name: String)

/**
 * Owns the canonical [ProxyConfig] and mediates every write path into it (paste box, individual
 * fields, external imports), plus drives [VpnController.apply]/[VpnController.stop]. The controller
 * itself is supplied asynchronously by [attachController] once the host activity has bound to the
 * VPN service — until then Apply is disabled and the UI shows a "preparing" state.
 *
 * v4 additions (§3, §5, §8): [torMode] + synthetic [torBootstrap] pre-stage; the one-facade
 * [ingestExternal] path that every front door (paste, scan, subscription, .ovpn, deep link, share)
 * converges on via `config.ConfigImporter`.
 */
class ProxyViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ProxyUiState())
    val uiState: StateFlow<ProxyUiState> = _uiState.asStateFlow()

    private val _controller = MutableStateFlow<VpnController?>(null)

    /** True once the VPN service is bound and Apply/Stop can actually do something. */
    val controllerReady: StateFlow<Boolean> =
        _controller.map { it != null }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Live tunnel state — [VpnState.IDLE] whenever no controller is attached yet. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val vpnState: StateFlow<VpnState> = _controller
        .flatMapLatest { it?.state ?: MutableStateFlow(VpnState.IDLE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VpnState.IDLE)

    /** Rolling window of the last [LOG_HISTORY_CAP] log lines for [LogView]. */
    val logs: StateFlow<List<LogEvent>> = LogBus.events
        .scan(emptyList<LogEvent>()) { acc, event -> (acc + event).takeLast(LOG_HISTORY_CAP) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- v4: Tor mode ---
    private val _torMode = MutableStateFlow(false)

    /** True when the user has toggled "Enable Tor" on. While on, the manual source row is disabled. */
    val torMode: StateFlow<Boolean> = _torMode.asStateFlow()

    private val _torBootstrap = MutableStateFlow<Int?>(null)

    /** Non-null (0..100) only while the synthetic PREPARING_TOR pre-stage is running; null otherwise. */
    val torBootstrap: StateFlow<Int?> = _torBootstrap.asStateFlow()

    /** The in-flight Tor prepare, so a second ring tap can cancel it (§3 uncancellable-hang fix). */
    private var torJob: Job? = null

    // --- v4: external import intake (§5) ---
    private val _importPreview = MutableStateFlow<ImportPreview?>(null)
    val importPreview: StateFlow<ImportPreview?> = _importPreview.asStateFlow()

    private val _importChoices = MutableStateFlow<List<NamedConfig>?>(null)
    val importChoices: StateFlow<List<NamedConfig>?> = _importChoices.asStateFlow()

    /** True while an [ingestExternal] parse/fetch is in flight, so the Import sheet can show a
     *  spinner + disable its submit button instead of sitting inert (esp. a slow subscription GET). */
    private val _importBusy = MutableStateFlow(false)
    val importBusy: StateFlow<Boolean> = _importBusy.asStateFlow()

    /** Called once by the host activity after binding to the VPN service. Idempotent. */
    fun attachController(controller: VpnController) {
        _controller.value = controller
    }

    // ---------------------------------------------------------------------------------------------
    // v6: Proxy vault + live status + free public list (§1). The three seams are attached by the host
    // activity exactly like [attachController]; the VM adds NO persistence, dial, or fetch logic of its
    // own — every method below delegates to one of the three interfaces. Reuse funnels through the
    // unchanged [applyConfig]/[VpnController.apply] path.
    // ---------------------------------------------------------------------------------------------

    private val _store = MutableStateFlow<ProxyStore?>(null)
    private val _checker = MutableStateFlow<StatusChecker?>(null)
    private val _freeSource = MutableStateFlow<FreeProxySource?>(null)

    /** The reactive vault, already ordered by the store; empty until the store is attached. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val savedProxies: StateFlow<List<SavedProxy>> = _store
        .flatMapLatest { it?.proxies ?: MutableStateFlow(emptyList<SavedProxy>()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The vault's default entry id, or null. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val defaultId: StateFlow<String?> = _store
        .flatMapLatest { it?.defaultId ?: MutableStateFlow<String?>(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Live status per proxy, keyed by [SavedProxy.id] and [FreeProxyEntry.key]. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val proxyStatuses: StateFlow<Map<String, ProxyStatus>> = _checker
        .flatMapLatest { it?.statuses ?: MutableStateFlow(emptyMap<String, ProxyStatus>()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _freeProxies = MutableStateFlow<List<FreeProxyEntry>>(emptyList())

    /** The parsed, SSRF-screened free public list (untrusted). Empty until the first fetch. */
    val freeProxies: StateFlow<List<FreeProxyEntry>> = _freeProxies.asStateFlow()

    private val _freeProxyBusy = MutableStateFlow(false)

    /** True while a free-list fetch is in flight (drives the Refresh spinner). */
    val freeProxyBusy: StateFlow<Boolean> = _freeProxyBusy.asStateFlow()

    private val _freeProxyNote = MutableStateFlow<String?>(null)

    /** A short honest note on the last free-list fetch ("Showing cached list" / a failure reason), or null. */
    val freeProxyNote: StateFlow<String?> = _freeProxyNote.asStateFlow()

    private val _freeRefreshState = MutableStateFlow<FreeRefreshState>(FreeRefreshState.Idle)

    /**
     * The explicit state of the last free-list refresh (loading / success-with-count-and-time / empty /
     * error). Drives the Free tab's explicit refresh UI so a refresh never silently degrades to an inert
     * grey list — replaces the single amber [freeProxyNote] as the primary signal.
     */
    val freeRefreshState: StateFlow<FreeRefreshState> = _freeRefreshState.asStateFlow()

    private val _freeLastFetchedAt = MutableStateFlow<Long?>(null)

    /** Epoch-millis the free list was last produced/cached (from [FreeProxyResult.Ok.fetchedAt]), or null. */
    val freeLastFetchedAt: StateFlow<Long?> = _freeLastFetchedAt.asStateFlow()

    private val _editing = MutableStateFlow<EditContext?>(null)

    /** Non-null while the manual editor is editing an existing vault entry (§5.4). */
    val editing: StateFlow<EditContext?> = _editing.asStateFlow()

    /** Attach the vault / status / free-list seams. Idempotent, mirrors [attachController]. */
    fun attachVault(store: ProxyStore, checker: StatusChecker, freeSource: FreeProxySource) {
        _store.value = store
        _checker.value = checker
        _freeSource.value = freeSource
    }

    /**
     * Re-apply an arbitrary config through the EXACT v5 apply path (pre-flight + fail-closed bring-up).
     * A saved/free proxy is a direct custom proxy, so any live Tor session is left first — a custom
     * proxy and Tor are mutually exclusive (§5.4). No new dial logic is added.
     */
    private fun reuseConfig(config: ProxyConfig) {
        if (_torMode.value) {
            _torMode.value = false
            _torBootstrap.value = null
            torJob?.cancel()
            torJob = null
            stopTorIfRunning()
        }
        _uiState.value = _uiState.value.copy(
            config = config,
            pasteText = config.redacted(),
            pasteError = null,
        )
        applyConfig(config)
    }

    /** Reuse a saved proxy: decrypt via [ProxyStore.resolve], then the identical [applyConfig] path. */
    fun applySaved(id: String) {
        val store = _store.value ?: return
        viewModelScope.launch {
            val config = store.resolve(id)
            if (config == null) {
                _uiState.value = _uiState.value.copy(
                    validationError = ProxyError.Io(
                        "This saved proxy could not be restored — it may have been deleted, or its saved " +
                            "password could not be decrypted on this device.",
                    ),
                )
                return@launch
            }
            reuseConfig(config)
        }
    }

    /** Save the current editor config into the vault under [name] (password encrypted by the store). */
    fun saveCurrent(name: String, origin: ProxyOrigin = ProxyOrigin.USER) {
        val store = _store.value ?: return
        viewModelScope.launch { store.save(name.ifBlank { defaultNameFor(_uiState.value.config) }, _uiState.value.config, origin) }
    }

    fun deleteSaved(id: String) {
        val store = _store.value ?: return
        viewModelScope.launch { store.delete(id) }
    }

    fun setDefault(id: String?) {
        val store = _store.value ?: return
        viewModelScope.launch { store.setDefault(id) }
    }

    fun reorder(orderedIds: List<String>) {
        val store = _store.value ?: return
        viewModelScope.launch { store.reorder(orderedIds) }
    }

    /**
     * Move a saved entry one slot up/down and persist the new order (§5.3). A robust, accessible
     * reorder that funnels through [ProxyStore.reorder] with no fragile drag math.
     */
    fun moveSaved(id: String, up: Boolean) {
        val ids = savedProxies.value.map { it.id }.toMutableList()
        val i = ids.indexOf(id)
        if (i < 0) return
        val j = if (up) i - 1 else i + 1
        if (j !in ids.indices) return
        ids[i] = ids[j].also { ids[j] = ids[i] }
        reorder(ids)
    }

    // --- edit-a-saved-proxy session (§5.4) ---

    /** Begin editing a saved proxy: prefill the live editor with its resolved (decrypted) config. */
    fun beginEditSaved(saved: SavedProxy) {
        val store = _store.value ?: return
        viewModelScope.launch {
            val config = store.resolve(saved.id) ?: return@launch
            _uiState.value = _uiState.value.copy(
                config = config,
                pasteText = config.redacted(),
                pasteError = null,
                validationError = null,
            )
            _editing.value = EditContext(saved.id, saved.name)
        }
    }

    fun setEditingName(name: String) {
        _editing.value = _editing.value?.copy(name = name)
    }

    /** Commit the in-flight edit to the vault via [ProxyStore.update] and end the edit session. */
    fun commitEdit() {
        val store = _store.value ?: return
        val edit = _editing.value ?: return
        viewModelScope.launch {
            store.update(edit.id, edit.name.ifBlank { defaultNameFor(_uiState.value.config) }, _uiState.value.config)
            _editing.value = null
        }
    }

    fun cancelEdit() {
        _editing.value = null
    }

    // --- live status (§3) ---

    /** Re-check one saved proxy now (resolves its full config incl. auth first). */
    fun checkSaved(saved: SavedProxy) {
        val store = _store.value ?: return
        val checker = _checker.value ?: return
        viewModelScope.launch {
            val config = store.resolve(saved.id) ?: return@launch
            checker.check(saved.id, config)
        }
    }

    /** Re-check one free proxy now (auth-less public config). */
    fun checkFree(entry: FreeProxyEntry) {
        val checker = _checker.value ?: return
        viewModelScope.launch { checker.check(entry.key, entry.toConfig()) }
    }

    /** Bounded-concurrency "check all" over the given saved rows (the visible window). */
    fun checkAllSaved(saved: List<SavedProxy>) {
        val store = _store.value ?: return
        val checker = _checker.value ?: return
        if (saved.isEmpty()) return
        viewModelScope.launch {
            val targets = saved.mapNotNull { s -> store.resolve(s.id)?.let { s.id to it } }
            checker.checkAll(targets)
        }
    }

    /** Bounded-concurrency "check all" over the given free rows (the visible window). */
    fun checkAllFree(entries: List<FreeProxyEntry>) {
        val checker = _checker.value ?: return
        if (entries.isEmpty()) return
        viewModelScope.launch { checker.checkAll(entries.map { it.key to it.toConfig() }) }
    }

    // --- free public list (§4) ---

    /** Fetch (or serve cache when [force] is false) the free public proxy list. */
    fun refreshFreeProxies(force: Boolean) {
        val source = _freeSource.value ?: return
        viewModelScope.launch {
            _freeProxyBusy.value = true
            _freeRefreshState.value = FreeRefreshState.Loading
            try {
                when (val result = source.fetch(force)) {
                    is FreeProxyResult.Ok -> {
                        _freeProxies.value = result.entries
                        _freeLastFetchedAt.value = result.fetchedAt
                        _freeProxyNote.value = if (result.fromCache) {
                            "Showing the last cached list — couldn't refresh right now."
                        } else {
                            null
                        }
                        _freeRefreshState.value = if (result.entries.isEmpty()) {
                            FreeRefreshState.Empty
                        } else {
                            FreeRefreshState.Success(result.entries.size, result.fetchedAt, result.fromCache)
                        }
                    }
                    is FreeProxyResult.Failed -> {
                        _freeProxyNote.value = result.reason
                        _freeRefreshState.value = FreeRefreshState.Error(result.reason)
                    }
                }
            } finally {
                _freeProxyBusy.value = false
            }
        }
    }

    /**
     * Populate the free list from the LOCAL CACHE ONLY (no network). Called when the Free tab is first
     * opened so merely viewing it never emits an outbound request — the network pull stays behind the
     * explicit [refreshFreeProxies] `force = true`. No busy spinner: this is a cheap local read.
     */
    fun loadFreeProxiesFromCache() {
        val source = _freeSource.value ?: return
        if (_freeProxies.value.isNotEmpty()) return
        viewModelScope.launch {
            val cached = source.fetchCachedOnly() ?: return@launch
            if (_freeProxies.value.isEmpty()) {
                _freeProxies.value = cached.entries
                _freeLastFetchedAt.value = cached.fetchedAt
                _freeRefreshState.value = if (cached.entries.isEmpty()) {
                    FreeRefreshState.Empty
                } else {
                    FreeRefreshState.Success(cached.entries.size, cached.fetchedAt, fromCache = true)
                }
            }
        }
    }

    /** Save a free public proxy into the vault, keeping its FREE_PUBLIC provenance (§4.5). */
    fun saveFreeProxy(entry: FreeProxyEntry, name: String) {
        val store = _store.value ?: return
        viewModelScope.launch {
            store.save(name.ifBlank { entry.sourceLabel }, entry.toConfig(), ProxyOrigin.FREE_PUBLIC)
        }
    }

    /** Use a free public proxy now — the same reuse/apply path (after the untrusted-server consent gate). */
    fun applyFreeProxy(entry: FreeProxyEntry) {
        reuseConfig(entry.toConfig())
    }

    /** A sensible default vault name from a config when the user leaves the name blank. */
    private fun defaultNameFor(config: ProxyConfig): String =
        if (config.host.isNotBlank()) "${config.host}:${config.port}" else "Proxy"

    fun onPasteTextChanged(text: String) {
        val current = _uiState.value
        if (text.isBlank()) {
            _uiState.value = current.copy(pasteText = text, pasteError = null)
            return
        }
        when (val result = ProxyParser.parse(text, default = current.config)) {
            is ProxyParser.Result.Ok -> _uiState.value = current.copy(
                pasteText = text,
                config = result.config,
                pasteError = null,
            )
            is ProxyParser.Result.Err -> _uiState.value = current.copy(
                pasteText = text,
                pasteError = result.hint?.let { "${result.message} — $it" } ?: result.message,
            )
        }
    }

    fun onConfigChanged(config: ProxyConfig) {
        _uiState.value = _uiState.value.copy(config = config)
    }

    // ---------------------------------------------------------------------------------------------
    // Connect flow
    // ---------------------------------------------------------------------------------------------

    /**
     * The single tap handler for the hero ring. Mirrors the v3 ConnectButton semantics and adds the
     * Tor pre-stage: tapping while connected stops (and stops Tor); tapping while idle/error either
     * runs the normal validate-then-apply, or — when Tor mode is on — bootstraps Tor first and then
     * applies its 127.0.0.1:9050 config. Taps during any busy/preparing stage are ignored.
     */
    fun onRingTap() {
        val stage = vpnState.value.stage
        when {
            stage == VpnStage.CONNECTED || stage == VpnStage.RECONNECTING -> {
                onStop()
                stopTorIfRunning()
            }
            stage == VpnStage.VALIDATING || stage == VpnStage.CONNECTING || stage == VpnStage.STOPPING -> Unit
            _torBootstrap.value != null -> cancelTorPrepare() // tap again to cancel an in-flight prepare
            _torMode.value -> connectViaTor()
            else -> onApply()
        }
    }

    /** Cancels an in-flight Tor bootstrap (the PREPARING_TOR arc), tears the half-started Tor down, and
     *  returns the ring to IDLE — so the pre-stage is never an uncancellable multi-minute hang (§3). */
    private fun cancelTorPrepare() {
        torJob?.cancel()
        torJob = null
        _torBootstrap.value = null
        stopTorIfRunning()
        LogBus.i("UI", "Tor prepare cancelled by the user.")
    }

    /** Runs the real pre-flight on the current config (the exact live dial path). Never optimistic. */
    fun onApply() = applyConfig(_uiState.value.config)

    private fun applyConfig(config: ProxyConfig) {
        val controller = _controller.value ?: run {
            LogBus.w("UI", "Apply tapped before the VPN service finished binding.")
            return
        }
        val fieldError = config.validate()
        if (fieldError != null) {
            _uiState.value = _uiState.value.copy(config = config, validationError = ProxyError.Io(fieldError))
            return
        }
        _uiState.value = _uiState.value.copy(config = config, validationError = null)
        viewModelScope.launch {
            when (val result = controller.apply(config)) {
                is ValidationResult.Success -> _uiState.value = _uiState.value.copy(
                    validationError = null,
                )
                is ValidationResult.Failure -> _uiState.value = _uiState.value.copy(
                    validationError = result.error,
                )
            }
        }
    }

    /**
     * Applies the Tor loopback config through the EXISTING VpnController, but — unlike the plain
     * [applyConfig] — tears Tor back down if the tunnel bring-up fails. Otherwise a failed apply would
     * leave the Tor daemon running (and its "Tor active — .onion enabled" foreground notification up)
     * with no tunnel actually carrying traffic — a dishonest, resource-leaking half-state. If the VPN
     * service is not bound yet, that is also surfaced (and Tor stopped) instead of silently no-op'ing.
     */
    private fun applyTorConfig(config: ProxyConfig) {
        val controller = _controller.value ?: run {
            LogBus.w("UI", "Tor bootstrapped but the VPN service is not bound yet — stopping Tor.")
            stopTorIfRunning()
            _torMode.value = false
            _uiState.value = _uiState.value.copy(
                validationError = ProxyError.Io("The VPN service is still starting. Try enabling Tor again in a moment."),
            )
            return
        }
        _uiState.value = _uiState.value.copy(config = config, validationError = null)
        viewModelScope.launch {
            when (val result = controller.apply(config)) {
                is ValidationResult.Success -> _uiState.value = _uiState.value.copy(validationError = null)
                is ValidationResult.Failure -> {
                    // The tunnel could not route through the loopback SOCKS — do not leave Tor orphaned.
                    stopTorIfRunning()
                    _torMode.value = false
                    _uiState.value = _uiState.value.copy(validationError = result.error)
                }
            }
        }
    }

    fun onStop() {
        _controller.value?.stop()
    }

    // ---------------------------------------------------------------------------------------------
    // Tor mode (§3, §8)
    // ---------------------------------------------------------------------------------------------

    /** Toggles Tor mode. Turning it off while a Tor tunnel is up tears the tunnel + Tor down. */
    fun setTorMode(enabled: Boolean) {
        _torMode.value = enabled
        if (!enabled) {
            torJob?.cancel()
            torJob = null
            val stage = vpnState.value.stage
            if (stage == VpnStage.CONNECTED || stage == VpnStage.RECONNECTING) onStop()
            stopTorIfRunning()
            _torBootstrap.value = null
        }
    }

    /**
     * Bootstraps Tor (the lane exposes a local SOCKS5 on 127.0.0.1:9050), tracking real bootstrap %
     * through the synthetic [torBootstrap] pre-stage, then hands the EXISTING apply() the Tor config.
     * A bootstrap failure surfaces as a normal inline [ProxyError] — no special channel (§3).
     */
    private fun connectViaTor() {
        val controller = TorGateway.controller ?: run {
            _uiState.value = _uiState.value.copy(
                validationError = ProxyError.Io("Tor is not available in this build."),
            )
            return
        }
        torJob = viewModelScope.launch {
            _torBootstrap.value = 0
            val collector = launch {
                controller.bootstrapProgress.collect { _torBootstrap.value = it }
            }
            // Honour start()'s terminal result directly — it already blocks until Tor is Ready or Failed,
            // so there is no second wait to race. withTimeoutOrNull is ONLY a hard safety cap around
            // start() itself (bootstrap is already bounded inside the manager); a Failed result is
            // surfaced immediately instead of dead-waiting the full timeout on a flag that never flips.
            val started: TorStartResult? = withTimeoutOrNull(TOR_BOOTSTRAP_TIMEOUT_MS) {
                runCatching { controller.start() }
                    .getOrElse { TorStartResult.Failed(it.message ?: "Tor failed to start.") }
            }
            collector.cancel()
            _torBootstrap.value = null
            torJob = null
            when (started) {
                is TorStartResult.Started -> applyTorConfig(started.proxyConfig)
                is TorStartResult.Failed -> {
                    // start() already shut the engine down and set phase=FAILED on this path; just report.
                    _uiState.value = _uiState.value.copy(validationError = ProxyError.Io(started.reason))
                }
                null -> {
                    // Safety cap tripped: start() is still holding the engine — tear it down and report.
                    runCatching { controller.stop() }
                    _uiState.value = _uiState.value.copy(
                        validationError = ProxyError.Io(
                            "Tor could not start — bootstrapping timed out. " +
                                "Check your connection and try again.",
                        ),
                    )
                }
            }
        }
    }

    private fun stopTorIfRunning() {
        runCatching { TorGateway.controller?.stop() }
    }

    // ---------------------------------------------------------------------------------------------
    // External import — the one facade every front door converges on (§5)
    // ---------------------------------------------------------------------------------------------

    /**
     * The single ingestion path for paste-import, QR scan, subscription URLs, `.ovpn` files, deep
     * links and share-target text. Delegates to `config.ConfigImporter`, then:
     *  - Single  -> fill the config + raise an [ImportPreview] (one confirmation tap to connect).
     *  - Many    -> publish [importChoices] for the pick-list.
     *  - Rejected-> the existing inline rose card, with the config lane's typed, named message.
     *
     * [allowNetwork] gates the ONLY path that touches the network before any user tap: a subscription
     * URL fetch. It is true for genuinely user-initiated in-app front doors (the Import/paste sheet,
     * the QR scanner the user opened), and MUST be false for anything arriving from an untrusted
     * external intent (VIEW deep link / SEND share / .ovpn open). When false, a subscription-shaped
     * URL is parsed locally only and reported honestly ("use the Subscription tab") instead of firing
     * an unconsented GET from the device's real IP — closing the deep-link beacon / SSRF vector (§11).
     */
    fun ingestExternal(raw: String, autoConnect: Boolean, allowNetwork: Boolean = true) {
        if (raw.isBlank()) return
        viewModelScope.launch {
            _importBusy.value = true
            try {
                when (val result = runCatching {
                    if (allowNetwork) ConfigImporter.import(raw) else ConfigImporter.importLocal(raw)
                }.getOrElse {
                    _uiState.value = _uiState.value.copy(
                        validationError = ProxyError.Io("Could not read that import — ${it.message ?: "unknown error"}"),
                    )
                    return@launch
                }) {
                    is ImportResult.Single -> {
                        _uiState.value = _uiState.value.copy(
                            config = result.config,
                            pasteText = result.config.redacted(),
                            pasteError = null,
                            validationError = null,
                        )
                        _importPreview.value = ImportPreview(result.config, autoConnect)
                    }
                    is ImportResult.Many -> {
                        _importChoices.value = result.configs
                    }
                    is ImportResult.Rejected -> {
                        _uiState.value = _uiState.value.copy(
                            validationError = ProxyError.Io("${result.error.message} — ${result.error.hint}"),
                        )
                    }
                }
            } finally {
                _importBusy.value = false
            }
        }
    }

    /** User picked one entry from a subscription pick-list — load it and offer to connect. */
    fun chooseImport(named: NamedConfig) {
        _uiState.value = _uiState.value.copy(
            config = named.config,
            pasteText = named.config.redacted(),
            pasteError = null,
            validationError = null,
        )
        _importChoices.value = null
        _importPreview.value = ImportPreview(named.config, autoConnect = false)
    }

    /** Clears the pending single-import preview (after the sheet is dismissed or connect is tapped). */
    fun consumeImportPreview() {
        _importPreview.value = null
    }

    /** Clears the subscription pick-list (sheet dismissed without a choice). */
    fun clearImportChoices() {
        _importChoices.value = null
    }

    fun dismissValidationError() {
        _uiState.value = _uiState.value.copy(validationError = null)
    }

    /**
     * User-initiated "Send report" from the inline error card. Routes the current
     * [ProxyError] through the same [ai.darshj.djproxy.vpn.seams.CriticalFailureSink] seam core
     * uses for crashes/engine-death — the diagnostics lane (if attached) decides how to turn this
     * into a mailto report. Always wrapped by [FeatureRegistry.reportCritical] (runCatching), a
     * no-op if there is no current error or no diagnostics lane attached.
     */
    fun sendErrorReport() {
        val error = _uiState.value.validationError ?: return
        FeatureRegistry.reportCritical(
            CriticalFailure(
                category = CriticalFailure.Category.BRINGUP_FAILED,
                reason = "${error.message} — ${error.hint}",
            ),
        )
        LogBus.i("UI", "User requested a diagnostic report for: ${error.message}")
    }

    // ---------------------------------------------------------------------------------------------
    // --- adblock mode (DESIGN_V6 §Block-ads) ---
    // Additive lane wiring, exactly mirroring the Tor section's "read the lane's own Gateway holder"
    // pattern (core's FeatureRegistry may not gain an adblockController slot). The single source of
    // truth is AdblockController.enabled (a StateFlow), so the toggle chip + settings switch stay in
    // sync with the CONNECT-time predicate with zero extra state here. The lane's Registrar runs at
    // process start (androidx.startup) before this ViewModel exists, so the controller is present;
    // the empty-flow fallback keeps a lane-absent build honest (the chip is hidden by the ui anyway).
    // ---------------------------------------------------------------------------------------------

    /** Reflects [ai.darshj.djproxy.adblock.AdblockController.enabled]; false when the lane is absent. */
    val blockAdsMode: StateFlow<Boolean> =
        ai.darshj.djproxy.adblock.AdblockGateway.controller?.enabled
            ?: MutableStateFlow(false).asStateFlow()

    /** Toggle ad/tracker sinkholing. Instant (a volatile write + StateFlow emit); no tunnel re-plumb. */
    fun setAdBlockMode(on: Boolean) {
        ai.darshj.djproxy.adblock.AdblockGateway.controller?.setEnabled(on)
    }

    // ---------------------------------------------------------------------------------------------
    // --- vpngate (DESIGN_V6 §VPN-Gate) ---
    // The vpngate lane is an honest OpenVPN-server BROWSER, not a tunnel. Its catalog + refresh state
    // live in the single VpnGateController the lane's Registrar publishes to VpnGateGateway; the ui
    // reads that Gateway directly for the list/refresh-state (like Tor's bootstrap flow), and only the
    // two mutating actions funnel back through here so they reuse the EXISTING apply/store seams:
    //   - [useVpnGate]  routes the RARE directly-dialable row through applyDialable -> the same
    //                   [reuseConfig]/[VpnController.apply] path every other source uses, and
    //   - [saveVpnGate] persists that row's dial config with FREE_PUBLIC provenance.
    // Export/Share of the .ovpn is a pure ui-side controller.shareOvpn(context, ..) call (needs a
    // Context this ViewModel deliberately does not hold), so it is not mirrored here.
    // ---------------------------------------------------------------------------------------------

    private var vpnGateController: ai.darshj.djproxy.vpngate.VpnGateController? = null

    /** Bind to the single controller the VpnGateRegistrar published. Called once by the host activity. */
    fun attachVpnGate() {
        vpnGateController = ai.darshj.djproxy.vpngate.VpnGateGateway.controller
    }

    /** Dial the rare directly-dialable VPN Gate row through the existing apply path (no-op otherwise). */
    fun useVpnGate(server: ai.darshj.djproxy.vpngate.VpnGateServer) {
        val ctl = vpnGateController ?: ai.darshj.djproxy.vpngate.VpnGateGateway.controller ?: return
        viewModelScope.launch {
            ctl.applyDialable(server) { config -> reuseConfig(config) }
        }
    }

    /** Persist a directly-dialable VPN Gate row into the vault, keeping FREE_PUBLIC provenance. */
    fun saveVpnGate(server: ai.darshj.djproxy.vpngate.VpnGateServer) {
        val store = _store.value ?: return
        val config = server.dialConfig ?: return
        viewModelScope.launch {
            store.save(server.hostName, config, ProxyOrigin.FREE_PUBLIC)
        }
    }
}
