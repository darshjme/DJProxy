# DJProxy — Project State & Build Reference

> Definitive reference for the DJProxy Android app. Read this first to understand what it does, how
> it's built, and the hard-won gotchas. Owner: Darshankumar Joshi. Repo: `darshjme/DJProxy`. Local:
> `D:\AI\DJProxy`. Package `ai.darshj.djproxy` (debug `.debug`). Namespace `ai.darshj.djproxy`,
> minSdk 21, targetSdk 35, compileSdk 35. Free / MIT (the `:openvpn3` module is GPL-2.0 — see Licensing).

---

## 1. What it is

A **device-wide, fail-closed VPN/proxy app**: routes ALL device traffic through one upstream with every
leak closed by construction (no WebRTC/DNS/IPv6 leak, UDP dropped, fail-CLOSED on upstream death). One
tap secures the whole phone. Upstreams supported (all verified working on-device):

| Route | Engine | How it works |
|---|---|---|
| **Cloudflare WARP (free)** | WireGuard-go direct-tun | one-tap free VPN; WARP account auto-registered & cached |
| **Any WireGuard** (own Oracle/VPS, any peer) | WireGuard-go | paste a `.conf` via Import → connects |
| **VPN Gate OpenVPN servers** | **OpenVPN3 C++ core** | official core; handles tls-auth/tls-crypt/NCP/inline PKI |
| **SOCKS5 / HTTP proxies** | native tun2socks (hev) | manual, saved-vault, or the live green-checked free list |
| **Tor** | embedded tor-android | `.onion` browsing, loopback SOCKS5 127.0.0.1:9050 |

Plus: **ad/tracker blocking** (CONNECT-time sinkhole), **mock location** (exit-geo), **LAN hotspot share**,
QS tiles, home-screen widget, QR scan/import, diagnostics with crash email.

---

## 2. Architecture — the lane pattern

Core VPN (`vpn/ engine/ net/ proxy/ core/ cpp/`) is **frozen**: never edited by feature work. Each
feature is a self-contained **lane** wired via **androidx.startup Initializer** (`*Registrar`, guarded)
→ a process-global **Gateway** holder → the core reads holders behind `runCatching` (fail-open).

