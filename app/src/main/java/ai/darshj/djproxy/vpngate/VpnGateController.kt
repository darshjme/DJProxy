package ai.darshj.djproxy.vpngate

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import androidx.core.content.FileProvider
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.vpn.LogBus
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The vpngate lane's public seam (mirrors the shape/role of `tor.TorController`). The ui reads it via
 * [VpnGateGateway.controller] — `FeatureRegistry` is core and may NOT be edited to add a `vpnGateController`
 * slot, so the lane owns its own nullable holder instead (same divergence the tor lane took).
 *
 * HONEST CONTRACT (SSOT `vpngate_scope`): this controls a server **catalog/browser**, NOT a tunnel.
 *   - [refresh] / [loadCache] populate [servers] from [VpnGateSource].
 *   - [connectOvpn] is the v2 one-tap hand-off: it stages the decoded `.ovpn` in app-private cache and
 *     fires an `ACTION_VIEW` `content://` intent (FileProvider) at an EXTERNAL OpenVPN app, excluding
 *     DJProxy itself from the chooser and returning `false` when no external handler exists (the caller
 *     then shows install guidance). DJProxy still does NOT tunnel OpenVPN — this only opens the profile.
 *   - [shareOvpn] / [shareOvpnText] keep the original `ACTION_SEND` EXTRA_TEXT path as the Share fallback.
 *   - [applyDialable] routes the RARE directly-dialable row through the caller's `VpnController.apply`
 *     seam (passed in, so this lane never hard-depends on the vpn controller and stays unit-testable).
 *
 * Contract: no method throws. Every accessor is cheap; [refresh]/[loadCache] are suspending. Serialised
 * so two Refresh taps cannot overlap.
 */
