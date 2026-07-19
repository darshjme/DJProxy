# DJProxy — DESIGN.md (single source of truth)

Free, open-source (MIT), device-wide proxy VPN for Android. Owner: Darshankumar Joshi.
Package `ai.darshj.djproxy` · minSdk 24 · compileSdk 35 · Compose + Material 3.

This document is authoritative. Five lanes (engine, proxy, vpn, ui, brand) implement against the
Kotlin interfaces defined here and in the shared files listed under **Module map**. If code and this
doc disagree, this doc wins until amended by the single writer.

---

## 1. What it is

A `VpnService` that routes **all** device traffic through **one** user-supplied proxy (SOCKS5 or
HTTP CONNECT, optional user/pass), with leaks closed by construction: no IPv6, no DNS, no WebRTC/UDP,
and hard fail-closed if the proxy is unreachable. One screen, validate-before-up, premium dark UI.

The five hard product requirements (device-wide, no IPv6 leak, no DNS leak, no WebRTC leak, fail-closed,
SOCKS5+HTTP with auth) are not features — they are the acceptance gates in §5.

---

## 2. Architecture

Two processes. The **main** process owns the tun, the routes, and the single `protect()` seam. The
**:engine** process runs vendored native `hev-socks5-tunnel` in isolation so a native crash degrades
fail-closed instead of leaking.

```
                          ┌──────────────────────── main process (ai.darshj.djproxy) ────────────────────────┐
   all device apps        │                                                                                   │
        │ IP packets      │   DjVpnService (VpnService)                                                        │
        ▼                 │     • Builder: addRoute 0.0.0.0/0  +  ::/0 (v6 blackhole in-tun)                   │
   ┌─────────┐  tun fd     │     • addDnsServer 10.111.0.2 (in-tun sentinel)   • MTU 1500                       │
   │  tun    │◄───────────┤     • NO allowBypass, NO per-app lists                                             │
   └─────────┘  owns fd    │     • dup(tunFd) ──────────────┐                                                  │
        ▲                  │                                 │ Binder / ParcelFileDescriptor                    │
        │                  │   LocalSocksServer 127.0.0.1:N  │        ┌──────────── :engine process ──────────┐ │
        │                  │     • SOCKS5 front (no auth)    └──────► │ EngineService (START_STICKY, FGS)      │ │
        │                  │     • picks upstream: SOCKS5 / HTTP CONNECT + user/pass                           │ │
        │                  │     • :53 CONNECT  → DNS-over-TCP to cfg upstream (device resolver NEVER used)    │ │
        │                  │     • UDP: dropped (default) | SOCKS5 UDP ASSOCIATE (opt-in)                      │ │
        │                  │     • upstream handshake fail → RST/refuse (fail-closed)                          │ │
        │                  │     • the ONE protect() call site (via SocketProtector)                          │ │
        │                  │            │ protect()'d upstream sockets                 hev (C/lwIP, IPv4-only) │ │
        │                  │            ▼                                              reads tun fd, forwards  │ │
        │                  │      real proxy (SOCKS5 / HTTP CONNECT)  ◄──────────────  each TCP flow as SOCKS5 │ │
        │                  │                                                           to 127.0.0.1:N          │ │
        └──────────────────┴───────────────────────────────────────────────────────  └───────────────────────┘ │
                           └───────────────────────────────────────────────────────────────────────────────────┘
```

**Data path:** app → tun → (dup fd) hev in :engine terminates TCP via lwIP → SOCKS5 to
`LocalSocksServer` on loopback → policy → `UpstreamDialer` (protected socket) → real proxy → internet.

**Why hev, not a pure-Kotlin userspace stack:** lwIP-grade TCP reassembly/retransmit at line rate in
C, MIT-licensed, tiny, 16KB-page clean. Kotlin owns policy where correctness (leaks, fail-closed)
matters and is easy to test; C owns the hot packet loop. One crucial scaling axis: **per-flow
concurrency at the loopback SOCKS seam** — everything is a TCP flow terminated in C and handed to a
coroutine in Kotlin, so throughput scales with structured-concurrency dialers, not threads.

**Why two processes:** the tun fd lives in main. If native hev crashes, `:engine` dies but the tun +
`0.0.0.0/0` + `::/0` blackhole routes stay UP with nothing forwarding ⇒ traffic drops, never direct.

---

## 3. Leak-proofing model  (vector → mitigation → verification)

