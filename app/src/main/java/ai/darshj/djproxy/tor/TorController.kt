package ai.darshj.djproxy.tor

import ai.darshj.djproxy.core.ProxyConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * The tor lane's public seam (§8, mirrors the shape of `LocationController`). The ui lane reads it
 * through [TorGateway.controller] — `FeatureRegistry` is core and may NOT be edited to add a
 * `torController` holder, so the lane owns its own nullable holder instead (the one deliberate
 * divergence from the UX draft, taken to honour the no-core-edit guardrail).
 *
 * ── The elegant integration (ZERO core change) ────────────────────────────────────────────────
 * When the user enables Tor, [start] bootstraps an embedded Tor (guardianproject **tor-android**,
 * run inside a foreground [TorService]). Tor exposes a local SOCKS5 on `127.0.0.1:9050`. The lane
 * then hands the ViewModel a [proxyConfig] = `socks5://127.0.0.1:9050` (no auth). The **existing**
 * `VpnController.apply(config)` validates-then-routes the whole device through that loopback SOCKS —
 * i.e. through Tor. No tunnel/core file is touched; Tor is "just another proxy source" (SSOT §0.1).
 *
 * ── Why `.onion` works with NO special-casing (verify the reasoning; do not special-case) ────────
 * The v3 core already resolves proxied names at the *exit*, not on-device:
 *   1. Chrome asks the OS to resolve `foo.onion`.
 *   2. Core's **MapDNS** hands back a synthetic fake-IP from `100.64.0.0/10` (it never does a real
 *      lookup — a `.onion` has no A record anyway, so any on-device resolver would simply fail).
 *   3. The packet to that fake-IP hits the tun; hev **reverse-maps** the fake-IP back to the literal
 *      name `foo.onion` and issues a SOCKS5 **domain CONNECT** (`CONNECT foo.onion:443`) to the
 *      upstream SOCKS — which, in Tor mode, is `127.0.0.1:9050`.
 *   4. Tor's SOCKS front receives the hostname, recognises the `.onion` TLD, and resolves the hidden
 *      service internally (rendezvous circuit). The name never needed a DNS A record on the device.
 * Because our own app is `addDisallowedApplication`-excluded from the tun, Tor's *own* circuit
 * sockets egress directly (they are not looped back through the tun into themselves). Therefore the
 * existing MapDNS + SOCKS5-domain-CONNECT path carries `.onion` for free — the tor lane adds nothing
 * on the data path; it only produces the `127.0.0.1:9050` [ProxyConfig] and manages Tor's lifecycle.
 *
 * Contract: no method throws. [start] is suspending and returns a typed [TorStartResult]; every other
 * member is a cheap, non-blocking accessor. Bootstrap failure / no network / user-disable are all
 * modelled as ordinary state transitions (see [TorControllerImpl]).
 */
interface TorController {

    /** Live Tor bootstrap percentage, 0..100. Drives the ui's synthetic `PREPARING_TOR` arc (§3). */
    val bootstrapProgress: StateFlow<Int>

    /** True once Tor has fully bootstrapped and the loopback SOCKS5 is ready to accept CONNECTs. */
    val active: StateFlow<Boolean>

    /** Coarse lifecycle for ui copy (never the sole meaning-carrier — pair it with a label). */
    val phase: StateFlow<TorPhase>

    /**
     * Bootstrap Tor and (on success) leave [active] true with the loopback SOCKS5 up. Suspends until
     * a terminal outcome. Idempotent: calling it while already [active] returns [TorStartResult.Started]
     * immediately with the current [proxyConfig] and does not re-bootstrap. MUST NOT throw (a fault is
     * reported as [TorStartResult.Failed]).
     */
    suspend fun start(): TorStartResult

    /** Disable Tor and revert to normal routing. Idempotent. MUST NOT throw. */
    fun stop()

    /**
     * The proxy the ViewModel feeds to the **existing** `VpnController.apply`: `socks5://127.0.0.1:<port>`
     * with NO authentication. The port is 9050 unless the embedded Tor had to fall back to another free
     * port, in which case the real, resolved port is reported here (honest — never a stale 9050).
     */
    fun proxyConfig(): ProxyConfig

    /** The ui's "Tor active" line of truth. Kept here so the copy has a single source. */
    fun readyMessage(): String = "Tor active — .onion enabled"
}

/** Coarse Tor lifecycle. `FAILED` is terminal-until-retry; the ui maps it onto the inline error card. */
enum class TorPhase { IDLE, BOOTSTRAPPING, READY, FAILED }

/** Total outcome of [TorController.start]. Success carries the ready-to-apply loopback [ProxyConfig]. */
sealed interface TorStartResult {
    /** Tor is bootstrapped; [proxyConfig] is `socks5://127.0.0.1:<port>` (no auth), ready for apply(). */
    data class Started(val proxyConfig: ProxyConfig) : TorStartResult

    /** Tor could not bootstrap (no network, timed out, process died). [reason] is user-facing copy. */
    data class Failed(val reason: String) : TorStartResult
}
