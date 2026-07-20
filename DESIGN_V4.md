# DJProxy v4 — Design SSOT (UI overhaul + feature expansion)

**Status:** SHIPPED. All seven lanes below (ui/qr/config/hotspot/location/tor/surfaces/platform) are
built and verified in-repo: `assembleRelease` succeeds, `testDebugUnitTest` passes (250 tests / 0
failures / 32 classes), and `apksigner`/`aapt2`/manifest xmltree checks confirm the release APK
matches this document. The blueprint below is otherwise unchanged from the original plan; §14 records
the handful of places the shipped code diverged from it and why — read §14 alongside the section it
corrects rather than trusting the numbers inline above it.

Supersedes nothing in v3 (the tunnel core is frozen); this is a purely **additive** layer over the
shipping v3 app. If code and this document disagree anywhere §14 doesn't call out, the code is the
source of truth (§14 is meant to be exhaustive — file a doc bug if you find a gap).

**Prime directive (unchanged from v3):** the DO-NOT-TOUCH core files (`vpn/DjVpnService`, `LeakPolicy`,
`TunBuilder`, `RemoteEngine`, `CredentialStore`, `ConnectivityProbe`, `HealthMonitor`, `VpnController`,
`VpnState`, `engine/**`, `proxy/{Socks5Dialer,HttpConnectDialer,UpstreamDialer,LocalSocksServer,Validator,ProxyError}`,
`net/**`, `cpp/**`) plus the core-owned seam surfaces (`vpn/FeatureRegistry.kt`, `vpn/seams/**`,
`core/ProxyConfig.kt`) are **read-only** to every lane. Nobody edits them. Every new capability attaches
through the existing extension points below.

---

## 0. The three load-bearing facts everything hangs on

1. **The config is a value; every import path and Tor are just *sources* that produce that value; the hero
   connects it.** All roads converge on `ProxyConfig` → `ProxyViewModel` → the **existing**
   `VpnController.apply(config)`. No source ever touches the tunnel directly.
2. **Lanes attach only through the v3 seams — never by editing core:**
   - `FeatureRegistry` — process-global nullable holders (`locationController`, `hotspotController`,
     `criticalFailureSink`) + `settingsPanels` list + `addSettingsPanel(panel)` (public, de-dups by id).
   - `androidx.startup.Initializer` per lane (a `*Registrar` class), registered as `<meta-data>` under
     `InitializationProvider` by the **platform** lane. Clone `location/LocationRegistrar` verbatim.
   - `VpnRuntime` (read-only from lanes): `state: StateFlow<VpnState>`, `currentConfig`,
     `lastValidatedExitIp`, `lastHealthReport`. Lanes **observe**; core drives.
   - `SettingsPanel` — a lane ships its own Compose settings section; the ui host renders all panels
     generically. `SettingsPanel { id; title; order; @Composable Content() }`.
3. **`FeatureRegistry` has no `torController` holder and we may not add one** (it is core). Therefore the
   **Tor lane owns its own holder** `tor/TorGateway` (same nullable-holder pattern) that the ui lane reads.
   This is the one deliberate divergence from the UX draft, taken to honor the no-core-edit guardrail.

---

## 1. Material 3 Expressive visual language (keep the dark glass)