| # | Leak vector | Mitigation (how it's closed) | How it is verified |
|---|-------------|------------------------------|--------------------|
| 1 | **Traffic escaping the tunnel** (per-app / bypass) | `Builder.addRoute("0.0.0.0/0", 0)`. **Never** `allowBypass()`. No `addAllowedApplication`/`addDisallowedApplication`. | CI grep: `allowBypass` and `addDisallowedApplication` appear **zero** times. Manual: every app's egress observed on tun only. |
| 2 | **IPv6 leak** | `Builder.addRoute("::/0", 0)` pulls all v6 into the tun; hev is `ipv4Only`, so v6 packets have no handler and die in-tun (blackhole). No v6 upstream ever dialed. | Self-test: connect to an IPv6 literal + AAAA-only host → must be **unreachable/timeout**. Manual: ipleak.net shows no IPv6. CI grep: `addRoute("::/0"` present. |
| 3 | **DNS leak** | `Builder.addDnsServer("10.111.0.2")` (in-tun sentinel). Resolver traffic hits tun → LocalSocksServer intercepts CONNECT-to-`:53` → tunnels **DNS-over-TCP** to `ProxyConfig.dnsServer` through the proxy. Device/system resolver is never queried. | Self-test: `dnsTunnelled` flag — assert queries traverse the tunnel, count via `TunnelStats.dnsQueries`. Manual: browserleaks DNS shows only the proxy egress. |
| 4 | **WebRTC / QUIC leak** | All **UDP dropped** by default (`ProxyConfig.blockUdp = true`) at LocalSocksServer. Kills STUN/TURN srflx gathering and QUIC → browsers fall back to TCP through the proxy. Optional SOCKS5 UDP ASSOCIATE relay, default OFF. | Self-test: STUN/QUIC UDP send → must be dropped (`udpBlocked`, `TunnelStats.udpDropped > 0`). Manual: browserleaks WebRTC shows no srflx/host candidate leaking real IP. |
| 5 | **Fail-open when proxy down** | LocalSocksServer resets/refuses any flow whose upstream handshake fails. Engine crash → routes held, nothing forwards. Validate-before-up: VPN only comes up on a genuine `ValidationResult.Success`. | Automated: kill proxy → assert **zero** egress on non-tun interfaces. Force `:engine` crash → assert routes stay up + traffic drops. Wi-Fi↔cellular handover → assert no leak window. |
| 6 | **Loopback / re-entrancy** (tunnel dialing itself) | Upstream sockets are `protect()`'d at the ONE `SocketProtector` seam before connect; hev only ever dials loopback (never protected, never leaves device). | CI grep: `VpnService.protect` / `.protect(` appears in **exactly one** file. |
| 7 | **IP fragmentation evasion** | `Ipv4Packet.isFragment` → refuse to reassemble; drop fragments. | Unit test on `Packets.kt` fragment flag. |

Self-test result is modelled by `LeakCheckReport` (§ vpn). The service must NOT report `CONNECTED`
until `LeakCheckReport.allPass == true`.

---

## 4. Threat model

- **Adversary:** a passive network observer + the destination server, both trying to learn the
  device's real IP or DNS. **In scope:** IP leak, DNS leak, WebRTC/STUN leak, fail-open on proxy loss,
  process-crash leak, handover leak.