class VpnGateController(
    private val source: VpnGateSource,
) {

    /** The current catalog rows (score desc / ping asc). Empty until a refresh or a cache load. */
    private val _servers = MutableStateFlow<List<VpnGateServer>>(emptyList())
    val servers: StateFlow<List<VpnGateServer>> = _servers.asStateFlow()

    /** Explicit refresh state so the ui shows spinner → count/timestamp → inline error (never silent). */
    private val _refreshState = MutableStateFlow<VpnGateRefreshState>(VpnGateRefreshState.Idle)
    val refreshState: StateFlow<VpnGateRefreshState> = _refreshState.asStateFlow()

    /** Epoch-millis the current [servers] were produced/cached, or `null` before any load. */
    private val _lastFetchedAt = MutableStateFlow<Long?>(null)
    val lastFetchedAt: StateFlow<Long?> = _lastFetchedAt.asStateFlow()

    /** Serialises refresh so overlapping taps don't double-fetch or race the state flows. */
    private val refreshLock = Mutex()

    /**
     * Network refresh of the catalog. Publishes [VpnGateRefreshState.Loading] up front, then either
     * [VpnGateRefreshState.Success] (with the count + fetchedAt) or [VpnGateRefreshState.Error]. On a
     * fetch failure the source itself degrades to stale cache, so a Success can still carry
     * `fromCache = true` rows. Never throws (seam contract).
     */
    suspend fun refresh(force: Boolean = true) {
        try {
            refreshLock.withLock {
                _refreshState.value = VpnGateRefreshState.Loading
                when (val res = source.fetch(force)) {
                    is VpnGateResult.Ok -> {
                        _servers.value = res.servers
                        _lastFetchedAt.value = res.fetchedAt
                        _refreshState.value = VpnGateRefreshState.Success(res.servers.size, res.fetchedAt)
                        LogBus.i(TAG, "VPN Gate catalog: ${res.servers.size} servers (fromCache=${res.fromCache})")
                    }
                    is VpnGateResult.Failed -> {
                        _refreshState.value = VpnGateRefreshState.Error(res.reason)
                        LogBus.w(TAG, "VPN Gate refresh failed: ${res.reason}")
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            _refreshState.value = VpnGateRefreshState.Error(t.message ?: "VPN Gate refresh failed")
        }
    }

    /**
     * Populate [servers] from the LOCAL CACHE ONLY (no network). Used when the tab is first opened so
     * viewing it never opens a socket. A cold cache is a no-op (state stays [VpnGateRefreshState.Idle]).
     */
    suspend fun loadCache() {
        try {
            val cached = source.fetchCachedOnly() ?: return
            _servers.value = cached.servers
            _lastFetchedAt.value = cached.fetchedAt
            if (cached.servers.isNotEmpty()) {
                _refreshState.value = VpnGateRefreshState.Success(cached.servers.size, cached.fetchedAt)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Cache-only read must never surface an error to the ui.
        }
    }

    /**
     * The v2 one-tap **Connect (open in VPN app)** hand-off. Unlike [shareOvpn] (which rides the profile
     * as plain text through a Share sheet), this stages the `.ovpn` as a real file and launches an
     * `ACTION_VIEW` `content://` intent at an external OpenVPN app so a single tap lands the user in that
     * app's import screen. Honest scope is unchanged: DJProxy does NOT tunnel OpenVPN — it only opens the
     * profile in an app that does.
     *
     * Steps (SSOT `vpngate-v2` acceptance):
     *  1. write [ovpn] to `context.cacheDir/ovpn/<sanitized>.ovpn` (app-private, no storage permission);
     *  2. derive `authority = context.packageName + ".fileprovider"` — **never hardcoded**, so it tracks
     *     the `.debug` applicationId suffix — and mint a FileProvider `content://` uri;
     *  3. build `ACTION_VIEW` with MIME `application/x-openvpn-profile` +
     *     `FLAG_GRANT_READ_URI_PERMISSION` + `FLAG_ACTIVITY_NEW_TASK`;
     *  4. enumerate handlers and EXCLUDE DJProxy's own package (it self-registers for this MIME);
     *  5. return `false` when there is definitively no external handler → the caller shows install
     *     guidance. Everything is wrapped in [runCatching]; a missing handler at launch time throws
     *     `ActivityNotFoundException`, which is caught and also yields `false`.
     *
     * @return `true` when the profile was handed to an external app; `false` when no external OpenVPN
     *         app is available (or staging/launch failed) — the caller surfaces the install-an-app copy.
     */
    fun connectOvpn(context: Context, fileBaseName: String, ovpn: String): Boolean = runCatching {
        // (1) Stage the profile in the FileProvider-exported app-private cache dir (res/xml/file_paths).
        val dir = File(context.cacheDir, "ovpn").apply { mkdirs() }
        val file = File(dir, "${sanitizeBaseName(fileBaseName)}.ovpn")
        file.writeText(ovpn)

        // (2) Runtime-derived authority (NEVER hardcoded — survives the .debug applicationId suffix).
        val authority = context.packageName + ".fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)

        // (3) A content:// VIEW of the OpenVPN-profile MIME, with a read grant + a fresh task.
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, OVPN_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        // (4) Enumerate handlers and drop DJProxy itself (it self-registers for this MIME). When the
        //     query sees handlers but every one is us, that is a definitive "no external app".
        val self = context.packageName
        val handlers = context.packageManager.queryIntentActivities(view, 0)
            .mapNotNull { it.activityInfo?.packageName }
        val external = handlers.filter { it != self }

        // (5) Definitive "none" → guide the user. (An EMPTY query is inconclusive on API 30+ without the
        //     <queries> manifest block, so we DON'T bail there — we still attempt the launch below and
        //     let ActivityNotFoundException be the source of truth via the getOrElse guard.)
        if (handlers.isNotEmpty() && external.isEmpty()) {
            LogBus.i(TAG, "connectOvpn: no external OpenVPN app registered for $OVPN_MIME")
            return@runCatching false
        }

        val launch = when (external.size) {
            1 -> Intent(view).setPackage(external.single())                // exactly one → straight in
            else -> Intent.createChooser(view, CHOOSER_TITLE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Keep DJProxy out of its own chooser (API 24+ honours this; older APIs ignore it).
                selfComponents(context, view)?.let { putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, it) }
            }
        }
        context.startActivity(launch)
        true
    }.getOrElse {
        // ActivityNotFoundException (no handler at all) or any staging/launch failure → guide the user.
        LogBus.w(TAG, "connectOvpn failed: ${it.message}")
        false
    }

    /**
     * Hand the decoded `.ovpn` of [server] to another app via a share chooser (`ACTION_SEND` EXTRA_TEXT).
     * The Share **fallback** for [connectOvpn]. No FileProvider, no temp file — the profile rides as text.
     * Safe: swallows any launch failure. Delegates to [shareOvpnText] so saved-profile rows (which hold no
     * [VpnGateServer]) can share the same way.
     */
    fun shareOvpn(context: Context, server: VpnGateServer) =
        shareOvpnText(context, server.hostName, server.ovpn)

    /**
     * Text-based Share of an arbitrary `.ovpn` body — used by both the catalog row ([shareOvpn]) and a
     * saved-profile row (after `OvpnVault.resolve`). Rides the profile as `ACTION_SEND` EXTRA_TEXT so it
     * needs no FileProvider surface. Safe: swallows any launch failure.
     */
    fun shareOvpnText(context: Context, fileBaseName: String, ovpn: String) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "$fileBaseName.ovpn")
                putExtra(Intent.EXTRA_TITLE, "$fileBaseName.ovpn")
                putExtra(Intent.EXTRA_TEXT, ovpn)
            }
            val chooser = Intent.createChooser(send, CHOOSER_TITLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }.onFailure { LogBus.w(TAG, "Share .ovpn failed: ${it.message}") }
    }

    /**
     * DJProxy's OWN activities that resolve [view], as a `ComponentName[]` for [Intent.EXTRA_EXCLUDE_COMPONENTS]
     * so the app never lists itself in its own "open in OpenVPN app" chooser. Null when none resolve (a
     * missing `<queries>` block on API 30+ hides them) — the chooser then falls back to unfiltered, which
     * is acceptable graceful degradation.
     */
    private fun selfComponents(context: Context, view: Intent): Array<Parcelable>? {
        val self = context.packageName
        val components = context.packageManager.queryIntentActivities(view, 0)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName == self }
            .map { ComponentName(it.packageName, it.name) as Parcelable }
        return components.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /** Keep the staged filename to the SSOT-mandated `[A-Za-z0-9._-]` set; empty → a stable fallback. */
    private fun sanitizeBaseName(raw: String): String {
        val cleaned = buildString {
            for (ch in raw.trim()) {
                append(if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "._-") ch else '_')
            }
        }.trim('_')
        return cleaned.ifEmpty { "profile" }
    }

    /**
     * Apply the RARE directly-dialable row through the caller-supplied [apply] seam (the ViewModel passes
     * `vpnController::apply`). Returns false — without calling [apply] — for any OpenVPN-only row, so the
     * ui can never accidentally "connect" a non-proxy VPN Gate server. Never throws.
     *
     * @return true when [server] was dialable and [apply] was invoked; false otherwise.
     */
    suspend fun applyDialable(
        server: VpnGateServer,
        apply: suspend (ProxyConfig) -> Unit,
    ): Boolean {
        val config = server.dialConfig ?: return false
        if (!server.directlyDialable) return false
        return try {
            apply(config)
            true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            LogBus.w(TAG, "VPN Gate apply failed for ${server.hostName}: ${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "VpnGate"

        /** The OpenVPN-profile MIME external apps register for (and DJProxy self-registers for). */
        const val OVPN_MIME = "application/x-openvpn-profile"
        private const val CHOOSER_TITLE = "Open .ovpn in an OpenVPN app"
    }
}

/**
 * Explicit refresh state for the VPN Gate tab so the ui renders spinner → count/timestamp → inline error
 * instead of a silent grey list (parity with the servers-tab free-refresh states, SSOT).
 */
sealed interface VpnGateRefreshState {
    /** Never fetched this process, and no cache surfaced yet. */
    object Idle : VpnGateRefreshState

    /** A refresh is in flight — the ui shows a spinner. */
    object Loading : VpnGateRefreshState

    /** Catalog loaded: [count] rows produced/cached at [fetchedAt] (epoch-millis). */
    data class Success(val count: Int, val fetchedAt: Long) : VpnGateRefreshState

    /** No source reachable and no cache — [reason] is short human copy for the inline error card. */
    data class Error(val reason: String) : VpnGateRefreshState
}