Dark-first glassmorphism stays. We push **shape, tonal depth, and motion** — not color families. All of §1
is owned by the **ui** lane (`ui/theme/**`, `ui/**`) and **res/values/** (colors/type/strings/styles).

### 1.1 Palette — additive tokens in `ui/theme/Color.kt` (never edit existing values)
```
AccentViolet     = 0xFF8B7BF5   // third stop: cyan→violet→indigo tri-tone sweep
TorPurple        = 0xFF9D6BFF   // Tor mode identity (distinct from brand cyan + status emerald)
TorPurpleDeep    = 0xFF6D28D9
GlowCyan         = AccentCyan.copy(alpha = 0.45f)   // ring bloom only, never fills
GlowEmerald      = Emerald.copy(alpha  = 0.45f)
GlowTor          = TorPurple.copy(alpha = 0.45f)
```
New brushes in `ui/theme/Theme.kt`:
```
DjTorBrush       = linearGradient(TorPurple, TorPurpleDeep)
DjBrandTriBrush  = sweepGradient(AccentCyan, AccentViolet, AccentIndigo, AccentCyan)  // the ring sweep
```
`DjBackgroundBrush` becomes **animatable**: keep the current indigo→charcoal→void radial as the idle target,
add a `torTint: Float (0..1)` param that lerps the inner stop indigo→`TorPurple` over 1.2 s when Tor engages.

### 1.2 Shape
- Bump `GlassSurface` corner radius 24→28 dp (generous expressive containers).
- The hero is **not a circle** — a superellipse/squircle ring built with `androidx.graphics.shapes`
  (`RoundedPolygon` / `MaterialShapes`) that **morphs vertex/lobe count per state** (table in §1.5).
- Import/source buttons are large rounded-square containers ("big shapes as containers"), tri-tone border.

### 1.3 Typography — `ui/theme/Type.kt`
- Promote the `DJProxy` wordmark to `displayMedium` scale, tracking −0.5 sp.
- Stat counters use a **tabular monospace** figure (`DjMono`, `FontFeatureSetting` tnum) so digits don't jitter
  while animating.

### 1.4 Motion tokens — NEW `ui/theme/MotionTokens.kt` (single source; everything references it)
```
val SpatialSpring   = spring<Float>(dampingRatio = 0.55f, stiffness = 380f)   // shape/scale morphs
val ShapeSpring     = spring<Float>(dampingRatio = 0.55f, stiffness = 380f)
val EffectTween     = tween<Float>(durationMillis = 300, easing = EaseOutCubic) // color/alpha, no bounce
val LockEasing      = EaseOutBack                                              // the "click shut"
const val LOCK_MS         = 420
const val BREATH_IDLE_MS  = 1800
const val BREATH_CONN_MS  = 2400
const val SPIN_VALIDATE_MS= 1100
const val SPIN_CONNECT_MS = 800
const val SCREEN_MS       = 280   // AnimatedContent screen swaps, EaseOutCubic
const val CHIP_STAGGER_MS = 60    // per-chip cascade
const val TOR_TINT_MS     = 1200  // atmosphere drift
```
**Reduced-motion:** gate all infinite/breath/spin behind `Settings.Global.ANIMATOR_DURATION_SCALE != 0`;
fall back to static per-state frames + cross-fades. **Color is never the sole meaning-carrier** — every
state keeps its `stageLabel()` word (v2 rule preserved).

### 1.5 The hero — `ui/components/ConnectRing.kt` (NEW; wraps, does not replace, `ConnectButton`)
Keep `ConnectButton.kt` intact as fallback. The ring wraps the same `(stage: VpnStage, onClick)` contract and
the same `stageLabel()`. Composition outside-in: ambient bloom (200 dp) → progress arc (168 dp, `drawArc` on
`DjBrandTriBrush`) → morphing squircle body (128 dp, tappable) → power glyph core (28→32 dp) + state satellite.

| Stage | Body shape | Ring | Color | Motion |
|---|---|---|---|---|
| IDLE | soft 6-lobe squircle | thin dormant outline | cyan tri-sweep | 1800 ms breath (±3%) |
| VALIDATING | tightening circle | indeterminate sweep (0.75 turn) | cyan | spin 1100 ms, glyph dims |
| CONNECTING | slight-squash circle | sweep spins faster + fills | cyan→violet | spin 800 ms |
| CONNECTED | full round squircle | **closed ring, clicks shut** | emerald | 420 ms EaseOutBack lock + 2400 ms calm breath |
| RECONNECTING | pulsing squircle | ring flickers/pulses | amber | urgent 900 ms pulse |
| STOPPING | shrinking | sweep unwinds | cyan | 300 ms collapse |
| ERROR | sharp 4-lobe (hard corners) | broken ring, gap | rose | single shake (±4 dp, 320 ms) then still |
| PREPARING_TOR *(ui synthetic)* | circle | arc = bootstrap % (0→100) | Tor purple | arc tracks real % |

- **The "lock" (signature money moment):** IDLE→CONNECTED = arc closes (EaseOutBack, 420 ms) + bloom flash
  (alpha 0→0.6→0.28, 500 ms) + glyph pop (1.0→1.12→1.0) + light haptic (`HapticFeedbackType.LongPress`).
- **Progress arc** replaces the tiny inner `CircularProgressIndicator`. Determinate for `PREPARING_TOR`
  (`sweepAngle = pct * 3.6f`), indeterminate rotating sweep for VALIDATING/CONNECTING.
- Shape hardening on ERROR / softening on CONNECTED is the emotional core — do not flatten it.

### 1.6 Signature motions elsewhere
- **Screen transitions:** wrap the Home↔Settings↔About swap in `AnimatedContent`
  (`slideInHorizontally + fadeIn`, 280 ms EaseOutCubic; Settings enters from the right). Sheets rise with
  `slideInVertically` spring.
- **Advisory chips:** stagger-in on health publish — each chip `fadeIn + scaleIn(0.85f)`, 60 ms/chip cascade;
  color transitions via `animateColorAsState`, never a hard swap. (`AdvisoryChipsRow` reused verbatim; only
  its entry animation is wrapped by ui.)
- **Stat counters:** throughput / latency / uptime via `animateIntAsState` — numbers roll, never snap.
- **Splash hand-off** (§7).

---

## 2. Information architecture & screen map (ui lane)

Replace the v3 boolean soup (`showOnboarding`/`showSettings`) with one `rememberSaveable` sealed route in a
NEW `ui/Route.kt` (no nav dependency):
```
sealed interface Route { data object Home; data object Settings; data object About }
enum class HomeSheet { None, Import, Scan, ManualEdit, ShareLan, TorInfo }
```
`Home` owns a nullable `activeSheet: HomeSheet`; sheets are M3 `ModalBottomSheet`. Onboarding stays a pre-Home
gate exactly as v3.

**Depth tiers (everything that is not "tap to connect" collapses upward):**
```
TIER 0 HERO      always-visible ConnectRing + one live line of truth (redacted source)
TIER 1 SOURCE    one row: Paste · Scan · Import · Tor(toggle)   ← M3 adaptive button group
TIER 2 SHEETS    Import · Scan · ManualEdit · ShareLan · TorInfo (ModalBottomSheet)
TIER 3 SETTINGS  NetworkInfo · Location · Hotspot · Diagnostics · Tor(panel) · About
```
Home at rest shows only: brand, ring, StageLabel + redacted line, source strip, advisory chips
(connected-only), and a collapsed **Details disclosure** (`ui/DetailsDisclosure.kt`, NEW) holding the relocated
`StatusCard` + animated stats + `LogView`. `ProxyFields` and `LogView` **leave the home surface** — they were
the v3 clutter — into the ManualEdit sheet and Details disclosure respectively.

**Source strip = `ui/SourceStrip.kt` (NEW).** Paste/Scan/Import open sheets; **Tor is a toggle chip**
(`ui/TorToggleChip.kt`) showing on/off + bootstrap %. When Tor is ON, Paste/Scan/Import are disabled with an
honest one-liner: *"Using Tor — turn off to use a custom proxy."* Tor toggle is hidden entirely when
`TorGateway.controller == null` (lane absent — honest capability, same rule as location/hotspot).

Full map:
```
Splash (branded ~900 ms Compose hand-off) → Onboarding gate (first run) →
Route.Home { ConnectRing + StageLabel + redacted line; SourceStrip; chips(connected); Details }
  sheets: Import(Paste/Scan/Subscription/File) · Scan · ManualEdit · ShareLan · TorInfo
  entry: LAUNCHER · djproxy:// · VIEW(proxy uri) · SEND(text) · .ovpn open · QS tile · widget
Route.Settings { NetworkInfoPanel · Location · Hotspot · Diagnostics · Tor · →About }
Route.About { version · licenses · source link }
```

---

## 3. Connect-state flow (the only authority stays `VpnState.stage`)

Tor adds a **UI-layer pre-stage** because Tor bootstrap happens *before* `apply()` (the lane must produce the
`127.0.0.1:9050` config first). It is synthesized by the ViewModel — **never a new core enum**:
```
tap ring (idle/error)
   Tor OFF ─────────────────────────────┐
   Tor ON → [UI PREPARING_TOR] arc=boot% ┘ on 100% → produce socks5://127.0.0.1:9050
                    ▼
              VALIDATING → CONNECTING → { CONNECTED | RECONNECTING | ERROR }
```
- `ProxyViewModel` (ui) gains `torMode: StateFlow<Boolean>` and synthesizes `PREPARING_TOR` while collecting
  `TorGateway.controller.bootstrapProgress`; on ready it calls the **existing** `onApply(socks5://127.0.0.1:9050)`.
- Tor bootstrap failure surfaces as a normal `ProxyError` in the **existing** inline error card
  ("Tor could not start — …"). No special channel.
- Success flash reuses `ProxyUiState.justSucceeded` → emerald lock + haptic.
- `busy`-guard on the ring (no taps during VALIDATING/CONNECTING/STOPPING) is preserved from `ConnectButton`.

**Two error languages stay walled apart (v2 hard-won rule):** blocking `ProxyError` → inline rose card;
advisory `HealthReport` → non-blocking chips. A DEGRADED chip and a CONNECTED ring coexist. Health degradation
is never rendered in the error channel and vice-versa.

---

## 4. QR scanning (qr lane — NEW `qr/` package)

**Stack decision:** **CameraX + ZXing core** (NOT ML Kit). Rationale: DJProxy ships to de-Googled ROMs and
x86 emulators; `play-services-location` is already an *optional, runtime-guarded* dep. ML Kit barcode pulls a
hard Play-services/Google dependency and ~mem overhead; ZXing `core` is a tiny pure-JVM decoder that also does
**encoding** (needed for the hotspot LAN-share QR), so one 3.5 MB dep covers scan **and** generate, fully
offline. CameraX gives the lifecycle-safe preview + analysis pipeline.

**Files (qr lane owns all):**
- `qr/QrCameraScanner.kt` — Composable: `@Composable fun QrCameraScanner(onResult: (String) -> Unit,
  onError: (String) -> Unit, modifier)`. Hosts `PreviewView` (camera-view) + `ImageAnalysis` bound to
  `LifecycleCameraController`; torch toggle; the pulsing cyan reticle (breath curve, from MotionTokens).
- `qr/ZxingLuminanceAnalyzer.kt` — `ImageAnalysis.Analyzer` that wraps each `ImageProxy`'s Y-plane in a
  `PlanarYUVLuminanceSource` → `HybridBinarizer` → `MultiFormatReader` (QR hint). Debounces duplicate reads.
- `qr/QrEncoder.kt` — `fun encodeQr(text: String, sizePx: Int): ImageBitmap` via ZXing `QRCodeWriter` →
  `BitMatrix` → `ImageBitmap`. Used by the ShareLan sheet.
- `qr/CameraPermission.kt` — small helper that surfaces the CAMERA-permission rationale state (the ui
  ScanSheet renders it; the actual `ActivityResultContracts.RequestPermission` launch is triggered from the
  Composable via `rememberLauncherForActivityResult`, so no MainActivity edit needed).

**Seam to ui:** the ui `ScanSheet` (Tier 2, inside Import too) is pure bottom-sheet chrome that embeds
`qr.QrCameraScanner(onResult = viewModel::ingestExternal)`. ui never imports CameraX/ZXing directly.

---

## 5. Config import (config lane — extend `core/ProxyParser.kt` + NEW `config/` package)

**Every front door converges on one facade → `ProxyConfig` or a typed error → the same ViewModel path.**

**Files (config lane owns):**
- `core/ProxyParser.kt` **(extend only — do not rewrite the 7 paste formats)**: keep the existing
  `parse(raw, default)`; the new URI/subscription/ovpn logic lives in `config/`. ProxyParser gains one thin
  delegator so a `://` scheme it does not itself speak is handed to `config.UriConfigParser` instead of the
  current blunt "Unknown proxy scheme" error.
- `config/ConfigImporter.kt` — the facade the ViewModel calls:
  `fun import(raw: String): ImportResult` where `raw` may be a single line, a URI, a subscription URL, or
  `.ovpn` text. Sniffs the shape, dispatches, returns `ImportResult.Single(ProxyConfig)` /
  `ImportResult.Many(List<NamedConfig>)` / `ImportResult.Rejected(ImportError)`.
- `config/UriConfigParser.kt` — URI schemes:
  - `socks5://user:pass@host:port`, `http://host:port` → direct `ProxyConfig`.
  - `ss://` SIP002: `ss://base64(method:pass)@host:port#name`. If `method` ∈ the small set hev/our SOCKS path
    can front (or is a plain userinfo we can map), produce a `ProxyConfig`; **Shadowsocks AEAD ciphers DJProxy
    cannot terminate → `ImportError.UnsupportedProtocol("ss", cipher)`** with the honest message.
  - Best-effort `vmess://`, `vless://`, `trojan://`, `hysteria2://`: parse host:port + auth; if a plain
    SOCKS/HTTP mapping is impossible (they are full obfuscation/transport protocols DJProxy does not speak)
    → **reject with a named typed error**, never a fake success.
- `config/SubscriptionFetcher.kt` — `suspend fun fetch(url: String): ImportResult`. HTTPS GET → the body is
  base64 of newline-separated config URIs → decode → map each via `UriConfigParser` → `ImportResult.Many`
  (name + host:port + redacted). Uses a plain `HttpsURLConnection` off the main thread (no new dep). Never
  auto-connects; the ui pick-list chooses one; remember last-selected.
- `config/OvpnParser.kt` — parse **proxy-relevant directives only**: `http-proxy HOST PORT` /
  `socks-proxy HOST PORT` → `ProxyConfig`; read `remote HOST PORT` + `proto` only as fallback context. Ignore
  all PKI/crypto. If there is **no** proxy directive →
  `ImportError.OvpnNotAProxy` ("This .ovpn is a full VPN config, not a proxy. DJProxy routes SOCKS/HTTP
  proxies only."). DJProxy is a SOCKS/HTTP proxy app, **not** an OpenVPN client — say so honestly.
- `config/ImportError.kt` — typed, each carries `message` + `hint`, mapped to `ProxyError.Io(message)` for the
  existing inline card. Named variants: `UnsupportedProtocol(scheme, detail)`, `OvpnNotAProxy`,
  `MalformedUri(scheme)`, `SubscriptionUnreachable`, `EmptySubscription`.

**How the UI triggers it:** the Import sheet's four tabs (Paste/Scan/Subscription/File) and the intent
ingestion (§7) all call `viewModel.ingestExternal(raw, autoConnect)` → `ConfigImporter.import(raw)`. On
`Single` → fill `ProxyFields` (animate up into the redacted line). On `Many` → pick-list. On `Rejected` →
the existing rose card with the **named** protocol. One code path, four front doors.

---

## 6. Hotspot completion (hotspot lane — `hotspot/**`)

The seam (`vpn/seams/HotspotController`) and most of the impl already exist (`HotspotControllerImpl`,
`LanShareServer` 572 lines, `RootRedirector`, `QrPayload`, `HotspotSettingsPanel`, `HotspotRegistrar`).
Completion = wire honest tiers end-to-end and expose the QR payload for the ShareLan sheet.

- **UNROOTED tier — `LAN_PROXY_ONLY`:** `LanShareServer` binds a SOCKS5/HTTP endpoint on the hotspot/LAN iface
  (discover the AP IP — it is **not** fixed at 192.168.43.1 since Android 9; enumerate `NetworkInterface`s for
  the `ap*`/`swlan*`/`softap*`/`rndis*`/`wlan*` non-loopback IPv4). Other devices point their proxy settings at
  `phoneIP:port`. `qrPayload()` returns `phoneIP:port(+cred)`; the ui ShareLan sheet renders it via
  `qr.QrEncoder.encodeQr(...)`. This tier **never claims tethered clients are transparently proxied.**
- **ROOT tier — `ROOT_TRANSPARENT_AVAILABLE`:** `RootRedirector` runs (via `su -c`) the VPNHotspot-style rules:
  `iptables -A FORWARD -j ACCEPT`; `iptables -t nat -A POSTROUTING -o tun0 -j MASQUERADE`; `ip rule`/`ip route`
  to send the discovered hotspot subnet through `tun0`. Advertise this tier **only** when `su` is present AND
  `tun0` exists. Report `HotspotCapability.ROOT_TRANSPARENT_AVAILABLE`; `ShareState.RootTransparent` only after
  the rules apply cleanly.
- **Honest reporting rule:** `capability` StateFlow drives all copy. Render "clients proxied through the
  tunnel" **only** when `share == RootTransparent`. LAN tier copy: "Other devices can use this phone as a proxy
  at `host:port`." The lane self-observes `VpnRuntime.state`/`currentConfig`; core does not drive it. All
  suspend calls return `ShareResult`, never throw (seam invariant).

**ui seam:** `ShareLanSheet` (ui) reads `FeatureRegistry.hotspotController` (nullable), renders `capability`,
`share`, the endpoint, and the QR. Hotspot config also lives in `HotspotSettingsPanel` (Settings).

---

## 7. Mock-location completion (location lane — `location/**`)

Seam + impl already substantially present (`LocationControllerImpl`, `MockLocationEngine` 286 lines,
`ExitGeoResolver` 318 lines, `LocationSettingsPanel`, `LocationRegistrar`). Completion = the end-to-end path
exit-IP → geo → mock provider, plus a self-test.

- **Trigger:** core already calls `LocationController.onProxyConnected(exitIp)` on CONNECTED (exit IP from
  `VpnRuntime.lastValidatedExitIp`, may be null) and `onProxyDisconnected()` on teardown — both `runCatching`,
  both must not throw.
- **Geo:** `ExitGeoResolver` resolves the exit IP → lat/lng over the tunnel (HTTP through the proxy, guarded
  bounds `lat∈[-90,90] lng∈[-180,180]`, reject 0,0). Cache per-IP.
- **Publish:** `MockLocationEngine` tiers by `LocationCapability`:
  - `READY_MOCK` — `LocationManager.addTestProvider` + `setTestProviderLocation` (GPS + network providers).
    Requires the Developer-Options "mock location app" app-op (NOT a runtime permission — none declared, by
    design). Onboarding already guides this grant.
  - `READY_EMULATOR` — emulator console/geo fixtures.
  - `READY_ROOT` — root-assisted where available.
  - `UNAVAILABLE` — grant absent → honest "GPS not spoofed" copy; **never** claim a mock when the app-op is
    off. `capability` drives all `LocationSettingsPanel` copy.
- **Manual override:** `setManualLocation(lat,lng)` takes precedence over exit-geo; `clearManual()` reverts.
- **Self-test (how to verify):** connect a proxy with a known-country exit → confirm `SpoofedLocation.label`
  matches the exit country; open Google Maps "your location" → the blue dot should sit at the mocked lat/lng;
  toggle the mock-app grant off → capability flips to `UNAVAILABLE` and the panel says so. Emulator: use
  `adb emu geo fix` as a control to compare against the published fix.

---

## 8. Tor (tor lane — NEW `tor/` package)

**The elegant integration (zero core change):** when "Enable Tor" is ON, the lane bootstraps Tor, which
exposes a local SOCKS5 on `127.0.0.1:9050`; the lane then hands the ViewModel a
`ProxyConfig(SOCKS5, "127.0.0.1", 9050)` (no auth). The **existing** `VpnController.apply` routes the whole
device through Tor. `.onion` browsing in Chrome works over the **existing MapDNS + SOCKS5 domain-CONNECT**
path (Chrome resolves `foo.onion` → MapDNS fake-IP → hev reverse-maps → `CONNECT foo.onion` → 127.0.0.1:9050 →
Tor resolves the hidden service). Because our app is `addDisallowedApplication`-excluded, Tor's own sockets go
direct, not looped. **No core file is touched.**

**Embed:** guardianproject **tor-android** (Tor binary + lib; ships arm64-v8a/armeabi-v7a/x86/x86_64 — DJProxy's
exact ABIs; API 24+ — Tor is gated at runtime, the app stays minSdk 21) + **jtorctl** (control port). Chained
"after my proxy" is **out of scope for v4** — Tor-alone is the deliverable.

**Files (tor lane owns):**
- `tor/TorController.kt` — the lane's public seam (mirrors LocationController shape):
  `bootstrapProgress: StateFlow<Int>` (0..100), `active: StateFlow<Boolean>`,
  `suspend fun start(): TorStartResult`, `fun stop()`, `fun proxyConfig(): ProxyConfig`
  (= `socks5://127.0.0.1:9050`). Must not throw.
- `tor/TorControllerImpl.kt` — owns `OnionProxyManager`, parses jtorctl `BOOTSTRAP` events → `bootstrapProgress`.
- `tor/TorService.kt` — foreground `Service` wrapping the tor process (Tor must outlive the Activity). Declared
  by **platform** with FGS type `specialUse` subtype "Tor onion routing" (reuses existing FGS permissions).
- `tor/TorGateway.kt` — **the ui seam** (the holder the ui reads, since `FeatureRegistry` may not be edited):
  `object TorGateway { @Volatile var controller: TorController? = null }`. Set by `TorRegistrar`.
- `tor/TorRegistrar.kt` — `androidx.startup.Initializer` (clone of `LocationRegistrar`): sets
  `TorGateway.controller` + `FeatureRegistry.addSettingsPanel(TorSettingsPanel(...))`. Platform adds its
  `<meta-data>` to `InitializationProvider`.
- `tor/TorSettingsPanel.kt` — `SettingsPanel(id="tor", order=…)`: bootstrap %, active state, .onion note,
  restart control.

**ui surfacing (ui lane, §5 creative):** Tor toggle chip in the source strip; while booting, ring is
`PREPARING_TOR` with arc = real bootstrap % and "Building Tor circuit… 47%"; at 100% the lock plays in
**purple**, the atmosphere `DjBackgroundBrush` drifts indigo→`TorPurple` (1.2 s), a second dashed concentric
"onion layers" orbit ring appears (8 s/turn), and a pill rises: **"Tor active · .onion enabled"** (TorPurple,
onion glyph, gentle breath). On disable the purple drains back to indigo (1.2 s). TorInfo sheet shows the same
state + "browse .onion in Chrome now."

---

## 9. Surfaces — one-tap without opening the app (surfaces lane — NEW `surfaces/` + `res/xml/**` + `res/layout/**`)

No new dependency (classic `RemoteViews` widget so it works at minSdk 21; `TileService` is API 24+, guarded).
- `surfaces/ConnectTileService.kt` — QuickSettings `TileService`: toggles connect/stop of the **last-validated**
  config via `VpnRuntime.currentConfig` → starts `DjVpnService` (through the same public start path the app
  uses). Reflects `VpnRuntime.state` in the tile state. Requires an active VPN consent already granted; if not,
  the tile launches `MainActivity` to obtain it (never routes without consent).
- `surfaces/TorTileService.kt` — QuickSettings tile: toggles `TorGateway.controller?.start()/stop()` then
  applies the Tor config through the same path.
- `surfaces/DjWidgetProvider.kt` — home-screen `AppWidgetProvider` (RemoteViews): one big connect/disconnect
  button + a Tor button; reflects state via periodic update + broadcast. Layout in
  `res/layout/widget_djproxy.xml`; config `res/xml/widget_djproxy_info.xml`.
- `surfaces/WidgetActions.kt` — `PendingIntent` action routing (connect/stop/tor) shared by tile + widget.
- **Share-target and deep-link *handling*** lives in `MainActivity` (ui lane) via `viewModel.ingestExternal`;
  the intent-**filters** are declared by platform (§11). The surfaces lane owns only the tile/widget entry
  points and their `res/xml` + `res/layout`.

---

## 10. Branded splash (brand lane — `res/drawable/**`, `res/mipmap/**`, `assets/**`; hand-off in ui)

- Static system-splash layer stays in `Theme.DJProxy.Splash` (themes.xml, **ui-owned res/values**) pointing at
  `@drawable/splash_icon` (**brand-owned drawable**) — different files, disjoint. Brand supplies: the squircle
  power-glyph mark (tri-tone border) as `drawable/splash_icon.*` and adaptive launcher icons
  (`mipmap/**`, `mipmap-anydpi-v26/ic_launcher.xml`), the onion glyph, and any illustration/animated splash art
  in `assets/`. **Brand must keep icon colors self-contained in drawable/vector resources — it may not add to
  `res/values` (ui-owned).**
- **Compose hand-off** (ui lane, `ui/SplashHandoff.kt`, ~900 ms first frame of MainActivity): the mark's ring
  draws itself on (arc 0→360°, EaseOutCubic 600 ms, `DjBrandTriBrush`), `DJProxy` fades + rises 8 dp
  (EaseOutCubic 320 ms, +200 ms delay), `by darshj.ai` fades in beneath (`TextTertiary` `labelMedium`,
  +0.5 sp, +500 ms delay), the ring exhales once into idle breath and content cross-fades in. The splash ring
  **becomes** the ConnectRing (same shape, same brush) — launch flows continuously into the control surface.
  Attribution lockup: `DJProxy` displayMedium TextPrimary · hairline `AccentCyan` dot · `by darshj.ai`
  TextTertiary (the dot is the only color).

---

## 11. Platform lane — the ONLY writer of `AndroidManifest.xml` + `app/build.gradle.kts`

Gather every dep/permission/intent-filter/service the other lanes need from this SSOT so **no other lane edits
the manifest or gradle.**

### 11.1 Dependencies to add (app/build.gradle.kts)
```
// qr lane — CameraX + ZXing (NO ML Kit; offline, de-Googled-safe, encode+decode in one dep)
androidx.camera:camera-core:1.4.1
androidx.camera:camera-camera2:1.4.1
androidx.camera:camera-lifecycle:1.4.1
androidx.camera:camera-view:1.4.1
com.google.zxing:core:3.5.3

// ui lane — expressive morphing squircle ring
androidx.graphics:graphics-shapes:1.0.1

// tor lane — guardianproject tor-android + control port
info.guardianproject:tor-android:0.4.8.7   // verify exact latest on Maven Central at build time
info.guardianproject:jtorctl:0.4.5.7
```
(surfaces uses no new dep. location's `play-services-location` already present. Keep the 4-ABI `abiFilters`;
tor-android matches them.)

### 11.2 Permissions to add
```
android.permission.CAMERA                    <!-- qr scanning -->
```
(INTERNET / FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE / WAKE_LOCK / ACCESS_WIFI_STATE /
NEARBY_WIFI_DEVICES / POST_NOTIFICATIONS already declared and cover Tor's FGS + hotspot LAN discovery. Hotspot
root tier uses `su`, not a manifest permission.)

### 11.3 Manifest additions
**On `<activity .MainActivity>` (already `singleTop`, `exported=true`) — four intent-filters:**
```xml
<!-- 1. custom-scheme deep link: import + connect -->
<intent-filter android:autoVerify="false">
  <action android:name="android.intent.action.VIEW"/>
  <category android:name="android.intent.category.DEFAULT"/>
  <category android:name="android.intent.category.BROWSABLE"/>
  <data android:scheme="djproxy" android:host="import"/>
  <data android:scheme="djproxy" android:host="connect"/>
</intent-filter>
<!-- 2. proxy-URI open-with (appear in chooser for socks5:// / ss:// links) -->
<intent-filter android:autoVerify="false">
  <action android:name="android.intent.action.VIEW"/>
  <category android:name="android.intent.category.DEFAULT"/>
  <category android:name="android.intent.category.BROWSABLE"/>
  <data android:scheme="socks5"/><data android:scheme="ss"/>
</intent-filter>
<!-- 3. share target: text/plain -->
<intent-filter>
  <action android:name="android.intent.action.SEND"/>
  <category android:name="android.intent.category.DEFAULT"/>
  <data android:mimeType="text/plain"/>
</intent-filter>
<!-- 4. .ovpn open/import -->
<intent-filter>
  <action android:name="android.intent.action.VIEW"/>
  <category android:name="android.intent.category.DEFAULT"/>
  <category android:name="android.intent.category.BROWSABLE"/>
  <data android:scheme="content"/><data android:scheme="file"/>
  <data android:mimeType="application/x-openvpn-profile"/>
  <data android:host="*"/><data android:pathPattern=".*\\.ovpn"/>
</intent-filter>
```
**Intent contract (handled in MainActivity.onCreate/onNewIntent, ui lane):** extract raw from `intent.data`
(VIEW) or `EXTRA_TEXT` (SEND) → `viewModel.ingestExternal(raw, autoConnect = host=="connect")`. On success open
Home with the Import sheet pre-filled/parsed (show what will connect); **never auto-connect from an untrusted
SEND/VIEW without showing the parsed target first** (one confirmation tap — security seam). Parse failure →
Import sheet with the typed error. The existing VPN-consent gate always runs first; deep-link never bypasses it.

**New services / providers:**
```xml
<!-- Tor foreground service (tor lane class; platform declares) -->
<service android:name=".tor.TorService" android:exported="false"
         android:foregroundServiceType="specialUse">
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="Tor onion routing"/>
</service>

<!-- QuickSettings tiles (API 24+; harmless meta on older) -->
<service android:name=".surfaces.ConnectTileService" android:exported="true"
         android:icon="@drawable/ic_tile_connect"
         android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
  <intent-filter><action android:name="android.service.quicksettings.action.QS_TILE"/></intent-filter>
</service>
<service android:name=".surfaces.TorTileService" android:exported="true"
         android:icon="@drawable/ic_tile_tor"
         android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
  <intent-filter><action android:name="android.service.quicksettings.action.QS_TILE"/></intent-filter>
</service>

<!-- Home-screen widget -->
<receiver android:name=".surfaces.DjWidgetProvider" android:exported="false">
  <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE"/></intent-filter>
  <meta-data android:name="android.appwidget.provider" android:resource="@xml/widget_djproxy_info"/>
</receiver>
```
**Add the Tor Initializer to the existing `InitializationProvider` `<meta-data>` list:**
```xml
<meta-data android:name="ai.darshj.djproxy.tor.TorRegistrar" android:value="androidx.startup"/>
```
(`ic_tile_connect`/`ic_tile_tor` drawables are brand-owned; `widget_djproxy_info.xml` + `widget_djproxy.xml`
are surfaces-owned.)

---

## 12. Disjoint file-ownership map (charter: no two writers on one file; DO-NOT-TOUCH core in no lane)

Base pkg `app/src/main/java/ai/darshj/djproxy/`, res `app/src/main/res/`. **New files marked (new).**

- **ui** — `ui/**` (incl. `ui/components/**`, `ui/theme/**`, `MainActivity.kt`, `ProxyScreen.kt`,
  `ProxyViewModel.kt`, `SettingsScreen.kt`) + `res/values/**`. New: `ui/Route.kt`, `ui/SourceStrip.kt`,
  `ui/TorToggleChip.kt`, `ui/DetailsDisclosure.kt`, `ui/SplashHandoff.kt`, `ui/components/ConnectRing.kt`,
  `ui/theme/MotionTokens.kt`, `ui/sheets/{ImportSheet,ScanSheet,ManualEditSheet,ShareLanSheet,TorInfoSheet}.kt`.
  Extends `ProxyViewModel` (`ingestExternal`, `torMode`, synthetic `PREPARING_TOR`) and MainActivity intent
  ingestion. Reads (never writes) `FeatureRegistry`, `VpnRuntime`, `tor.TorGateway`, `qr.*`, `config.*`.
- **qr** — `qr/**` (new): `QrCameraScanner.kt`, `ZxingLuminanceAnalyzer.kt`, `QrEncoder.kt`, `CameraPermission.kt`.
- **config** — `core/ProxyParser.kt` (extend) + `config/**` (new): `ConfigImporter.kt`, `UriConfigParser.kt`,
  `SubscriptionFetcher.kt`, `OvpnParser.kt`, `ImportError.kt`.
- **hotspot** — `hotspot/**` (complete): `HotspotControllerImpl.kt`, `LanShareServer.kt`, `RootRedirector.kt`,
  `HotspotCapability.kt`, `QrPayload.kt`, `HotspotSettingsPanel.kt`, `HotspotRegistrar.kt`.
- **location** — `location/**` (complete): `LocationControllerImpl.kt`, `MockLocationEngine.kt`,
  `ExitGeoResolver.kt`, `LocationCapability.kt`, `LocationSettingsPanel.kt`, `LocationRegistrar.kt`.
- **tor** — `tor/**` (new): `TorController.kt`, `TorControllerImpl.kt`, `TorService.kt`, `TorGateway.kt`,
  `TorRegistrar.kt`, `TorSettingsPanel.kt` (+ `OnionProxyManager.kt` if the tor-android wrapper needs it).
- **surfaces** — `surfaces/**` (new): `ConnectTileService.kt`, `TorTileService.kt`, `DjWidgetProvider.kt`,
  `WidgetActions.kt` + `res/xml/widget_djproxy_info.xml` + `res/layout/widget_djproxy.xml`.
- **brand** — `res/drawable/**`, `res/drawable-*dpi/**`, `res/mipmap-*/**`, `res/mipmap-anydpi-v26/**`,
  `assets/**` (launcher/adaptive icons, `splash_icon.*`, `ic_tile_connect`, `ic_tile_tor`, onion glyph,
  squircle mark, splash art). Must not add to `res/values`.
- **platform** — `AndroidManifest.xml` + `app/build.gradle.kts` **only** (all §11 deps/permissions/filters/
  services/tiles/widget/Tor-Initializer).

**Disjointness check:** `res/values/**` → ui only; `res/drawable|mipmap|assets` → brand only;
`res/xml|res/layout` → surfaces only; manifest+gradle → platform only; `core/ProxyParser.kt` → config only;
each java package → exactly one lane. DO-NOT-TOUCH core (`vpn/**` except none are lane-owned, `engine/**`,
`proxy/**` frozen set, `net/**`, `cpp/**`, `core/ProxyConfig.kt`, `vpn/FeatureRegistry.kt`, `vpn/seams/**`)
appears in **no** lane — read-only to all.

---

## 13. Seam summary (so lanes can't drift)

- **config** exposes `config.ConfigImporter.import(raw): ImportResult`; ui/VM call it. Produces `ProxyConfig`
  for the **existing** `VpnController.apply` — no core change.
- **qr** exposes `@Composable qr.QrCameraScanner(onResult,onError,modifier)` and
  `qr.QrEncoder.encodeQr(text,sizePx): ImageBitmap`; ui hosts them.
- **tor** exposes `tor.TorGateway.controller: TorController?` with `bootstrapProgress: StateFlow<Int>`,
  `active: StateFlow<Boolean>`, `start()/stop()`, `proxyConfig(): ProxyConfig(socks5://127.0.0.1:9050)`;
  registers `SettingsPanel(id="tor")` via `FeatureRegistry.addSettingsPanel`. ui reads `TorGateway`; null →
  Tor hidden.
- **hotspot** exposes (via existing `FeatureRegistry.hotspotController`) `capability`, `share`, `qrPayload()`;
  tiers `LAN_PROXY_ONLY` / `ROOT_TRANSPARENT_AVAILABLE` / `UNAVAILABLE`; ShareLan sheet reads it.
- **location** exposes (via existing `FeatureRegistry.locationController`) `capability`, `current`;
  core drives `onProxyConnected(exitIp)` / `onProxyDisconnected()`.
- **surfaces** tiles/widget act through `VpnRuntime.currentConfig` + `tor.TorGateway` + the same start path;
  never route without consent.
- **ui** owns the composition root, the synthetic `PREPARING_TOR` stage, the `Route`/`HomeSheet` model, intent
  ingestion (`ingestExternal`), and every animation; reads all lane seams, writes none of them.
- **platform** is the single writer of manifest + gradle, wiring every lane's deps/filters/services from §11.

---

## 14. Post-implementation deltas (shipped vs. this blueprint)

Recorded once, here, instead of silently editing the numbers above — so anyone diffing the plan
against the repo has a map of exactly where and why they differ.

- **§11.1 dependency version:** `info.guardianproject:tor-android:0.4.8.7` is not resolvable — that
  tag exists only on the guardianproject `gpmaven` repository, which this project does not declare
  (`settings.gradle.kts` pins `repositoriesMode = FAIL_ON_PROJECT_REPOS` with only `google()` +
  `mavenCentral()`, and adding a repository is outside the platform lane's ownership of this file —
  it owns `AndroidManifest.xml` + `app/build.gradle.kts`, not `settings.gradle.kts`). Shipped pin is
  **`0.4.7.14`**, the newest `tor-android` build actually published to Maven Central; its bundled
  manifest declares `minSdkVersion 16` / `targetSdkVersion 33`, fully compatible with this app's
  minSdk 21 / targetSdk 35, so no `<uses-sdk tools:overrideLibrary>` was needed. `jtorctl:0.4.5.7` is
  unchanged from the plan.
- **§9 widget security (post-launch hardening):** `DjWidgetProvider` ships `android:exported="false"`,
  not the implicit default. `onReceive` dispatches privileged `TOGGLE`/`STOP`/`TOR_TOGGLE` actions with
  no consent gate of its own; an exported receiver would let any zero-permission app on the device fire
  an explicit `STOP` broadcast and tear the tunnel down (a kill-switch bypass). The AppWidget host still
  delivers `APPWIDGET_UPDATE` to a non-exported provider, and the widget's own button `PendingIntent`s
  (created via `getBroadcast` by this app) are delivered on the creator's token regardless of export —
  so `exported=false` closes the hole with no loss of function. Treat this as the canonical widget
  manifest shape; do not revert to an implicit/exported receiver.
- **§8 Tor state model:** shipped `TorController` adds a `phase: StateFlow<TorPhase>` (`IDLE` /
  `BOOTSTRAPPING` / `READY` / `FAILED`) alongside `bootstrapProgress` and `active`. This is what lets
  `TorInfoSheet` show an honest "Tor couldn't start" card on a failed bootstrap instead of a `0%`
  progress bar that looks like it's still trying — `phase` was needed to distinguish "never started"
  from "just failed" from `bootstrapProgress` alone.
- **§4 qr lane file split:** the lane additionally ships `qr/QrDecode.kt` — a pure-JVM
  `LumaFrame`/`QrDecoder`/`QrScanHandoff` core with no CameraX/ZXing/Android imports, so the
  frame-to-`ProxyConfig` handoff is unit-testable without a camera. `ZxingQrDecoder` (the real ZXing
  binding) and `ZxingLuminanceAnalyzer` sit on top of it. Not a scope change, just a testability split
  the blueprint's file list didn't enumerate.
- **§9 surfaces lane file split:** `surfaces/TileState.kt` (shared tile on/off/unavailable state) and
  `surfaces/TorBridge.kt` (the tile/widget-facing read of `tor.TorGateway`, kept separate so
  `surfaces/**` doesn't import `tor.*` types directly into `ConnectTileService`/`DjWidgetProvider`)
  were added alongside the four files §9 named. Same ownership (`surfaces` lane), no seam change.
- **Everything else** — the seven-lane split, the `FeatureRegistry`/`VpnRuntime`/`TorGateway` seams,
  the seven-format-plus-URI/subscription/`.ovpn` import funnel, the `ConnectRing` state table, the
  honest hotspot/location capability tiers, and the disjoint file-ownership map — matches this document
  as written; no other corrections were needed.