- **Out of scope:** a hostile local app with root/`CAP_NET_ADMIN` (it can bypass any VPN — OS limit);
  traffic-analysis correlation; a malicious *proxy* itself (user chose it — we redact its password, we
  don't trust it with more than transport).
- **Secrets:** the proxy password lives only in `ProxyConfig`, is redacted in every display
  (`ProxyConfig.redacted()`), and is **never** written to `LogBus`. No analytics, no network calls
  except through the tunnel + the pre-flight probe.
- **Assumptions:** proxies are IPv4. If a user needs v6, that is a future toggle, not a silent
  fallback — v6 stays blackholed by default.

---

## 5. Release gates (must all pass before "connected" / before ship)

1. Self-test (on-device, pre-CONNECTED): IPv6 unreachable · UDP dropped · DNS tunnelled → `allPass`.
2. Fail-closed suite: proxy-kill = zero non-tun egress · engine-crash = routes held + drop · handover = no window.
3. Manual: ipleak.net + browserleaks.com clean (no v6, no DNS leak, no WebRTC srflx) on default config.
4. CI greps: `allowBypass`(0) · `addDisallowedApplication`(0) · `addRoute("::/0"`(≥1) · `.protect(`(exactly 1 file).
5. `.so` loads on a 16KB-page Android 15 image; ships `arm64-v8a` + `armeabi-v7a` (x86_64 = separate emulator build).

---

## 6. Apply flow (validate-before-up)

```
paste / fields ─► ProxyParser.parse ─► ProxyConfig ─► ProxyConfig.validate() (field-level)
      │ (two-way synced in UI)                              │ null == ok
      ▼                                                     ▼
   ProxyViewModel ──── apply(config) ───► VpnController.apply(config)
                                             │
                                             ▼  Validator.validate(config)  [SAME UpstreamDialer as live path]
                        ┌────────────────────┴─────────────────────┐
                        │ real TCP connect + real handshake         │
                        │ (SOCKS5 negotiate / HTTP CONNECT)         │
                        │ + real probe request through the proxy    │
                        └────────────────────┬─────────────────────┘
                    Failure(ProxyError)      │      Success(latency, status, exitIp)
                    ▲ typed, human, hinted    ▼
                 stay down (fail-closed)    bring tun up → run leak self-test → CONNECTED
```

`Validator` reuses the exact `UpstreamDialer` the live `LocalSocksServer` uses, so both proxy types
are exercised identically and pre-flight can't diverge from runtime. Every failure is one closed
`ProxyError` case (DNS / refused / timeout / auth / not-SOCKS5 / http-status / connect-refused /
handshake-malformed / probe-failed / io) with a one-line fix hint.

---

## 7. Module map (packages under `ai.darshj.djproxy`)

| Package | Owner lane | Contents |
|---------|-----------|----------|
| `core`  | proxy | `ProxyType`, `ProxyConfig`, `ProxyParser` (**already written**) |
| `net`   | proxy | `Packets.kt`: IPv4/TCP/UDP parse+build, checksums (**already written**) |
| `proxy` | proxy | **`ProxyError.kt`** (shared, written): `ProxyError`, `ValidationResult`, `DialResult`, `SocketProtector`, `UpstreamDialer`, `Validator`. Impl: SOCKS5/HTTP dialers, DNS-over-TCP, `ValidatorImpl`, `LocalSocksServer`. |
| `vpn`   | vpn | **`VpnState.kt` + `TunnelStats.kt` + `LogBus.kt`** (shared, written). Impl: `DjVpnService`, `VpnController` impl, watchdog, the single `SocketProtector`/`protect()` seam. Owns `AndroidManifest.xml`. |
| `engine`| engine | **`EngineContract.kt`** (shared, written): `EngineConfig`, `EngineState`, `EngineStats`, `EngineController`, `HevBridge`. Impl: `EngineService` (:engine process), CMake + vendored hev C. Owns `app/build.gradle.kts` NDK/abiFilters. |
| `ui`    | ui | Compose theme (`ui/theme/Color.kt` etc.), the one screen, `ProxyViewModel` binding to `VpnController.state` + `LogBus.events`. |
| (res)   | brand | `res/values/colors.xml`, mipmap icon, `@string/app_name` — contributed to the manifest **through the vpn owner**. |

**Shared-file ownership (one writer each):** `core/ProxyConfig.kt` + `proxy/ProxyError.kt` → proxy ·
`vpn/VpnState.kt` + `vpn/LogBus.kt` + `vpn/TunnelStats.kt` + `AndroidManifest.xml` → vpn ·
`app/build.gradle.kts` → engine · `res/values/colors.xml` → brand · `ui/theme/Color.kt` → ui.
Other lanes submit diffs to the owner for serial apply. Never two writers on one file.

---

## 8. The interfaces every lane codes against

All of the following are compilable and total (no TODOs). Signatures are frozen; internals are the
lanes'.

### 8.1 `proxy/ProxyError.kt` (proxy lane owns)

```kotlin
sealed class ProxyError(val message: String, val hint: String) {
    data class DnsResolutionFailed(val host: String)          // host un-resolvable
    data class ConnectionRefused(val host: String, port: Int) // nothing listening
    data class Timeout(val phase: String)                     // connect/handshake/probe timed out
    object AuthRejected                                        // bad user/pass
    object NotASocks5Server                                    // wrong protocol on port
    data class HttpStatus(val code: Int, val reason: String)  // HTTP CONNECT 4xx/5xx (not 407)
    data class ConnectRefusedByProxy(val host: String, port: Int)
    data class HandshakeMalformed(val detail: String)
    data class ProbeFailed(val detail: String)
    data class Io(val detail: String)
}

sealed interface ValidationResult {
    data class Success(val latencyMs: Long, val probeStatus: Int, val exitIp: String?) : ValidationResult
    data class Failure(val error: ProxyError) : ValidationResult
}

sealed interface DialResult {
    data class Ok(val socket: java.net.Socket) : DialResult   // already tunnelled to destination
    data class Fail(val error: ProxyError) : DialResult
}

fun interface SocketProtector { fun protect(socket: java.net.Socket): Boolean }  // the ONE protect seam

interface UpstreamDialer { suspend fun connect(host: String, port: Int): DialResult }  // SOCKS5 & HTTP, same path

interface Validator { suspend fun validate(config: ProxyConfig): ValidationResult }
```

### 8.2 `vpn/VpnState.kt`, `vpn/TunnelStats.kt`, `vpn/LogBus.kt` (vpn lane owns)

```kotlin
enum class VpnStage { IDLE, VALIDATING, CONNECTING, CONNECTED, RECONNECTING, STOPPING, ERROR }

data class TunnelStats(bytesUp, bytesDown: Long, activeConnections: Int,
                       totalConnections, udpDropped, dnsQueries: Long)  // companion EMPTY

data class LeakCheckReport(ipv6Unreachable, udpBlocked, dnsTunnelled: Boolean, checkedAtMs: Long) {
    val allPass: Boolean  // all three true — required before CONNECTED
}

data class VpnState(stage: VpnStage, proxyRedacted: String?, connectedSinceMs: Long,
                    stats: TunnelStats, error: ProxyError?, leakChecks: LeakCheckReport?) {
    val isUp: Boolean; val isBusy: Boolean            // companion IDLE
}

interface VpnController {
    val state: StateFlow<VpnState>
    suspend fun apply(config: ProxyConfig): ValidationResult   // validate FIRST, up only on Success
    fun stop()
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }
data class LogEvent(timeMs: Long, level: LogLevel, tag: String, message: String)
object LogBus { val events: SharedFlow<LogEvent>; fun log/d/i/w/e(...) }  // replay 200, drop-oldest
```

### 8.3 `engine/EngineContract.kt` (engine lane owns)

```kotlin
data class EngineConfig(tunFd: Int, mtu: Int, socksHost: String, socksPort: Int,
                        ipv4Only: Boolean, udpRelay: Boolean, logLevel: String, taskStackSize: Int) {
    fun toYaml(): String   // hev_socks5_tunnel config
}

sealed interface EngineState { Stopped; Starting; Running; data class Crashed(exitCode, reason) }
data class EngineStats(txPackets, txBytes, rxPackets, rxBytes: Long)  // companion EMPTY

interface EngineController {
    val state: StateFlow<EngineState>
    fun start(config: EngineConfig)   // blocks until running/failed, idempotent
    fun stop()
    fun stats(): EngineStats
}

object HevBridge {                                   // JNI → vendored hev-socks5-tunnel
    fun load()                                       // loadLibrary("djproxy-engine"), call once in :engine
    external fun runBlocking(configYaml: String, tunFd: Int): Int
    external fun quit()
    external fun statsRaw(): LongArray               // [txPackets, txBytes, rxPackets, rxBytes]
}
```

### 8.4 UI binding contract (ui lane consumes only)

`ProxyViewModel` depends on exactly: `ProxyParser` (paste↔fields), `ProxyConfig` (+ `validate()`,
`redacted()`), `VpnController` (`state`, `apply`, `stop`), `LogBus.events`. It renders `VpnState` and
`ValidationResult`/`ProxyError` (`message` + `hint`) and never touches sockets, the engine, or the tun.

---

## 9. Build & waves

- Build: `& 'D:\AI\gradle\gradle-8.11.1\bin\gradle.bat' -p D:\AI\DJProxy assembleDebug` (no wrapper).
- **Wave 1 (parallel):** engine vendors+builds hev + JNI/config; proxy lands dialers + validator +
  DNS-over-TCP + LocalSocksServer; vpn lands service skeleton + watchdog + protect seam + manifest;
  brand lands icon/colors/strings; ui lands theme + screen + ViewModel against these contracts.
- **Wave 2 (serial, green-gated between clusters — typecheck/test/lint/build):** wire
  EngineService ↔ LocalSocksServer ↔ DjVpnService, then run §5 leak + fail-closed gates.
- Shared files: one writer each (§7). Parallel READ/audit, serial coherent WRITE.

---

## 10. UI / beauty bar

One screen. Big paste box + labelled Host/Port/User/Pass/Type inputs kept in **two-way sync** with
the paste box (paste → parse → fill fields; edit field → recompose canonical line). Apply button with
validate-before-up. Live status card: CONNECTED state, redacted proxy, uptime (from
`connectedSinceMs`), bytes up/down, active connections. Scrollable live log from `LogBus`.

Material 3 + Compose, **dark-first**, glassmorphism frosted translucent surfaces, tasteful gradient
accents (**not** stock Compose purple), real motion: animated stage transitions + a connect pulse on
the status card. Proper type scale. Premium product, zero template slop.
