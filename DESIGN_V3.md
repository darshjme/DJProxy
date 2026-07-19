# DJProxy v3 — Architecture SSOT (Single Source of Truth)

Status: **APPROVED FOR BUILD**. This document is authoritative. Implementation lanes build
against the contracts here and MUST NOT drift. Where this file and code disagree, this file wins
until amended.

Owner: Principal Architect. Consumers: `core`, `location`, `hotspot`, `diagnostics`, `ui`, `compat`.

Package roots (unchanged): `ai.darshj.djproxy` (release), `ai.darshj.djproxy.debug` (debug).

---

## 0. The four failures this release retires + two new features

| # | Failure (v2, real-device) | v3 fix (this doc) |
|---|---------------------------|-------------------|
| 1 | Leak self-test **hard-gates** CONNECTED; throws `LeakException`; scary UI even when TCP works | §2 Advisory self-test. CONNECTED = engine up **+** real in-tun TCP probe. Leak checks are post-connect **health indicators**; a failure NEVER tears down, throws, or blocks — it shows a chip and (DNS) triggers fallback. |
| 2 | DNS broken on residential SOCKS5 (they block outbound TCP:53) | §3 Pluggable `DnsResolver`. **Primary = DoH on :443** through the proxy. Ordered fallbacks DoT:853 → DoT-style, TCP:53. All resolve **through the proxy exit** (no geo-leak). Tested. |
| 3 | App crashed ("app closed") | §4 Crash-proof model: global uncaught handler, `CoroutineExceptionHandler` on every scope, VpnService can never take down UI, `:engine` death degrades fail-closed + reconnect. |
| 4 | Must run on ANY Android + ANY emulator | §7 minSdk 21, 4 ABIs, `CapabilityDetector`, honest emulator-bypass notice, every capability degrades gracefully. |
| 5 | **NEW** Location spoofing to match proxy exit geo | §5 `LocationController` seam + tiers (unrooted needs Dev-Options mock-app grant; honest capability state). |
| 6 | **NEW** VPN Wi-Fi hotspot / router | §6 `HotspotController` seam. Honest tiers: (a) LAN proxy endpoint that truly works root-free, (b) root iptables redirect, (c) always-honest reporting. |
| + | Diagnostic email (carried from prior turn) | §8 `CriticalFailureSink` seam → mailto report; redacts credentials; no backend. |

Design mantra holds: **fail-closed for traffic, fail-open for advice.** Leaks are still closed by
construction (routes/DNS sentinel unchanged); what changes is that *observability of a residual leak
is advisory, not a gate.*

---

## 1. Component map (v3)

```
                          ┌──────────────────────────── UI process ─────────────────────────────┐
                          │  ui/  (Compose, glassmorphism)                                        │
                          │   ProxyScreen · SettingsScreen · AdvisoryChips · ErrorCard            │
                          │        │ binds                                                        │
                          │        ▼                                                              │
                          │   VpnController (seam, core)                                          │
                          └────────┼─────────────────────────────────────────────────────────────┘
                                   │ in-process holder (VpnRuntime / FeatureRegistry)
      ┌────────────────────────────┼───────────────────────────────────────────────────────────┐
      │  main process              ▼                                                              │
      │   DjVpnService (core) ── owns tun, routes, the ONE protect() seam, wakelock               │
      │     ├─ TunBuilder            (routes 0/0 + ::/0 blackhole + DNS sentinel)                 │
      │     ├─ TunRouter             (packet pump; v6/frag/UDP drop; DNS → DnsInterceptor)        │
      │     ├─ DnsInterceptor ──────► DnsResolver (composite: DoH443 ▸ DoT853 ▸ TCP53)   §3       │
      │     ├─ ConnectivityProbe     (in-tun TCP probe = CONNECTED criterion)          §2         │
      │     ├─ HealthMonitor         (post-connect advisory: v6/udp/dns)              §2          │
      │     ├─ EngineWatchdog        (reconnect/give-up; fail-closed)                             │
      │     ├─ LoopbackProxy         (LocalSocksServer, proxy lane; engine dials it)              │
      │     └─ RemoteEngine ─────────► :engine process (native hev-socks5-tunnel)                 │
      │                                                                                            │
      │   FeatureRegistry (core) — nullable holders, set by feature Initializers:                 │
      │     · dnsResolverFactory   (default provided by core)                                     │
      │     · locationController   ◄── location lane      §5                                      │
      │     · hotspotController    ◄── hotspot lane        §6                                      │
      │     · criticalFailureSink  ◄── diagnostics lane    §8                                      │
      │     · settingsPanels[]     ◄── any lane → ui renders                                       │
      └────────────────────────────────────────────────────────────────────────────────────────┘
```

Lanes plug in **only** through `FeatureRegistry` holders + the seam interfaces in §9. No feature lane
edits `DjVpnService`, `LeakPolicy`, `TunBuilder`, or any core file.

---

## 2. Advisory self-test model (fixes failure #1)

### 2.1 New CONNECTED criteria (exact)

The service transitions `CONNECTING → CONNECTED` iff **both**:

1. `RemoteEngine.state == EngineState.Running` (native loop up, socketpair plumbed), **and**
2. `ConnectivityProbe.run()` returns success — a **real TCP round-trip through the tun**:
   open an *un-protected* `Socket` to `PROBE_HOST:80` (shipped default **`1.1.1.1`**, path `/`),
   which is routed by the OS into the tun → engine → upstream proxy, write a minimal HTTP GET,
   read a status line. Success = any HTTP status line parsed within `PROBE_TIMEOUT_MS` (default
   8000, 2 attempts, 1.5s backoff).

   **Hardening applied at ship (post-review):** the probe target is an **IP literal**, not a
   hostname (the original draft used `www.gstatic.com`/`/generate_204`). CONNECTED must not itself
   depend on in-tun DNS working — DNS is exactly the subsystem most likely to be degraded on a
   residential exit (failure #2). Dialing a literal means the upstream proxy connects directly, no
   resolution needed, so a DNS outage surfaces only as the post-connect `dns` health chip, never as
   a bring-up failure.

Rationale: pre-flight already proved the *proxy* speaks TCP; the in-tun probe proves the *whole data
path* (tun→router→engine→loopback→dialer→proxy) works end-to-end. That is the honest definition of
"connected". Leak posture is **not** part of this decision.

`ConnectivityProbe` NEVER throws to the caller — it returns `ProbeOutcome.Ok(latencyMs, exitIpOrNull)`
or `ProbeOutcome.Fail(ProxyError)`. On `Fail`, bring-up fails closed (as today) — but that is a *data
path* failure, not a *leak* failure.

### 2.2 Post-connect health flow (advisory)

After CONNECTED is published, `HealthMonitor.start()` runs the former leak probes as **indicators**,
on an interval (first pass immediately, then every `HEALTH_INTERVAL_MS`, default 60s) and on
network-change:

- `ipv6Reachable`  — v6 literal connect; expected unreachable (blackholed). Reachable ⇒ advisory
  chip "IPv6 may leak".
- `udpEscapes`     — UDP send to a non-sentinel resolver; expected dropped. Reply received ⇒ chip
  "UDP/WebRTC may leak".
- `dnsThroughProxy`— resolve a known name via the active `DnsResolver`; success ⇒ healthy.
  **Failure ⇒ (a) chip "DNS degraded", (b) `DnsResolver` advances its strategy order (DoH→DoT→TCP53)
  automatically for subsequent queries.** Never a teardown.

`HealthMonitor` guarantees: **no probe can throw**, block the tunnel, or change `VpnStage`. Every
probe body is wrapped; failures degrade to `unknown`. Output is a `HealthReport` (see §2.3) published
into `VpnState.health`, rendered by ui as non-blocking chips.

Emulator-bypass detection lives here too: if `ConnectivityProbe` succeeded via a *direct* path check
(a protected control socket reaches the net but the in-tun probe latency/behaviour indicates traffic
bypassed the tun — see §7.3 `CapabilityDetector.suspectVpnBypass`), publish an honest
`health.emulatorBypassSuspected = true` chip rather than claiming full protection.

### 2.3 Data-model changes (core, `VpnState.kt`)

`LeakCheckReport` is **repurposed** as an advisory `HealthReport` (rename in code, keep concept).
Booleans become tri-state to allow "unknown":

```kotlin
enum class Health { OK, DEGRADED, UNKNOWN }

data class HealthReport(
    val ipv6: Health = Health.UNKNOWN,          // OK = blackholed as intended
    val udp: Health = Health.UNKNOWN,           // OK = dropped as intended
    val dns: Health = Health.UNKNOWN,           // OK = resolved through proxy
    val activeDnsStrategy: String = "",         // "DoH:443" | "DoT:853" | "TCP:53"
    val emulatorBypassSuspected: Boolean = false,
    val checkedAtMs: Long = 0,
) {
    val hasWarnings: Boolean get() =
        ipv6 == Health.DEGRADED || udp == Health.DEGRADED ||
        dns == Health.DEGRADED || emulatorBypassSuspected
    // NOTE: there is NO `allPass` gate anymore. Nothing blocks CONNECTED on this.
}
```

`VpnState` gains `val health: HealthReport? = null` (replaces `leakChecks`). `VpnStage` unchanged.
`LeakException` and the `allPass` gate in `DjVpnService.bringUp` are **deleted**.

---

## 3. Pluggable DNS resolver strategy (fixes failure #2)

### 3.1 Why DoH-443 primary

Residential SOCKS5 exits (nsocks/iproyal/luxsocks) routinely block outbound TCP:53 and UDP:53. Port
443 is the whole reason a proxy exists, so it survives. Therefore the **primary** DNS transport is
**DoH — DNS-over-HTTPS on :443** carried through the proxy CONNECT tunnel, so resolution happens at
the **proxy exit** (correct geo, no DNS leak).

### 3.2 The seam — `DnsResolver` (core, `dns/DnsResolver.kt`)

```kotlin
/** One pluggable DNS transport. Resolves a raw DNS *message* (no length prefix) through the proxy. */
interface DnsResolver {
    /** Stable label for logs/health, e.g. "DoH:443". */
    val label: String
    /**
     * @param query raw DNS query message (the exact bytes the app put on the wire, ID included).
     * @return raw DNS answer message (ID matching query), or null on failure (caller decides fallback).
     * MUST NOT throw. MUST resolve through the injected proxy dialer (exit-side resolution).
     */
    suspend fun resolve(query: ByteArray): ByteArray?
}
```

### 3.3 Implementations (all core, `dns/`)

All receive an `UpstreamDialer` (the proxy dialer) via constructor; all protect()ed by construction
because the dialer already protects.

- **`DohResolver`** (`dns/DohResolver.kt`) — primary. Connects to the endpoint's **IP literal** on
  :443 (no bootstrap DNS needed), wraps the tunnelled socket in an `SSLSocket`, then HTTP/1.1:
  `POST /dns-query` with `Content-Type: application/dns-message`, `Accept: application/dns-message`,
  `Content-Length`, `Connection: close`, body = raw query bytes. Read status; on 200 read
  `Content-Length` bytes = answer. Default resolvers, ordered: `cloudflare-dns.com` (1.1.1.1, 1.0.0.1),
  `dns.google` (8.8.8.8). TLS cert validation is ALWAYS ON — never skipped — but the *mechanism*
  is SDK-gated (`DohResolver.shouldUseSniApis`, hardening applied at ship): API 24+ sets
  `SSLParameters.serverNames` + `endpointIdentificationAlgorithm = "HTTPS"` before the handshake so
  the platform verifies the cert against the DoH host; API 21–23 lack those APIs entirely (they
  throw `NoSuchMethodError` if called), so on that range the code completes the handshake and then
  verifies the hostname manually with `HttpsURLConnection.getDefaultHostnameVerifier()` — same
  guarantee, different mechanism, never a silently-skipped check. `DotResolver` reuses this same
  guarded TLS wrap (`DohResolver.tlsWrapPort`) so it never re-introduces the crash on 21–23.
  Suspend-native (`resolveVia` is a `suspend fun` awaiting `dialer.connect` directly): an earlier
  draft bridged the IO-dispatched dialer through `runBlocking`, which parked a second IO-pool thread
  per resolution and defeated structured cancellation; removed at ship. Timeout `DNS_TIMEOUT_MS`.
- **`DotResolver`** (`dns/DotResolver.kt`) — fallback 1. `dialer.connect(dotHost, 853)` → TLS →
  RFC 7858 2-byte length-prefixed DNS message exchange. Same suspend-native + SDK-guarded TLS fix
  as `DohResolver`.
- **`DnsOverTcpResolver`** (`dns/DnsOverTcpResolver.kt`) — fallback 2. Exactly the v2 behaviour
  (RFC 7766, 2-byte length prefix to `dnsServer:53` through the proxy). Kept because some proxies
  DO allow :53 and it is the cheapest path when they do.
- **`CompositeDnsResolver`** (`dns/CompositeDnsResolver.kt`) — orchestrates an **ordered list**
  `[DoH, DoT, TCP53]`. `resolve` tries the current head; on null, advances to the next and remembers
  the first that works as the new head (sticky, so we don't re-pay failures every query). Exposes
  `currentLabel` for `HealthReport.activeDnsStrategy`. This is the object `DnsInterceptor` holds.

### 3.4 Wiring (core)

`DnsInterceptor` (in `LeakPolicy.kt`, core) no longer speaks TCP itself — it delegates to a
`DnsResolver` obtained from `FeatureRegistry.dnsResolverFactory(config, dialer)`. Core ships the
default factory that builds `CompositeDnsResolver([DoH, DoT, TCP53])`. The factory is overridable
(tests inject a fake; a future lane could add a custom resolver) but core provides a working default
so no lane is required to wire it.

The caching/coalescing/`withId` logic in v2 `DnsInterceptor` is **retained** and wraps whatever
`DnsResolver` is active (cache key unchanged). Truncation/TC-bit path unchanged.

### 3.5 Cache-friendliness with geo

DNS answers are cached by question key (unchanged). Because every strategy resolves at the exit, geo
is consistent. TTL cap unchanged (60s) so a proxy switch cannot serve stale geo indefinitely.

### 3.6 Tests (core, `dns/` test package)

- `DohResolverTest` — against a local TLS test server returning a fixed `application/dns-message`.
- `CompositeDnsResolverTest` — DoH fails → falls to DoT → falls to TCP53; head becomes sticky;
  `currentLabel` reflects the winner; all-fail returns null (no throw).
- `DnsMessageTest` — question-key parse, ID rewrite, length framing (moved out of `LeakPolicy`).

---

## 4. Crash-proofing model (fixes failure #3)

Invariant: **a fault anywhere degrades the tunnel fail-closed and is reported; it never crashes the
process or the UI.**

1. **Global net** (core, `DjProxyApp.kt`): `Thread.setDefaultUncaughtExceptionHandler` installs a
   `CrashCatcher` (core, `vpn/CrashCatcher.kt`) that: (a) writes the stack + minimal build/device
   info to `filesDir/last_crash.txt`, (b) calls `FeatureRegistry.criticalFailureSink?.onCriticalFailure(...)`
   with category `UNCAUGHT`, (c) delegates to the previous default handler so the platform still
   records it. Never swallows silently.
2. **Every `CoroutineScope`** created by core carries a `CoroutineExceptionHandler` that logs to
   `LogBus`, reports to the sink for critical categories, and — for the VpnService scope — routes to
   `failClosed(...)` instead of propagating. A shared helper `Coro.safeScope(name, onFatal)` (core,
   `vpn/Coro.kt`) is the only way scopes are built in core; each `launch` that touches IO also uses
   `runCatching`/try around blocking calls (already the pattern in `TunRouter`).
3. **VpnService isolation**: `DjVpnService` runs in the main process but its scope is a
   `SupervisorJob`; no child failure cancels siblings, and no exception is allowed to reach the
   service's lifecycle callbacks unhandled (each `onStartCommand` branch body is wrapped). A bring-up
   failure calls `teardown` + `stopSelf`, never rethrows.
4. **`:engine` death** (already isolated by process): surfaces as `EngineState.Crashed` →
   `EngineWatchdog` holds routes, reconnects with backoff; on unrecoverable, `onGiveUp` →
   `failClosed` + `criticalFailureSink(ENGINE_DEATH)`. A native SIGSEGV can never touch the tun or UI.
5. **Feature-lane isolation**: calls into `locationController` / `hotspotController` /
   `criticalFailureSink` from core are **always** wrapped (`runCatching`), so a buggy feature lane can
   never crash bring-up. This is mandated in §9.

`CriticalFailure` categories: `SELF_TEST` (retained for taxonomy though no longer a hard-fail),
`ENGINE_DEATH`, `UNCAUGHT`, `BRINGUP_FAILED`.

---

## 5. Location spoofing (feature #5) — `location` lane

### 5.1 Capability tiers (honest)

| Tier | Precondition | What we do | Honest status shown |
|------|--------------|------------|---------------------|
| T0 none | No mock-app grant, not root, not emulator-geo | Resolve + **display** exit geo (informational). **GPS NOT spoofed.** | "Location not spoofed — grant Mock location app in Developer Options" (deep-link + guide) |
| T1 mock-app | User set DJProxy as *Select mock location app* (Dev Options) | `LocationManager.addTestProvider` for `gps`/`network`/`fused`; `setTestProviderEnabled(true)`; push exit-geo `Location` at interval; `PASSIVE`/fused test loc via `FusedLocationProviderClient.setMockMode` when Play Services present | "Location spoofed → <city, country> (via Developer-Options mock)" |
| T-EMU emulator | AVD/Genymotion geo channel available | Prefer test-provider path (works on AVD); document telnet `geo fix` for manual | "Emulator location set" |
| T-ROOT root | Root detected | Same as T1 without needing Dev-Options grant (can grant `ACCESS_MOCK_LOCATION` via `appops`); optional | "Location spoofed (root)" |

We NEVER claim GPS is spoofed when the grant is absent — capability state drives the copy.

### 5.2 Exit-geo resolution (through the proxy)

On CONNECTED, core calls `locationController.onProxyConnected(exitIp)` (exitIp from
`ConnectivityProbe`, may be null). The location lane resolves lat/long via an **IP-geo lookup made
THROUGH the proxy/DoH** so the lookup sees the exit IP: it uses
`VpnDependencies.dialerFactory(VpnRuntime.currentConfig!!, VpnRuntime.protector)` to obtain a proxied
dialer, then HTTPS-GETs a geo endpoint (e.g. `ipinfo.io/geo` or `ip-api.com`) over TLS through that
dialer. If `exitIp` is null it queries the endpoint's self-IP form (still exits at the proxy). Manual
lat/long override (`setManualLocation(lat,lng)`) takes precedence and skips lookup.

### 5.3 The seam — `LocationController` (defined by core, §9.2; implemented by location lane)

Location lane files (all NEW): `location/LocationControllerImpl.kt`, `location/MockLocationEngine.kt`,
`location/ExitGeoResolver.kt`, `location/LocationCapability.kt`, `location/LocationRegistrar.kt`
(androidx.startup `Initializer` that sets `FeatureRegistry.locationController`), plus tests.

Permissions the location lane needs (added by **compat** to the manifest on request):
`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` (to publish mock), and it reads
`Settings.Secure`/`AppOpsManager` to detect the mock grant. `FusedLocationProviderClient` is optional
(guarded: only if `com.google.android.gms:play-services-location` present — compat adds the dep;
degrade to `LocationManager`-only if absent, e.g. de-Googled/emulator).

UI: a "Location" settings panel (registered via `FeatureRegistry.settingsPanels`) rendered by the
`ui` lane, showing capability state, exit geo, a manual override, and the Dev-Options deep-link.

---

## 6. Hotspot / router (feature #6) — `hotspot` lane

### 6.1 The brutal truth (documented in-app)

On stock **unrooted** Android, tethered/hotspot client traffic egresses on the upstream interface,
**bypassing** `VpnService` (the tun). So "turn on hotspot + VPN" does **NOT** proxy the clients. We
do not pretend otherwise.

### 6.2 Honest tiers

| Tier | Mechanism | Reality |
|------|-----------|---------|
| **(a) LAN proxy endpoint** (default, root-free, ACTUALLY works) | A `LanShareServer` binds an HTTP-CONNECT + SOCKS5 listener on the concrete LAN/hotspot address `HotspotCapability.localLanAddress()` — **never `0.0.0.0`** (see hardening below). Other devices set their proxy to `phoneIP:port`. That traffic enters our server → `UpstreamDialer` → upstream proxy exit. | Genuinely carries client traffic through the paid proxy. Clients must set a proxy (per-app or Wi-Fi advanced). |
| **(b) root transparent** | Detect root; if present, offer `iptables`/`ip rule` to redirect tethered subnet into the tun/`LanShareServer`. | Transparent (no client config) but requires root. Behind a root check; off by default. |
| **(c) reporting** | `HotspotCapability` always tells the truth about what's active and what clients must do. | Always honest. |

Ship (a) working + documented, (b) behind root check, (c) always. **Plus:** QR / one-tap that encodes
`phoneIP:port` (and optional PAC URL) so another device configures itself in one scan.

**Hardening applied at ship (post-review, HIGH finding):** the original draft allowed an
optional no-auth mode and would bind to any interface it was told to. Both were removed:

- **Auth is always forced on.** `HotspotControllerImpl.startLanShare` ignores its `requireAuth`
  parameter's intent to ever be `false` in practice — a random per-session credential (`LanCredential`)
  is always generated, and the "no auth" toggle that existed in an earlier `HotspotSettingsPanel`
  draft was deleted from the UI entirely. Rationale: the LAN share is a live relay over the user's
  paid, identity-bearing proxy exit; an unauthenticated endpoint on an untrusted Wi-Fi/hotspot
  network is an open relay for anyone on that network.
- **Bind-address is asserted, not assumed.** After `LanShareServer.start()`, the controller checks
  `server.boundAddress?.isAnyLocalAddress` and refuses the share (tearing the server back down) if it
  ever resolved to a wildcard bind — defence in depth against `localLanAddress()` ever returning a
  bad value.

### 6.3 LanShareServer details (hotspot lane, NEW)

- Reuses core's proxy path: constructs a dialer via
  `VpnDependencies.dialerFactory(VpnRuntime.currentConfig!!, VpnRuntime.protector)` (reads core public
  API; does not edit core). Every client connection dials the upstream proxy through that — so LAN
  clients egress at the proxy exit, same as the phone.
- Binds to the hotspot iface IP (discovered via `NetworkInterface`, typically `192.168.43.1` /
  `192.168.x.1`). Auth is always required (random per-session cred shown in the QR/settings panel) —
  there is no unauthenticated mode.
- Only runs while the tunnel is CONNECTED; stops on teardown (hotspot lane observes `VpnRuntime.state`).
- Security: never binds `0.0.0.0` — always the concrete LAN address, asserted by the controller.
- **SSRF guard (HIGH finding, fixed at ship):** every SOCKS5 and HTTP-CONNECT/absolute-URI target is
  checked by `LanShareServer.isBlockedTarget` before dialing. IP literals resolving to loopback,
  link-local, site-local (RFC 1918), multicast, unspecified, IPv4 CGNAT (`100.64.0.0/10`, not covered
  by `isSiteLocalAddress`), or IPv6 unique-local (`fc00::/7`) are refused — a stranger on the shared
  LAN cannot use the share to pivot into the phone's own network. Hostnames are not screened here
  (they resolve at the proxy exit, not locally), only literals.
- **In-flight teardown on stop (HIGH finding, fixed at ship):** `stop()` used to close only the
  listening `ServerSocket`; already-accepted client↔upstream pumps kept forwarding after "Stop
  sharing" because nothing tracked them. `LanShareServer` now keeps a `live` set of every client and
  upstream socket for the duration of `handle()`, and `stop()` closes every socket in that set —
  proven by the `stop_closes_inflight_tunnels` test.
- **Constant-time credential compare:** both the SOCKS5 username/password sub-negotiation and the
  HTTP `Proxy-Authorization: Basic` check use `MessageDigest.isEqual` rather than `String.equals`, so
  response timing cannot leak how many leading credential bytes matched.

### 6.4 The seam — `HotspotController` (core §9.3; implemented by hotspot lane)

Hotspot lane files (all NEW): `hotspot/HotspotControllerImpl.kt`, `hotspot/LanShareServer.kt`,
`hotspot/RootRedirector.kt`, `hotspot/HotspotCapability.kt`, `hotspot/QrPayload.kt`,
`hotspot/HotspotRegistrar.kt` (Initializer), plus tests. UI panel via `settingsPanels`.
Compat adds any manifest bits (`ACCESS_WIFI_STATE`, `NEARBY_WIFI_DEVICES neverForLocation` for
API 33+; `ACCESS_NETWORK_STATE` already present). `CHANGE_WIFI_STATE` is intentionally **not**
declared — the app never toggles Wi-Fi/tethering itself, it only discovers the LAN address to bind
to, so requesting that permission would be over-broad.

### 6.5 RootRedirector chain hygiene (MEDIUM finding, fixed at ship)

The draft `apply()`/`revert()` scripts wrote directly into the platform's generic `FORWARD` and
`nat POSTROUTING` chains. On `revert()`, a naive "delete our ACCEPT/MASQUERADE rule" risked matching
and deleting a rule some *other* app or the platform itself had installed with the same shape. The
shipped `RootRedirector.buildApplyScript`/`buildRevertScript` route every accept/masquerade rule
through two dedicated, DJProxy-owned chains — `DJPROXY_FWD` (filter) and `DJPROXY_NAT` (nat) — and
only ever jump to them from `FORWARD`/`POSTROUTING`. `revert()` deletes the jump, flushes, and drops
those two chains; it never touches a rule it did not create.

---

## 7. Compatibility matrix (fixes failure #4) — `compat` lane

### 7.1 minSdk 21, targetSdk 35, 4 ABIs

`build.gradle.kts`: `minSdk = 21`. ABIs unchanged (`arm64-v8a, armeabi-v7a, x86, x86_64`) — one
universal APK for phones + x86 emulators. NDK r27 (16KB-page clean) unchanged.

### 7.2 API-level branch table (must all be guarded)

| API surface | Min | Guard / fallback |
|-------------|-----|------------------|
| `startForeground(type)` SPECIAL_USE | 34 | `<34`: `startForeground(id, n)` no type (already partly done — extend down to 21) |
| Notification channel | 26 | `<26`: legacy `Notification.Builder(ctx)` (already done) |
| `POST_NOTIFICATIONS` runtime perm | 33 | `<33`: no runtime request (manifest perm harmless) |
| `FOREGROUND_SERVICE` perm | 28 | manifest perm ignored `<28` |
| `Builder.setMetered` | 29 | guarded (already) |
| AndroidKeyStore AES/GCM (CredentialStore) | 23 | **`21–22`: no AndroidKeyStore GCM** → fallback: do NOT persist password (session-only); persist non-secret fields; on restart prompt re-entry. Never store plaintext. `CredentialStore.keystoreAvailable(sdkInt)` gates every call site; `encrypt`/`decrypt` catch `Throwable` (not just `Exception`) because `KeyGenParameterSpec`/`KeyProperties` throw `NoClassDefFoundError` — an `Error`, not an `Exception` — below API 23 if ever reached without the guard (HIGH finding, fixed at ship). |
| TLS SNI / endpoint-identification (`SNIHostName`, `setServerNames`, `setEndpointIdentificationAlgorithm`) | 24 | **`21–23`: APIs absent, throw `NoSuchMethodError` if called.** `DohResolver.shouldUseSniApis(sdkInt)` gates them; below 24, complete the handshake anyway and verify the hostname manually via `HttpsURLConnection.getDefaultHostnameVerifier()`. Certificate/hostname verification is never skipped, only the mechanism differs. (HIGH finding, fixed at ship — this crashed DoH/DoT bring-up on 21–23 before the guard.) |
| `LocationManager.addTestProvider` new signature | 30 | branch old/new ctor (location lane) |
| `FusedLocationProviderClient.setMockMode` | needs GMS | optional; degrade to LocationManager |
| IPv6 route `::/0`, `setBlocking` | 21 | OK |
| `WifiManager`/tether reflection | varies | hotspot lane reflects defensively; degrade to LAN endpoint |

### 7.3 `CapabilityDetector` (compat, NEW `compat/CapabilityDetector.kt`)

Single source for runtime capability truth, read by ui + core + feature lanes:

```kotlin
object CapabilityDetector {
    fun isEmulator(): Boolean            // Build.FINGERPRINT/HARDWARE/PRODUCT: generic|goldfish|ranchu|vbox|nox|ldplayer|bluestacks|genymotion
    fun emulatorName(): String?
    fun hasPlayServices(ctx): Boolean
    fun isRooted(): Boolean              // su binary / test-keys / magisk paths (best-effort)
    fun mockLocationGranted(ctx): Boolean
    fun apiLevel(): Int = Build.VERSION.SDK_INT
    /** Heuristic: in-tun probe failing while a protected control socket reaches the net ⇒ VPN bypass. */
    fun suspectVpnBypass(inTunOk: Boolean, controlNetOk: Boolean): Boolean
}
```

Emulator honesty: LDPlayer/BlueStacks/Genymotion frequently route around the guest VPN. When
`isEmulator()` and `suspectVpnBypass(...)`, `HealthMonitor` sets `emulatorBypassSuspected` and ui
shows a **non-blocking** notice: "Emulator networking may bypass the VPN — traffic might not be
proxied." We never silently misbehave; we tell the truth.

### 7.4 Graceful degradation rule

Every new capability (location mock, fused, hotspot tether, keystore GCM, DoH) MUST check its
precondition via `CapabilityDetector`/try and degrade to the next honest tier when absent — never
crash, never lie.

---

## 8. Diagnostic email (carried) — `diagnostics` lane

- **Settings toggle** "Send diagnostic reports" (persisted) + a **"Send diagnostic report"** action.
- On a **CRITICAL failure** (`CriticalFailure` category `SELF_TEST` hard-category / `ENGINE_DEATH` /
  `UNCAUGHT` / `BRINGUP_FAILED`) the diagnostics lane (as the registered `CriticalFailureSink`) offers
  to send (notification + in-app card via ui).
- **Report contents** (assembled by `diag/ReportBuilder.kt`): `LogBus` replay buffer (last 200) +
  build/device info (model, Android ver, API, ABI list, DJProxy versionName/Code) + last
  `HealthReport` (from `VpnRuntime.lastHealthReport`) + failure category/reason + last_crash.txt if
  present.
- **Transport**: `ACTION_SENDTO` `mailto:${BuildConfig.DIAG_RECIPIENT}` prefilled subject/body. The
  recipient is a Gradle `buildConfigField` (`app/build.gradle.kts`), not a hardcoded literal in the
  diagnostics lane, precisely so a third-party fork of this MIT source can blank or replace it and
  never silently mail reports to the original maintainer's inbox. The official build ships the
  maintainer's own address. **No backend, no SMTP, no INTERNET-side send** — the device mail client
  sends only if the user taps Send.
- **Redaction (hard rule)**: NEVER include the proxy password. `ReportBuilder` scrubs: the password is
  never available to it (not in LogBus, not in state), and it additionally regex-scrubs anything
  resembling `://user:pass@` and `password=`/`token:` tokens as defence-in-depth. Redaction is
  unit-tested (`ReportBuilderRedactionTest`).
- **Crash trace attachment**: `ReportBuilder.collect` reads `CrashCatcher`'s `filesDir/last_crash.txt`
  if present, folds it into the report body under "Last captured crash", then **deletes the file** so
  a stale crash is never attached to an unrelated future report.

Diagnostics lane files (all NEW): `diag/DiagnosticsSink.kt` (implements `CriticalFailureSink`),
`diag/ReportBuilder.kt`, `diag/MailIntentFactory.kt`, `diag/DiagPrefs.kt`, `diag/DiagnosticsRegistrar.kt`
(Initializer → `FeatureRegistry.criticalFailureSink` + a `settingsPanel`), plus tests. Reads `LogBus`,
`VpnRuntime.lastHealthReport`, `CapabilityDetector` — all core/compat public API. Does not edit core.

---

## 9. SEAMS — the exact plug-in contracts (defined by `core`)

This is the load-bearing section. Feature lanes implement these in their OWN new files and register
via `FeatureRegistry`. Core calls them (always wrapped in `runCatching`). **No feature lane edits any
core file.**

### 9.1 `FeatureRegistry` (core, NEW `vpn/FeatureRegistry.kt`) — the holder

```kotlin
object FeatureRegistry {
    // Core ships a working default; overridable for tests / custom transports.
    @Volatile var dnsResolverFactory: (ProxyConfig, UpstreamDialer) -> DnsResolver =
        { cfg, dialer -> CompositeDnsResolver.default(cfg, dialer) }

    @Volatile var locationController: LocationController? = null   // set by location lane
    @Volatile var hotspotController:  HotspotController?  = null   // set by hotspot lane
    @Volatile var criticalFailureSink: CriticalFailureSink? = null // set by diagnostics lane

    // Feature lanes contribute settings UI without editing ui/: ui renders these panels.
    val settingsPanels: MutableList<SettingsPanel> = java.util.concurrent.CopyOnWriteArrayList()
    fun addSettingsPanel(p: SettingsPanel) { settingsPanels.add(p) }
}
```

Registration mechanism: each feature lane ships an `androidx.startup.Initializer<Unit>` (its
`*Registrar`) that sets its holder / adds its panel. **Compat** registers each Initializer as
`<meta-data>` under `androidx.startup.InitializationProvider` in `AndroidManifest.xml` (compat is the
single writer of the manifest; it wires the entries the lanes declare). This keeps every lane's logic
in lane-owned files while core stays untouched and DjProxyApp stays core-owned.

### 9.2 `LocationController` (core, NEW `vpn/seams/LocationController.kt`)

```kotlin
interface LocationController {
    val capability: StateFlow<LocationCapability>     // UNAVAILABLE | READY_MOCK | READY_ROOT | READY_EMULATOR
    val current: StateFlow<SpoofedLocation?>          // what we're currently publishing (null = none)
    /** Called by core on CONNECTED with the resolved exit IP (may be null). MUST NOT throw. */
    suspend fun onProxyConnected(exitIp: String?)
    /** Called by core on teardown. MUST NOT throw. */
    fun onProxyDisconnected()
    fun setManualLocation(lat: Double, lng: Double)   // override; null-clears via clearManual()
    fun clearManual()
    fun refreshCapability(ctx: Context)
}
data class SpoofedLocation(val lat: Double, val lng: Double, val label: String, val source: String)
enum class LocationCapability { UNAVAILABLE, READY_MOCK, READY_ROOT, READY_EMULATOR }
```

### 9.3 `HotspotController` (core, NEW `vpn/seams/HotspotController.kt`)

```kotlin
interface HotspotController {
    val capability: StateFlow<HotspotCapability>
    val share: StateFlow<ShareState>                  // Off | LanProxy(addr,port,cred?) | RootTransparent
    /** Start the honest default: LAN proxy endpoint. MUST NOT throw; returns result. */
    suspend fun startLanShare(requireAuth: Boolean): ShareResult
    suspend fun startRootTransparent(): ShareResult   // only if capability allows
    fun stopShare()
    fun qrPayload(): String?                           // encodes phoneIP:port(+cred) for one-tap
    fun refreshCapability(ctx: Context)
}
enum class HotspotCapability { LAN_PROXY_ONLY, ROOT_TRANSPARENT_AVAILABLE, UNAVAILABLE }
sealed interface ShareState { object Off; data class LanProxy(val addr:String,val port:Int,val cred:String?); object RootTransparent : ShareState }
sealed interface ShareResult { data class Ok(val state: ShareState): ShareResult; data class Fail(val reason:String): ShareResult }
```

### 9.4 `CriticalFailureSink` (core, NEW `vpn/seams/CriticalFailureSink.kt`)

```kotlin
interface CriticalFailureSink {
    /** Called by core on a critical failure. MUST NOT throw and MUST return fast (post to own scope). */
    fun onCriticalFailure(failure: CriticalFailure)
}
data class CriticalFailure(
    val category: Category,
    val reason: String,
    val timeMs: Long = System.currentTimeMillis(),
) { enum class Category { SELF_TEST, ENGINE_DEATH, UNCAUGHT, BRINGUP_FAILED } }
```

### 9.5 `SettingsPanel` (core, NEW `vpn/seams/SettingsPanel.kt`)

```kotlin
/** A settings section a feature lane contributes; ui renders title + content in the settings screen. */
interface SettingsPanel {
    val id: String
    val title: String
    val order: Int
    @Composable fun Content()   // rendered inside a GlassSurface by ui
}
```

This lets `location`, `hotspot`, `diagnostics` each own their settings Compose UI **in their own
package** while the `ui` lane owns only the settings *host* screen that iterates
`FeatureRegistry.settingsPanels`. (Feature-lane panels depend on Compose; that is fine — Compose is a
shared dependency, not a core source file.)

### 9.6 `DnsResolver` — see §3.2 (core, `dns/DnsResolver.kt`).

### 9.7 Seam invariants (enforced, CI-greppable where possible)

1. Core calls every seam via `runCatching { ... }` — a feature fault never breaks bring-up.
2. Feature `onProxyConnected`/sink calls MUST be non-throwing and fast (own coroutine scope).
3. Feature lanes read `VpnRuntime.currentConfig`, `VpnRuntime.protector`, `VpnRuntime.state`,
   `VpnDependencies.dialerFactory`, `LogBus`, `CapabilityDetector` (all public) — they never edit them.
4. The ONE `VpnService.protect(` call site stays in `DjVpnService` (existing CI grep unchanged).

---

## 10. Bring-up sequence (v3, exact)

```
apply(config)                                   [VpnController, core]
 └─ field validate → PreflightValidator (real TCP+handshake+probe, exit-side)   [proxy/core]
      └─ on Success → start DjVpnService(ACTION_CONNECT)
DjVpnService.bringUp(config)                    [core]
 1  publish CONNECTING
 2  TunBuilder.configure + establish tun        (routes 0/0 + ::/0 + DNS sentinel)
 3  plumbEngine (socketpair, LocalSocksServer, RemoteEngine.startRemote)
 4  build EngineWatchdog (not observing yet)
 5  startRouter (TunRouter + DnsInterceptor(dnsResolverFactory(config,dialer)))
 6  ConnectivityProbe.run()  ── FAIL → teardown+fail-closed (NOT a leak failure)
                              └─ OK  → connected=true; watchdog.start(); stats.start()
 7  publish CONNECTED (health=null yet)
 8  HealthMonitor.start()  (advisory; runs v6/udp/dns, sets VpnState.health; DNS-fallback on degrade)
 9  runCatching { FeatureRegistry.locationController?.onProxyConnected(probe.exitIp) }
10  (hotspot lane self-observes state; starts LAN share only if user enabled)
teardown(reason)
 · connected=false; stop stats/watchdog/router/engine/loopback; close socketpair; close tun
 · runCatching { locationController?.onProxyDisconnected() }
```

No step 5–10 failure ever throws to the lifecycle or crashes the process (§4).

---

## 11. Beauty / UI language

Keep dark glassmorphism Material 3 (`GlassSurface`, `DjColors`, `DjBackgroundBrush`). New surfaces:

- **Advisory chips** row under the status card: small pill chips (amber for warnings, cyan for
  "healthy"), one per `HealthReport` warning + emulator-bypass notice. Non-blocking, dismissible.
- **Settings screen**: a new route hosting `FeatureRegistry.settingsPanels` (each in a `GlassSurface`)
  + the diagnostics toggle/action. Reached from a gear in the header.
- **Error card**: unchanged component, but leak states never render here anymore (they're chips).

All new toggles/rows reuse existing typography/colour tokens; no new visual language.

---

## 12. Disjoint file-ownership map (authoritative)

See the machine-readable map in the accompanying structured output. Summary:

- **core** — all shared `vpn/**`, `proxy/**`, `engine/**`, `net/**`, `core/**`, `DjProxyApp.kt`, the
  new `dns/**`, the new `vpn/seams/**`, `vpn/FeatureRegistry.kt`, `vpn/CrashCatcher.kt`,
  `vpn/Coro.kt`, `vpn/ConnectivityProbe.kt`, `vpn/HealthMonitor.kt`. (Owns the self-test rewrite in
  `vpn/LeakPolicy.kt` and the CONNECTED-criteria rewrite in `vpn/DjVpnService.kt`.)
- **location** — new `location/**` package + its tests ONLY.
- **hotspot** — new `hotspot/**` package + its tests ONLY.
- **diagnostics** — new `diag/**` package + its tests ONLY.
- **ui** — `ui/**` ONLY (settings host screen, advisory chips, error card, toggles).
- **compat** — `app/build.gradle.kts`, `AndroidManifest.xml`, `res/values/strings.xml`, new
  `compat/CapabilityDetector.kt` (+ test).

No file appears in two lanes. `strings.xml` is owned by **compat** as the single writer; lanes needing
strings hand compat the entries (or use inline Compose strings in their own panels to avoid contention).
The Compose `settingsPanels` mechanism (§9.5) is what lets location/hotspot/diagnostics ship their own
settings UI without touching `ui/**`.

## 13. Test gates (green before merge, per lane)

- core: `dns/*Test` (DoH/Composite/DnsMessage), `ConnectivityProbeTest`, `HealthMonitorTest`
  (advisory never throws/never gates), existing `ValidatorErrorTaxonomyTest`/`TcpFlowTest`/`PacketsTest`.
- location: capability-tier resolution + manual override + exit-geo parse (no network in unit test;
  inject fake dialer).
- hotspot: `LanShareServer` round-trip via fake dialer; QR payload encode; capability report.
- diagnostics: `ReportBuilder` redaction test (password never present), mailto factory.
- compat: `CapabilityDetector` emulator/root heuristics on fixture `Build` values.

Build/verify: `gradle.bat -p D:\AI\DJProxy assembleDebug` then `testDebugUnitTest`, then install on
RZCY71VED0R (real, Android 16) **and** emulator-5554 (LDPlayer, honest-bypass path). Native build
budget 2–4 min.

---

## 14. Shipped state — security-remediation pass (post-§1–13 build)

Everything in §1–13 above is what's in the repo today. After the initial v3 build landed, a security
review of that same code found and fixed the following before the release artifact was signed. Each
is called out at its point of relevance above (§2.1, §3.3, §6.2/6.3/6.5, §7.2, §8); this section is
the one-place summary plus what was verified in-workflow versus what still needs an owner on-device
pass.

**Findings fixed, all confirmed by reading the shipped source:**

- HIGH — LAN-share open relay: forced auth, concrete-address bind assertion, SSRF target blocking (§6.2/6.3).
- HIGH — DoH/DoT crash on API 21–23: SDK-gated SNI path with manual hostname-verify fallback (§3.3/§7.2).
- HIGH — `CredentialStore` `NoClassDefFoundError` on API 21–22: `keystoreAvailable(sdkInt)` guard +
  broadened `Throwable` catch (§7.2).
- HIGH — Fused mock location never publishing: `MockLocationEngine`'s `FusedMock.start()` now sets
  `active = true` *before* calling `push()`, since `push()` short-circuits while `active` is false
  (the original order silently no-op'd every fused fix).
- HIGH — `LanShareServer.stop()` leaking in-flight tunnels: `live` socket set closed on stop (§6.3).
- MEDIUM — `RootRedirector` revert stripping foreign firewall rules: dedicated `DJPROXY_FWD`/
  `DJPROXY_NAT` chains, revert scoped to only those (§6.5).
- LOW — constant-time credential compare (`MessageDigest.isEqual`, §6.3); chunked-transfer decode OOM
  guard in both `DohHttp`/`HttpText` chunked readers (reject an oversized declared chunk length before
  allocating for it); `runBlocking` removed from all three DNS resolver transports in favour of native
  suspend (§3.3); diagnostic recipient moved to `BuildConfig.DIAG_RECIPIENT` (§8); `CrashCatcher`'s
  `last_crash.txt` now read, redacted, folded into the report, and deleted by `ReportBuilder.collect`
  (§8); `ConnectivityProbe` decoupled the CONNECTED gate from in-tun DNS via the IP-literal probe
  target (§2.1).

One unrelated, pre-existing lint false positive (`InvalidFragmentVersionForActivityResult` firing on
a Compose-only, Fragment-free activity) blocked `lintVitalRelease`; it is disabled with a documented
rationale in `app/build.gradle.kts` — every other lint check stays fatal for release.

**Green gates, real output:** `testDebugUnitTest` → 143 tests, 0 failures, 0 errors (17 test files
across core/dns/location/hotspot/diagnostics/compat). `assembleRelease` → BUILD SUCCESSFUL, native
ndk-build across all 4 ABIs. `apksigner verify` → v1+v2 verified, RSA-4096
`CN=DJProxy, O=Darshankumar Joshi`. Manifest/badging inspected directly: `minSdk 21`, `targetSdk 35`,
`native-code: arm64-v8a armeabi-v7a x86 x86_64`, `DjVpnService` + `BIND_VPN_SERVICE` +
`android.net.VpnService` intent-filter + `specialUse` foreground service type present; permission set
is `INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE(+SPECIAL_USE), POST_NOTIFICATIONS, WAKE_LOCK,
ACCESS_WIFI_STATE, NEARBY_WIFI_DEVICES(neverForLocation)` — `ACCESS_FINE/COARSE_LOCATION` and
`CHANGE_WIFI_STATE` are deliberately absent (see the manifest comments and §5.3/§6.4).

**Proven in-workflow:** compilation, all 143 unit tests, the native 4-ABI build, signing, and every
behavioral fix that has a JVM-reachable unit test — the SSRF filter, bound-address≠`0.0.0.0`,
in-flight-teardown, the SNI-guard and Keystore-guard SDK branches, the `RootRedirector` chain-surgery
script builders, and the constant-time compare.

**Not yet proven — needs an owner on-device pass** (no live proxy/geo/hotspot run has happened from
this workflow): a real DoH/DoT TLS handshake against an actual API 21–23 device (the manual-verify
path is unit-tested for its logic, not exercised against a live TLS server on that OS range); tunnel
bring-up + the IP-literal probe through a live nsocks/iproyal/luxsocks exit; the Fused mock-location
publish path on a real device with the Developer-Options grant; LAN-share reachability and auth from
a second physical device; the root transparent-redirect chains on an actually-rooted device; and
emulator (LDPlayer/AVD) bypass behavior end to end. Until that pass runs, treat the health/DNS/
location/hotspot lanes as code-correct and unit-verified, not field-verified.
