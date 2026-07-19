<img src="./assets/djproxy_banner.svg" alt="DJProxy" width="100%">

[![Build](https://img.shields.io/github/actions/workflow/status/darshjme/DJProxy/build.yml?style=flat-square&label=build)](https://github.com/darshjme/DJProxy/actions)
[![License: MIT](https://img.shields.io/badge/license-MIT-58a6ff?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2024%2B-3fb950?style=flat-square)](#build-from-source)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0-a371f7?style=flat-square)](app/build.gradle.kts)

DJProxy is a free, open-source Android app that puts one SOCKS5 or HTTP-CONNECT proxy in front of
every app on the device. Paste in credentials, hit Apply, and either the whole phone routes through
that proxy or nothing does — there's no in-between state where some traffic quietly escapes.

Most "proxy apps" only redirect the browser, or leak DNS and WebRTC around the tunnel they just set
up. DJProxy is built the other way: closed by construction, then a real on-device test proves it
before the UI ever says "connected."

## What it actually stops

| Leak vector | What normally leaks | How DJProxy closes it |
|---|---|---|
| **Traffic outside the tunnel** | Per-app VPN configs let some apps opt out, or `allowBypass()` lets the OS route around the tunnel on demand. | `VpnService.Builder.addRoute("0.0.0.0", 0)`, no `allowBypass()`, no per-app allow/deny list. Every installed app shares one tun with no exceptions. |
| **IPv6** | Apps and system services dial IPv6 directly, bypassing an IPv4-only tunnel entirely. | `addRoute("::", 0)` pulls all IPv6 into the tun. The transport is IPv4-only, so v6 packets have nowhere to go and are dropped in-tun — nothing v6 ever reaches the network. |
| **DNS** | The device keeps using its own DNS servers (often the carrier's or the Wi-Fi router's) even with a VPN up, revealing every hostname you look up. | `addDnsServer()` points the OS at an in-tun sentinel address. Every query lands inside the tunnel and is re-sent as DNS-over-TCP through the configured upstream resolver, over the proxy. The device's own resolver is never consulted. |
| **WebRTC / STUN / QUIC** | Browsers open UDP directly to STUN/TURN servers to discover your real IP (`srflx` candidates), and QUIC bypasses TCP-based tunnels outright. | All UDP is dropped by default, except the one sentinel:53 path used for tunnelled DNS. STUN gathering fails, QUIC has no path, and browsers fall back to TCP through the proxy. |
| **Proxy going down** | Most "route through a proxy" setups fail open: if the proxy dies, traffic goes direct so the user doesn't notice their connection dropped. | If the proxy is unreachable, or the native packet engine crashes, packets are dropped. Never forwarded direct. The routes stay up and hold the tunnel closed while a background watchdog tries to recover. |
| **A dead engine silently un-blackholing traffic** | A native crash in the packet-forwarding code path can take the whole VPN down with it, and some VPN clients then let the OS route direct. | The packet transport runs in its own isolated process (`:engine`). If it crashes, only that process dies — the main process still owns the tun and its `0.0.0.0/0` / `::/0` routes, so nothing forwards until it's replumbed. |
| **A proxy that doesn't actually work** | Apps that "activate" a proxy on faith, then discover mid-browsing that it never worked. | Apply runs a real TCP connect, a real SOCKS5/HTTP-CONNECT handshake, and a real HTTP probe request through the proxy before the tunnel is allowed up — and again as an on-device self-test (IPv6 unreachable, UDP blocked, DNS tunnelled) before the UI reports Connected. |

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
5. Once it's up, the status card shows uptime, bytes up/down, active connections, and which leak
   checks passed. A live log scrolls underneath if you want the detail.

## Architecture

DJProxy splits into two OS processes on purpose: the process holding the tun and its routes never
runs the native packet transport, so a crash in that transport can't take the routes down with it.

```mermaid
flowchart LR
    subgraph device["Every app on the device"]
        A["Any installed app"]
    end

    subgraph main["Main process — ai.darshj.djproxy"]
        VPN["DjVpnService\naddRoute 0.0.0.0/0 + ::/0\nin-tun DNS sentinel\nno allowBypass, no per-app list"]
        TUN[("tun interface")]
        Router["TunRouter\nleak policy: drop IPv6, drop UDP\nexcept sentinel DNS"]
        Socks["LocalSocksServer\n127.0.0.1 loopback SOCKS5 front"]
        Watchdog["EngineWatchdog\nholds routes, replumbs on fault"]
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
    UdpDst -- "yes" --> Dns["Query tunnelled as DNS-over-TCP\nto the configured upstream,\nthrough the proxy"]
    Dns --> Reply["Synthesized UDP reply written\nback into the tun"]
    ToEngine --> ProxyOut["Reaches the proxy only after\na real, protected socket connect"]
```

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

The VPN only comes up after a genuine success — and even then, it doesn't report Connected until an
on-device leak self-test (IPv6 unreachable, UDP blocked, DNS tunnelled) passes too.
</details>

<details>
<summary><strong>Fail-closed engine isolation</strong></summary>

The native hev/lwIP packet loop runs in its own `:engine` process, bound over Binder from the main
process that owns the tun. If that process crashes, the main process's copy of the bridging file
descriptor closes, the router on the main-process side reads EOF, and the fault is reported to a
watchdog — while the tun and its `0.0.0.0/0` + `::/0` routes stay exactly where they were. Nothing
routes until the watchdog rebuilds the bridge and restarts the engine, with capped exponential
backoff; if every attempt is exhausted, the tunnel fails all the way closed rather than opening up.
</details>

<details>
<summary><strong>DNS-over-TCP, with a real cache</strong></summary>

Every DNS query is answered by tunnelling it over TCP (RFC 7766 framing) to the DNS server you
configured, through the proxy — never by asking the device's own resolver. Answers are cached for
60 seconds and identical concurrent queries are coalesced into a single upstream round trip, capped
at 16 concurrent upstream connections, so a page issuing a burst of lookups doesn't open a burst of
sockets. Oversized UDP replies come back with the DNS truncation bit set, so resolvers cleanly retry
over TCP — which the loopback SOCKS front terminates locally and answers from the same tunnelled
resolver.
</details>

<details>
<summary><strong>Credentials encrypted at rest</strong></summary>

The proxy password is the one secret DJProxy persists (so an always-on VPN can re-establish the
tunnel after the device or the app process restarts). It's encrypted with an AES-256-GCM key held in
the Android Keystore before it ever touches `SharedPreferences` — the key material itself never
leaves the TEE/StrongBox and can't be extracted. If encryption or decryption fails for any reason,
the password is dropped rather than silently stored or recovered in plaintext.
</details>

<details>
<summary><strong>Live status, not a spinner</strong></summary>

The status card shows connection stage, the redacted proxy endpoint (`•••@host:port` — username and
password are both masked), a ticking uptime counter, bytes up/down, active and total connection
counts, and a chip for each leak check that ran. A scrollable log underneath streams every
significant event as it happens. The whole screen is Material 3 + Compose, dark-first, with
translucent glass surfaces and a cyan/indigo accent instead of stock Material purple.
</details>

## Honest limitations

DJProxy is a `VpnService`, not a rooted firewall. That has real limits, and pretending otherwise
would be exactly the kind of overclaiming this project exists to avoid:

- **A rooted device, or an app with `CAP_NET_ADMIN`, can route around any Android VPN.** This is an
  OS-level ceiling, not something any app in userspace can close. DJProxy assumes an unrooted device.
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

See [DESIGN.md](DESIGN.md) for the full leak-proofing model, threat model, and release gates this
project holds itself to.

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
keystore as a trusted release identity.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). The short version: read `DESIGN.md` first, never weaken a
leak guarantee to make a feature easier, and don't add a second `VpnService.protect()` call site
anywhere in the codebase.

## License

MIT — see [LICENSE](LICENSE).

---

<sub>[Darshankumar Joshi](https://github.com/darshjme)</sub>