- **Proxy core** — `DjVpnService` (owns tun + the ONE `VpnService.protect()` seam), out-of-process native
  `hev-socks5-tunnel` in `:engine` (a native crash can't tear down the tun → fail-closed), `TunRouter`,
  `LocalSocksServer`, `DnsInterceptor` (DoH/DoT/TCP, resolves at exit), `PreflightValidator` (real
  connect+handshake+probe before bring-up).
- **wireguard/** — `WgEngineController` (WARP auto-register/cache + `.conf` parse), `WgDirectVpnService`
  (own VpnService, drives the tun fd DIRECTLY via `Ovpnsocks.startWireguardTun`), SOCKS-mode fallback.
- **ovpn3/** — `Ovpn3EngineController`, `Ovpn3VpnService` (own VpnService, feeds the real tun fd to the
  OpenVPN3 `ClientAPI` via `TunBuilder`, `socket_protect`→`VpnService.protect`), `Ovpn3Client` (SWIG
  subclass). Consumes the `:openvpn3` module.
- **ovpnengine/** — legacy userspace OpenVPN (ooni/minivpn) → local SOCKS5. Superseded by ovpn3 for VPN
  Gate (kept for reference; it fails VPN Gate with `EOF`).
- **freeproxy/** — multi-source fetch (9 HTTPS lists) → `FreeProxyHealthChecker` (28-wide concurrent sweep
  via the real `PreflightValidator`, GREEN = `ValidationResult.Success`) → shows ONLY live, latency-sorted;
  green snapshot persisted.
- **vpngate/** — VPN Gate CSV catalog (http://www.vpngate.net/api/iphone/) + saved-`.ovpn` vault.
- **tor/ · adblock/ · location/ · hotspot/ · diag/** — the other lanes.
- **Connection label seam** — `VpnRuntime.sourceLabel` set at every connect site; the status line names
  WHAT is connected (WARP / VPN Gate · country / Saved / Free / Manual / Tor) instead of a loopback addr.

---

## 3. Build recipes

### 3.1 Main APK (Kotlin/Compose)
Gradle 9.1.0 (`~/.gradle/wrapper/dists/gradle-9.1.0-all/.../bin/gradle.bat`), JDK 21, SDK at
`D:\AI\android-sdk` (platform 35, build-tools 34.0.0, NDK r27 `27.2.12479018`, cmake 3.22.1).
`gradle :app:assembleDebug` / `:app:assembleRelease`. Release is unsigned → zipalign + apksigner with
`djproxy-release.jks` (pw `djproxy`, CN=DJProxy). SWIG must be on PATH for the `:openvpn3` native step
(`D:\AI\swig-extract\swigwin-4.2.1`).

### 3.2 ovpnsocks.aar (gomobile — WireGuard + WARP + legacy OpenVPN engine)
Source `ovpnengine-src/` (Go). Toolchain: **Go 1.18** at `D:\AI\go118\go` (gVisor netstack pins `!go1.19`),
pinned **gomobile+gobind `@v0.0.0-20220722155234-aaac322e2105`** installed into `GOBIN=D:\AI\gopath\bin`
(the `C:\Users\darsh\go\bin` one is a modern build → breaks), JDK 11 at `D:\AI\jdk11\jdk-11.0.31+11`, NDK r27.
PATH order: **jdk11\bin FIRST** (else newer javac rejects `-source 1.7`), then gopath\bin, then go118\go\bin.
**SKIP `gomobile init`** (tries gobind@latest→Go1.23). x/crypto pinned `@v0.0.0-20211215153901-e495a2d5b3d3`
(pre `crypto/ecdh`, Go1.18-safe) for curve25519. wireguard core stays `@20210424` + netstack `@20220202` +
go118/netip `@20211105` (mutually version-locked — do NOT bump). Use `conn.NewStdNetBind()` NOT
`NewDefaultBind()` (raw linux bind → EPERM on Android). Then:
`gomobile bind -target="android/arm64,android/arm,android/386,android/amd64" -androidapi 21 -o app/libs/ovpnsocks.aar ./`

### 3.3 :openvpn3 native (libovpn3.so — OpenVPN3 core)
Vendored ics-openvpn cpp core copied into `openvpn3/src/main/cpp/` (openssl/asio/mbedtls/lzo/lz4/fmt/
openvpn3). Self-contained gradle module (`openvpn3/build.gradle.kts`) using THIS repo's toolchain — do
NOT run ics-openvpn's own build.gradle (Windows path-syntax error). Requires **SWIG** (swigwin-4.2.1) +
**SDK cmake 3.22.1** (`sdkmanager "cmake;3.22.1"`). CRITICAL: `cmake { targets += "ovpn3" }` — skips the
legacy OpenVPN-2.x target (its `init.c` fails NDK r27 clang "basename implicit-decl"). Produces
`libovpn3.so` ×4 ABIs (~11 min). SWIG java (`net/openvpn/ovpn3/*.java`, 42 files) is generated by CMake
into `.cxx/.../ovpn3/` and **committed** to `openvpn3/src/main/java/net/openvpn/ovpn3/`.

---

## 4. Hard-won gotchas (don't re-discover)

1. **R8 strips JNI-by-name methods** → any native lib whose C side up-calls Java by name needs keep rules:
   `net.openvpn.ovpn3.**` (SWIG directors — else VPN Gate connect dies "no static method
   SwigDirector_..._tun_builder_new"), `ovpnsocks.**` (gomobile), `org.torproject.**`. Debug (no R8) hides these.
2. **FGS ordering**: every own-VpnService must call `startForeground()` FIRST in `onStartCommand` for ALL
   actions, guarded — else `ForegroundServiceDidNotStartInTimeException` crashes the app on connect
   (WgDirectVpnService + Ovpn3VpnService both had this).
3. **androidx.startup Initializers run BEFORE Application.onCreate** → install `CrashCatcher` in
   `attachBaseContext`, and guard every `*Registrar.create()` with runCatching (a lane fault must not crash
   cold-init — this was the LDPlayer "instant crash on icon" bug).
4. **VPN consent**: `establish()==null` ⇒ OS consent missing ⇒ request it (consent gate + auto-retry
   observer on `ProxyError.VpnPermissionRequired`), don't dead-end.
5. **minivpn v0.0.3 cannot connect VPN Gate** (drops inline `<ca>/<cert>/<key>`, no tls-auth/NCP → `EOF`).
   OpenVPN3 is the fix. Don't try to upgrade minivpn (toolchain-incompatible + still no tls-auth).
6. **DJVPN Pro** (`ai.darshj.djvpn`, a DJProxy fork) is a SEPARATE app on the Fold7 that registers as an
   OpenVPN handler and grabs the foreground — it interferes with UI automation of DJProxy (disable via
   `pm disable-user`, re-enable after).
7. **Fold7 quirks for adb testing**: multi-display (screencap needs stderr suppressed device-side), animated
   orb blocks `uiautomator dump` (use screencap + coordinate taps), Samsung pocket-mode overlay blocks
   touch (`settings put system surface_palm_touch 0`), release isn't debuggable (`run-as` can't read
   `last_crash.txt`), debug pkg is `ai.darshj.djproxy.debug` NOT `.djvpn`.

---

## 5. Status — ALL owner-reported issues resolved + verified on Fold7

- ✅ **WARP free VPN** (one tap, device-wide) — `warp=on`.
- ✅ **WARP direct-tun ~3.2× faster** (0.50→1.59 MB/s) — killed the userspace double-stack.
- ✅ **Free-proxy list green-only + rotating** — 600 candidates → 100 live, fastest-first.
- ✅ **VPN Gate OpenVPN connects** (OpenVPN3) — "VPN Gate · Japan (OpenVPN)", exit 219.100.37.234, warp=off.
- ✅ **Paste-your-own WireGuard** (Oracle/VPS) wired.
- ✅ **Connection labels** — names WARP/VPN Gate·country/Saved/Free/Manual/Tor.
- ✅ Cold-init crash fix, VPN-permission auto-request, save-to-vault, app name "DJProxy".
- ✅ 18-agent E2E audit: critical + 6 high fixed.

### Known follow-ups
- Cosmetic: VPN Gate label shows "VPN Gate · VPN Gate · Japan" (sourceLabel + redacted both say it) — dedup.
- Audit leftovers: notification-Disconnect should stop sibling engine lanes (lane self-observation of
  `VpnRuntime.state`); delete-confirm dialog; consent-on-every-onCreate; details-panel raw label; 26 low.
- Optional: WARP endpoint rotation (faster on throttled carriers); WARP+ license; 16 KB native-lib alignment.
- **Licensing**: the `:openvpn3` module is GPL-2.0 (ics-openvpn core) → the shipped combined app is GPL-2.0.
  Add a NOTICE/LICENSE entry before public distribution (owner pre-approved this tradeoff for the fix).

---

## 6. Deliverable
`D:\AI\DJProxy\DJProxy.apk` — signed release (CN=DJProxy, ks pw `djproxy`), `libovpn3.so` ×4 ABIs,
everything above verified on the Fold7 (RZCY71VED0R, Android 16). HEAD `3c84d86`.
