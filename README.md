<img src="./assets/djproxy_banner.svg" alt="DJProxy" width="100%">

[![Build](https://img.shields.io/github/actions/workflow/status/darshjme/DJProxy/build.yml?style=flat-square&label=build)](https://github.com/darshjme/DJProxy/actions)
[![License: MIT](https://img.shields.io/badge/license-MIT-58a6ff?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-3fb950?style=flat-square)](#build-from-source)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0-a371f7?style=flat-square)](app/build.gradle.kts)

DJProxy is a free, open-source Android app that puts one SOCKS5 or HTTP-CONNECT proxy in front of
every app on the device. Paste in credentials, hit Apply, and either the whole phone routes through
that proxy or nothing does — there's no in-between state where some traffic quietly escapes.

Most "proxy apps" only redirect the browser, or leak DNS and WebRTC around the tunnel they just set
up. DJProxy is built the other way: closed by construction — routes, DNS, and IPv6/UDP handling all
force everything through the tunnel — then a real on-device TCP probe proves the path works before
the UI ever says "connected." Residual-leak checks still run continuously after that, but as health
indicators, never as a gate that can throw a scary error over a proxy that is actually working fine.

## What it actually stops

| Leak vector | What normally leaks | How DJProxy closes it |
|---|---|---|
| **Traffic outside the tunnel** | Per-app VPN configs let some apps opt out, or `allowBypass()` lets the OS route around the tunnel on demand. | `VpnService.Builder.addRoute("0.0.0.0", 0)`, no `allowBypass()`, no per-app allow/deny list. Every installed app shares one tun with no exceptions. |
| **IPv6** | Apps and system services dial IPv6 directly, bypassing an IPv4-only tunnel entirely. | `addRoute("::", 0)` pulls all IPv6 into the tun. The transport is IPv4-only, so v6 packets have nowhere to go and are dropped in-tun — nothing v6 ever reaches the network. This is enforced unconditionally; it is not part of the advisory model below. |
| **WebRTC / STUN / QUIC** | Browsers open UDP directly to STUN/TURN servers to discover your real IP (`srflx` candidates), and QUIC bypasses TCP-based tunnels outright. | All UDP is dropped by default, except the one sentinel:53 path used for tunnelled DNS. STUN gathering fails, QUIC has no path, and browsers fall back to TCP through the proxy. Also unconditional, not advisory. |
| **DNS** | The device keeps using its own DNS servers, and on residential proxies DNS-over-TCP:53 (the obvious fix) is itself routinely blocked by the exit, so a naive fix just fails differently. | Every query lands on an in-tun DNS sentinel and is resolved **through the proxy exit** by a pluggable resolver chain — DoH:443 first, then DoT:853, then DNS-over-TCP:53 — so it survives exits that block :53. See [DNS resolution](#dns-resolution-doh-first-so-it-survives-residential-exits) below. |
| **Proxy going down** | Most "route through a proxy" setups fail open: if the proxy dies, traffic goes direct so the user doesn't notice their connection dropped. | If the proxy is unreachable, or the native packet engine crashes, packets are dropped. Never forwarded direct. The routes stay up and hold the tunnel closed while a background watchdog tries to recover. |
| **A dead engine silently un-blackholing traffic** | A native crash in the packet-forwarding code path can take the whole VPN down with it, and some VPN clients then let the OS route direct. | The packet transport runs in its own isolated process (`:engine`). If it crashes, only that process dies — the main process still owns the tun and its `0.0.0.0/0` / `::/0` routes, so nothing forwards until it's replumbed. |
| **A proxy that doesn't actually work** | Apps that "activate" a proxy on faith, then discover mid-browsing that it never worked. | Apply runs a real TCP connect, a real SOCKS5/HTTP-CONNECT handshake, and a real HTTP probe request through the proxy before the tunnel is allowed up. The tunnel is then declared **Connected** only after a second real probe — an unprotected TCP round-trip to an IP literal, routed through the tun itself — proves the whole in-device data path works end to end. |
| **A residual leak going unnoticed** | Apps that ran a leak self-test once at connect time and never looked again, so a leak that develops after (a network change, a resolver going bad) is invisible. | A post-connect health monitor re-checks IPv6/UDP/DNS every 60 seconds and on network change, for the life of the connection — surfaced as chips, never a teardown. |

A failed advisory check is shown as an amber chip under the status card, not an error screen — see
[Advisory health, not a hard gate](#advisory-health-not-a-hard-gate) for why that's the honest design and
what changed from earlier versions.

## Quick start

1. Get a SOCKS5 or HTTP-CONNECT proxy (with or without a username/password).
2. Install the APK and open DJProxy.
3. Paste the proxy in whatever format you have it — `socks5://user:pass@host:port`,
   `host:port:user:pass`, `user:pass@host:port`, plain `host:port`, or space-separated
   `host port user pass` all parse. Or just fill in the Host/Port/User/Pass fields directly; paste
   box and fields stay in sync either way.
4. Tap **Apply**. DJProxy tests the proxy for real before doing anything else. If it fails, you get
   a specific reason (wrong port, bad credentials, timeout, not actually a SOCKS5 server, etc.) and
   a one-line fix — never a bare "error."
5. Once it's up, the status card shows uptime, bytes up/down, active connections, and a row of
   advisory health chips. A live log scrolls underneath if you want the detail. The gear icon opens
   Settings, where the DNS strategy, location spoofing, and Wi-Fi/hotspot sharing panels live.

## Architecture

DJProxy splits into two OS processes on purpose: the process holding the tun and its routes never
runs the native packet transport, so a crash in that transport can't take the routes down with it.
A `FeatureRegistry` lets location, hotspot, and diagnostics plug in without ever editing the core
tunnel code — each is its own package, wired at process start, and every call from core into a
feature lane is wrapped so a bug in one can never break bring-up.

```mermaid
flowchart LR
    subgraph device["Every app on the device"]
        A["Any installed app"]
    end

    subgraph main["Main process — ai.darshj.djproxy"]
        VPN["DjVpnService\naddRoute 0.0.0.0/0 + ::/0\nin-tun DNS sentinel\nno allowBypass, no per-app list"]
        TUN[("tun interface")]
        Router["TunRouter\nleak policy: drop IPv6, drop UDP\nexcept sentinel DNS"]
        Probe["ConnectivityProbe\nIP-literal TCP round-trip = CONNECTED"]
        Health["HealthMonitor\nadvisory: IPv6 / UDP / DNS every 60s"]
        Socks["LocalSocksServer\n127.0.0.1 loopback SOCKS5 front"]
        Watchdog["EngineWatchdog\nholds routes, replumbs on fault"]
        Registry["FeatureRegistry\nlocation · hotspot · diagnostics · DNS strategy"]
    end

    subgraph engine[":engine process (isolated)"]
        Hev["hev-socks5-tunnel\nnative C / lwIP"]
    end

    Upstream["ProxyDialer\nthe one protect() seam"]
    Proxy["Your SOCKS5 / HTTP-CONNECT proxy"]

    A -- "IP packets" --> TUN
    TUN --> Router
    Router -- "socketpair, dup'd fd" --> Hev
    Hev -- "TCP flows as SOCKS5" --> Socks
    Socks --> Upstream
    Upstream -- "protected socket" --> Proxy
    Hev -. "crash / EOF" .-> Watchdog
    Watchdog -. "replumb, routes held" .-> Router
    Router -.-> Probe
    Probe -.->|"CONNECTED"| Health
    Health -.->|"chips, never gate"| Registry
```

The native transport is [hev-socks5-tunnel](app/src/main/cpp/hev-socks5-tunnel) (MIT, C, built on
lwIP), vendored as a submodule. It terminates every TCP flow from the tun and re-offers it to
`LocalSocksServer` as a plain SOCKS5 connection on loopback — that server then dials the *real*
proxy through the single `SocketProtector` seam and forwards bytes. That seam is the only place in
the codebase that calls `VpnService.protect()`; every off-device socket goes through it, which is
what stops the tunnel from ever looping back into itself.

### Where packets get dropped

```mermaid
flowchart TD
    Start(["Packet enters the tun"]) --> Ver{"IP version?"}
    Ver -- "IPv6" --> DropV6["Dropped\n(blackholed in-tun — v6 never reaches\nthe network, engine is IPv4-only)"]
    Ver -- "IPv4" --> Frag{"Fragmented?"}
    Frag -- "yes" --> DropFrag["Dropped\n(no reassembly — refuses a\nknown evasion path)"]
    Frag -- "no" --> Proto{"Protocol?"}
    Proto -- "TCP" --> ToEngine["Forwarded to the :engine\nprocess (hev/lwIP terminates it,\nre-offers as SOCKS5 on loopback)"]
    Proto -- "other (ICMP, ...)" --> DropOther["Dropped"]
    Proto -- "UDP" --> UdpDst{"Destination is the\nin-tun DNS sentinel:53?"}
    UdpDst -- "no" --> DropUdp["Dropped\n(kills STUN/TURN srflx gathering\nand QUIC — no exceptions)"]
    UdpDst -- "yes" --> Dns["Query handed to the DNS resolver chain\n(DoH:443 -> DoT:853 -> TCP:53),\nall through the proxy exit"]
    Dns --> Reply["Synthesized UDP reply written\nback into the tun"]
    ToEngine --> ProxyOut["Reaches the proxy only after\na real, protected socket connect"]
```

### Bring-up: how CONNECTED is decided

```mermaid
flowchart TD
    Apply["Apply proxy"] --> Preflight["PreflightValidator:\nreal TCP + handshake + probe"]
    Preflight -- "fail" --> Reject["Specific error + fix hint\n(tunnel never starts)"]
    Preflight -- "ok" --> Tun["Establish tun\nroutes 0/0 + ::/0 + DNS sentinel"]
    Tun --> Engine["Plumb :engine, start router"]
    Engine --> ProbeGate{"ConnectivityProbe:\nTCP round-trip to an IP literal,\nTHROUGH the tun"}
    ProbeGate -- "fail" --> Closed["Teardown, fail closed\n(this is a data-path failure,\nnot a leak failure)"]
    ProbeGate -- "ok" --> Connected["Publish CONNECTED"]
    Connected --> HealthLoop["HealthMonitor starts:\nIPv6 / UDP / DNS checks,\nevery 60s + on network change"]
    HealthLoop -- "any check degrades" --> Chip["Amber chip only.\nDNS also auto-advances\nto the next resolver."]
    Chip -.-> Connected
```

The probe target is deliberately an **IP literal** (`1.1.1.1:80`), not a hostname — the CONNECTED
decision must not itself depend on in-tun DNS working, since DNS is exactly the piece most likely to
be degraded on a residential exit. A DNS outage shows up as a post-connect advisory chip, never as a
reason the tunnel refuses to come up.

## Features

<details>
<summary><strong>Paste box that understands seven formats</strong></summary>

`ProxyParser` recognizes `scheme://user:pass@host:port`, `scheme://host:port`,
`user:pass@host:port`, `host:port:user:pass`, `user:pass:host:port`, plain `host:port`, and
whitespace-separated `host port user pass`. The scheme (`socks5://` or `http://`) sets the proxy
type when present; otherwise the currently selected type is kept. Every failure comes back as a
specific message plus a fix hint — never a silent no-op.

The paste box and the labelled Host/Port/User/Password/Type fields are two-way synced: paste and
the fields fill in, edit a field and the canonical line updates.
</details>

<details>
<summary><strong>Validate-before-up, not activate-and-hope</strong></summary>

Tapping Apply runs `PreflightValidator`: a real TCP connect to the proxy, the real SOCKS5 or HTTP
CONNECT handshake (including auth if you supplied it), and a real `GET /generate_204` request
tunnelled through the proxy end to end. This reuses the exact same dialer the live tunnel uses, so
a green pre-flight proves the live path, not an approximation of it.

Every way that can fail maps to one specific, human error with a one-line hint:

- proxy host doesn't resolve
- connection actively refused
- timed out during connect / handshake / probe
- credentials rejected
- something answered but it isn't a SOCKS5 server
- the HTTP CONNECT proxy returned a 4xx/5xx status
- the proxy refused to open a tunnel to the destination
- the handshake was structurally malformed
- the handshake succeeded but the probe request never came back

The VPN only comes up after a genuine success, and then `ConnectivityProbe` proves the whole in-tun
data path once more before the UI reports Connected (see [Bring-up](#bring-up-how-connected-is-decided)).
</details>

<details>
<summary><strong>Advisory health, not a hard gate</strong></summary>

An earlier version of DJProxy treated its own leak self-test as a gatekeeper: if the tunnel could
reach an IPv6 literal, or a UDP packet escaped, or DNS-over-TCP failed, the whole tunnel refused to
report Connected — even when the proxy was carrying TCP traffic perfectly well. On residential
exits that block DNS-over-TCP outright, that made the self-test the single biggest cause of "DJProxy
doesn't work," even though the tunnel was fine.

`HealthMonitor` now runs the same three checks (IPv6 reachable, UDP escapes, DNS resolves) as
**indicators**, on a 60-second interval and on every network change, after CONNECTED is already
published. A degraded result never tears the tunnel down, never throws, and never changes the
connection stage — it publishes an amber chip. A degraded DNS result additionally advances the
resolver chain to the next transport automatically (see below), which is the one case where a
health check has a side effect beyond reporting.
</details>

<details>
<summary><strong>DNS resolution: DoH first, so it survives residential exits</strong></summary>

Residential SOCKS5/HTTP exits from providers like nsocks, iproyal, and luxsocks routinely block
outbound TCP to port 53 — which is exactly the port the original DNS-over-TCP design depended on.
Port 443 is the one port a proxy exists to carry, so DJProxy's primary DNS transport is now
**DNS-over-HTTPS on :443**, tunnelled through the proxy CONNECT the same way any other TCP flow is:

1. **DoH:443** (`DohResolver`) — POSTs the raw DNS query as `application/dns-message` to
   `cloudflare-dns.com` / `dns.google`, reached by IP literal so no bootstrap DNS is needed, wrapped
   in a certificate- and hostname-verified TLS session.
2. **DoT:853** (`DotResolver`) — the same endpoints over DNS-over-TLS, RFC 7858 length-prefixed
   messages, for the exits that allow :853 but still block :443's HTTP path or :53.
3. **DNS-over-TCP:53** (`DnsOverTcpResolver`) — the original v2 behaviour, kept because some proxies
   genuinely do allow :53 and it's the cheapest path when they do.

`CompositeDnsResolver` tries the chain in that order and remembers whichever transport first
succeeds ("sticky head"), so a proxy that blocks :443 doesn't re-pay that failure on every lookup —
it settles on DoT or TCP:53 and stays there until a health check finds it failing again. Every
transport resolves **at the proxy exit**, so DNS answers reflect the exit's geography, not the
phone's — no DNS geo-leak, which matters for the unblocking use case this app exists for. Answers
are cached 60 seconds and identical concurrent queries are coalesced into one upstream round trip.

The active transport is shown as one of the advisory chips ("DNS via DoH:443", "DNS via TCP:53",
...) so you can see which strategy your specific proxy landed on.
</details>

<details>
<summary><strong>Location spoofing, tiered and honest</strong></summary>

Apps that read GPS (dating, streaming, delivery) can disagree with a proxy's exit IP — the IP says
one city, the GPS says another. DJProxy can resolve the proxy's exit IP to a city-level lat/long
(looked up *through* the proxy, so the lookup itself sees the exit) and push that fix into Android's
mock-location machinery. But Android only allows an app to fake location under a real grant, and
DJProxy will not pretend otherwise:

| Tier | What's required | What you get |
|---|---|---|
| **Unavailable** | Nothing granted, no root | The exit city is looked up and *shown*, but no fix is published. The settings panel says so plainly and links straight to Developer Options. |
| **Mock-location grant** | Settings → Developer options → Select mock location app → DJProxy | `LocationManager` GPS/network test providers publish the exit fix; if Google Play services is present, `FusedLocationProviderClient` mock mode is set too, so apps using the common Fused API see it as well. |
| **Emulator** | Same grant, running on an AVD/Genymotion/LDPlayer-class image | Same mechanism (the emulator's own console `geo fix` channel can't be driven from inside an app process, so the in-app test-provider path is what's actually used — this is called out in the UI rather than promising a console-level spoof). |
| **Root** | `su` present | DJProxy self-grants the mock-location app-op via `appops set … allow` so you don't have to touch Developer Options at all. |

A manual latitude/longitude override is always available and takes priority over the exit lookup.
The settings panel's headline text is generated directly from the detected tier — it can say
"Unavailable," "Ready," or "Ready (root)," but it can never claim GPS is spoofed while the grant is
actually absent.
</details>

<details>
<summary><strong>Wi-Fi hotspot / router sharing, honestly scoped</strong></summary>

On stock, unrooted Android, a device tethered to your phone's hotspot does **not** get proxied by
turning on DJProxy — tethered client traffic egresses on the upstream network interface directly,
bypassing `VpnService` entirely (the tun only ever sees this device's own apps). No app in userspace
can change that. DJProxy does not pretend it can; it ships the three things that are actually true:

- **LAN proxy endpoint (default, works unrooted).** `LanShareServer` binds a combined SOCKS5 +
  HTTP-CONNECT listener to your phone's actual hotspot/Wi-Fi address (never `0.0.0.0`) and forwards
  every accepted connection through DJProxy's own upstream dialer — so a laptop or second phone that
  points its proxy settings at `<phone-IP>:8787` genuinely rides your paid proxy's exit. Access is
  always password-protected (a random credential is generated per share; there is no "no auth"
  option), and the endpoint refuses to dial private/loopback/link-local/CGNAT addresses so a LAN
  client can't use it to pivot into the phone's own network. Stopping the share tears down every
  in-flight connection, not just the listener.
- **Root transparent redirect (behind a real root check).** If `su` is present, DJProxy can install
  `iptables`/`ip rule` policy routing (in its own dedicated chains, so reverting never touches rules
  DJProxy didn't create) that pulls tethered soft-AP/USB/Bluetooth traffic into the tun without the
  client configuring anything. This is real transparent proxying, but it requires root and is
  self-tearing-down if the tunnel drops.
- **Honest reporting, always.** The settings panel shows exactly which tier is available — LAN proxy
  only, root transparent available, or no LAN interface at all — and a QR / copyable address for
  the LAN endpoint so a second device can configure itself in one scan.
</details>

<details>
<summary><strong>Crash-proof by construction</strong></summary>

A "the app just closed" report is treated as a defect in the app's fault handling, not just the
triggering bug. Every layer degrades instead of taking the process down with it:

- A process-wide `Thread.setDefaultUncaughtExceptionHandler` (`CrashCatcher`) captures the stack and
  minimal device info to a local file for the diagnostic report, reports it to the diagnostics lane,
  then still delegates to the platform's own handler — nothing is silently swallowed.
- Every coroutine scope in the core tunnel path carries its own exception handler; a `SupervisorJob`
  means one child failing never cancels its siblings.
- `DjVpnService`'s bring-up steps are wrapped so a bad step tears the tunnel down cleanly
  (fail-closed) instead of throwing out of the service lifecycle.
- The native engine's own process boundary already isolates a native crash from the main process;
  the watchdog reconnects with backoff, or gives up and reports, but never lets a native fault reach
  the tun or the UI.
- Feature-lane calls (location, hotspot, diagnostics) from core are always `runCatching`-wrapped, so
  a bug in one of those lanes can never break bring-up or crash the app.
</details>

<details>
<summary><strong>Runs on Android 5.0 and up, honestly on emulators too</strong></summary>

`minSdk` is 21 (Android 5.0 Lollipop); every API-gated call in the codebase — foreground service
types, notification channels, runtime permission requests, Keystore AES-GCM, TLS SNI APIs — is
branched down to that floor rather than assumed. Two examples that matter in practice:

- **Credential storage below API 23.** AndroidKeyStore's AES-GCM path doesn't exist before
  Marshmallow. On API 21–22, DJProxy simply doesn't persist the proxy password (everything else is
  kept) rather than ever falling back to storing it in the clear — you re-enter it after a restart.
- **TLS on API 21–23.** The SNI/endpoint-identification APIs the DoH/DoT resolvers use for
  certificate verification were added in API 24. Below that, DJProxy still completes a full,
  certificate-verified TLS handshake — it just verifies the hostname manually with the platform's
  own verifier afterward instead of skipping verification. No API level ever gets a weaker TLS check.

One universal APK ships all four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), so the same
build installs on a physical phone and on x86/x86_64 emulators (LDPlayer, BlueStacks, Android Studio
AVD, Genymotion). On emulators specifically: several of them (LDPlayer and BlueStacks especially)
route guest networking around a VPN they're supposedly inside. DJProxy detects the emulator
heuristically and, if it also suspects traffic is bypassing the tun, surfaces a plain "emulator
networking may bypass the VPN" advisory chip — it never claims full protection it can't verify.
</details>

<details>
<summary><strong>Send a diagnostic report</strong></summary>

Settings has a "Send diagnostic reports" toggle and a "Send diagnostic report" button. On a critical
failure (an uncaught crash, an engine death that couldn't recover, a bring-up failure) a report is
offered; you can also send one manually at any time. The report is assembled locally — device model,
Android/API level, ABI list, the last 200 log lines, the last advisory health snapshot, and the last
captured crash trace if any — and handed to the device's own mail app via `ACTION_SENDTO`. There is
no backend and no automatic network send: the report only leaves the device if you tap Send in your
mail client.

The proxy password is never in the log buffer or the app state that feeds the report to begin with,
and the assembled body is still scrubbed for anything that looks like a credential (URL user-info,
`password=`/`token:` style key-value pairs) as defence in depth. This redaction is unit-tested.
</details>

<details>
<summary><strong>Live status, not a spinner</strong></summary>

The status card shows connection stage, the redacted proxy endpoint (`•••@host:port` — username and
password are both masked), a ticking uptime counter, bytes up/down, active and total connection
counts, and the advisory health chip row. A scrollable log underneath streams every significant
event as it happens. The whole screen is Material 3 + Compose, dark-first, with translucent glass
surfaces and a cyan/indigo accent instead of stock Material purple.
</details>

## What a proxy VPN on unrooted Android can and cannot do

Being straight about the ceiling here is the whole point of this project, so this is the plain
version, gathered in one place:

- **It can put every app's own traffic through your proxy, with no opt-out.** That's the core
  guarantee, and it holds regardless of Android version or root.
- **It can prove the tunnel actually works, not just that it "activated."** Pre-flight plus the
  post-establish TCP probe are both real network round trips, not a config check.
- **It can make DNS resolve at the proxy's exit even when the exit blocks the obvious DNS ports**,
  by falling back through DoH → DoT → TCP:53.
- **It can spoof GPS location to match the exit — but only with a real grant.** Unrooted Android
  requires you to explicitly select DJProxy as the Developer Options mock-location app; without that
  grant (or root, which can self-grant it), DJProxy shows you the exit city but does not touch GPS.
  There is no way around this that doesn't involve lying to the user, so DJProxy doesn't.
- **It can share its proxy connection with other devices on your LAN or hotspot — if they configure
  a proxy address.** It cannot make hotspot tethering transparently ride the VPN on stock Android;
  that would require intercepting traffic Android routes around the VpnService entirely, which is an
  OS-level restriction, not a DJProxy limitation. Root lifts this restriction (real `iptables`
  policy routing); unrooted does not, and DJProxy says so instead of shipping something that looks
  like it works and doesn't.
- **It cannot protect you from the proxy operator itself.** DJProxy stops your own device from
  leaking your IP or DNS around the tunnel. It has no way to stop a malicious or logging proxy
  provider from seeing your traffic's contents — that trust decision is yours when you pick a
  provider.
- **It cannot survive a device reboot or an unrecoverable crash without "Always-on VPN" +
  "Block connections without VPN"** enabled in Android's system VPN settings for DJProxy. Without
  that, a reboot leaves the device routing directly until you reopen the app.
- **It cannot defeat a rooted device or an app with `CAP_NET_ADMIN`** — those can route around any
  Android VPN, DJProxy included. This is an OS-level ceiling that applies to every `VpnService`-based
  app, not something specific to this one.

## Honest limitations

DJProxy is a `VpnService`, not a rooted firewall or a kernel-level packet filter. The section above
covers the location/hotspot ceilings specifically; the rest of the model:

- **A rooted device, or an app with `CAP_NET_ADMIN`, can route around any Android VPN.** This is an
  OS-level ceiling, not something any app in userspace can close. DJProxy assumes an unrooted device
  by default and only uses root, where present, to lift its own hotspot/location tiers — never to
  claim a guarantee it can't back up on a device it doesn't control.
- **Without "Always-on VPN" + "Block connections without VPN" enabled in Android's system VPN
  settings, a device reboot or a VPN crash the watchdog can't recover from will leave the device
  routing directly** until DJProxy is reopened. Turn both on (Settings → Network & internet → VPN →
  DJProxy → the gear icon) if you want the fail-closed guarantee to survive a reboot without you
  having to notice.
- **DJProxy trusts the proxy you give it with your traffic's contents.** It protects you from your
  own device leaking your IP or DNS around the tunnel; it does not protect you from a malicious or
  logging proxy operator. Choose your proxy accordingly.
- **UDP relaying is intentionally not shipped.** The SOCKS5 UDP ASSOCIATE primitives exist in the
  codebase and are unit-tested, but there is no live relay wired up: verifying a UDP relay is
  actually leak-free requires a real device, and shipping an unverified relay would violate the
  whole premise of this app. UDP is unconditionally dropped instead, which is the safe direction —
  it's what kills the WebRTC/QUIC leak in the first place.
- **Traffic-analysis correlation (timing, packet size) is out of scope**, as it is for essentially
  every VPN. DJProxy's threat model is a passive network observer or the destination server trying
  to learn your real IP or the hostnames you're resolving — not a global adversary correlating flows.

See [DESIGN.md](DESIGN.md) for the original core leak-proofing/threat model, and
[DESIGN_V3.md](DESIGN_V3.md) for the full v3 architecture (advisory health, the DNS resolver chain,
location/hotspot lanes, crash-proofing, and the compatibility matrix) these mechanisms build on.

## Build from source

Requirements: Android SDK (`platform-35`, `build-tools;35.0.0`), NDK `27.2.12479018`, JDK 21. The
native transport is a git submodule — clone with `--recurse-submodules` or run
`git submodule update --init` afterward.

```sh
git clone --recurse-submodules https://github.com/darshjme/DJProxy.git
cd djproxy

# Debug build
gradle assembleDebug

# Unit tests
gradle testDebugUnitTest
```

If you don't have a Gradle wrapper available, call your local `gradle` binary the same way — there's
nothing wrapper-specific in these commands.

### Release signing

Release builds are signed entirely from environment variables. There are no fallback passwords in
source: if `DJPROXY_KEYSTORE` is unset, the release build produces an unsigned APK; if it's set but
any password variable is missing, the build fails immediately instead of guessing.

```sh
export DJPROXY_KEYSTORE=/path/to/your-release.jks
export DJPROXY_STORE_PASSWORD=...
export DJPROXY_KEY_ALIAS=...
export DJPROXY_KEY_PASSWORD=...
gradle assembleRelease
```

Generate your own keystore for any build you intend to distribute. Don't reuse a development
keystore as a trusted release identity. If you fork this project for your own build, also change
(or blank) `DIAG_RECIPIENT` in `app/build.gradle.kts` — the official build ships the maintainer's own
address for diagnostic e-mails, and a rebranded fork should not silently mail reports to it.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). The short version: read `DESIGN_V3.md` first (it's the
current source of truth; `DESIGN.md` is the original core model it extends), never weaken a leak
guarantee or turn an advisory check back into a hard gate to make a feature easier, and don't add a
second `VpnService.protect()` call site anywhere in the codebase.

## License

MIT — see [LICENSE](LICENSE).

---

<sub>[Darshankumar Joshi](https://github.com/darshjme)</sub>
