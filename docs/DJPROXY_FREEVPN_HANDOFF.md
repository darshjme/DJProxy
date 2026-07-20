# DJProxy — Free-VPN Backend Integration Handoff

**For:** the DJProxy implementation team · **From:** RE/extraction pass, 2026-07-21
**Repo:** DJProxy (Android, Kotlin, `hev-socks5-tunnel`, `darshjme/DJProxy`)
**Source data & proofs:** `D:\AI\android-tools\freevpn-re\`

---

## 1. What you're building

Add a **"Free VPN"** mode to DJProxy that connects through free-tier servers from real providers.
Three integration-ready sources are already extracted and proven below. Ship in this order:

1. **Cloudflare WARP** — free, unlimited, **no account**. (Phase 1 — do this first.)
2. **Windscribe free** — 85 servers, 10 countries. (Phase 2 — adds country choice.)
3. **Proton free** — 5 countries. (Phase 3 — optional, more choice.)

## 2. The ONE architectural decision you must make first

DJProxy today consumes **SOCKS5/HTTP** upstreams (`hev-socks5-tunnel`). **None of these providers
expose SOCKS5** — they all speak **WireGuard**. So you need to add **one new engine**:

> **Add `wireguard-go` (golang.zx2c4.com/wireguard, License: MIT) as a second tunnel mode.**

- **Why wireguard-go and not OpenVPN:** the embeddable Android OpenVPN client (`ics-openvpn`) is
  **GPLv2 — it would force DJProxy's whole APK to GPL.** `wireguard-go` is **MIT**, so DJProxy stays
  permissively licensed. This is non-negotiable if DJProxy is to remain non-GPL.
- **How it fits:** WireGuard owns its own TUN interface via `VpnService.Builder`. Android allows only
  **one active `VpnService`**, so "Free VPN (WireGuard)" is a **separate mode** from the existing
  "SOCKS5/HTTP proxy" mode — not chained through `hev-socks5-tunnel`. UI: a mode toggle.
- **Recommended integration:** use the **`wireguard-android`** library (`com.wireguard.android:tunnel`,
  Apache-2.0) which wraps `wireguard-go`'s Go backend + a Kotlin `Backend`/`Tunnel`/`Config` API.
  You feed it a standard `.conf` string. That's the whole interface.

**Alternative (no new engine):** only viable for Shadowsocks-based providers (Turbo VPN — RE pending).
If confirmed SS, a bundled local `shadowsocks-libev` that exposes `127.0.0.1:1080` SOCKS5 feeds
DJProxy's *existing* engine unchanged. Keep this option open pending the Turbo VPN result.

---

## 3. Phase 1 — Cloudflare WARP (free, unlimited, no account) ⭐

**Protocol:** WireGuard. **Cost:** free/unlimited. **Account:** none — register silently on-device.

### 3a. Register a free WARP identity on-device (per install, no user action)
`POST https://api.cloudflareclient.com/v0a2483/reg`
- Generate a WireGuard keypair on-device (the `wireguard-android` `KeyPair()`).
- Body (JSON): `{ "key": "<client_public_key_base64>", "install_id":"", "fcm_token":"", "tos":"<ISO8601 now>", "type":"Android", "model":"PC", "locale":"en_US" }`
- Headers: `Content-Type: application/json`, `User-Agent: okhttp/3.12.1`, `CF-Client-Version: a-6.11-2223`.
- Response gives `result.config.peers[0].public_key`, `...endpoint.host`, and your assigned
  `result.config.interface.addresses` (v4 `172.16.0.2/32` + a v6). Save `result.id` + `result.token`
  (Bearer) if you later want to query/delete the identity.

Reference implementation to copy the exact flow: **`wgcf`** (`github.com/ViRb3/wgcf`, MIT) —
`cloudflare/api.go`. A **verified live-generated** profile (proof this works) is at
`warp-hideme/wgcf-profile.conf`:

```ini
[Interface]
PrivateKey = <your on-device generated key>
Address    = 172.16.0.2/32, 2606:4700:110:...:b125/128
DNS        = 1.1.1.1, 1.0.0.1
MTU        = 1280
[Peer]
PublicKey  = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=   # Cloudflare's fixed WARP peer pubkey
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint   = engage.cloudflareclient.com:2408
```

### 3b. Endpoints (round-robin these if `:2408` is blocked on a network)
- Hostname: `engage.cloudflareclient.com:2408`
- IPv4 pool: `162.159.192.1`, `162.159.192.2`, `162.159.195.1`, `188.114.96.0`, `188.114.98.224`
- Ports that also carry WARP: `2408`, `500`, `1701`, `4500`
- Peer public key is **constant** for all WARP: `bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=`

### 3c. UX
No login. On first "Free VPN → WARP" tap: register → store keypair in `EncryptedSharedPreferences` →
build `wireguard-android` `Config` from the response → `Backend.setState(tunnel, UP, config)`. Done.

---

## 4. Phase 2 — Windscribe free (85 servers, 10 countries)

**Server list is PUBLIC — no auth needed:**
`GET https://assets.windscribe.com/serverlist/mob-v2/1/1` (JSON; `premium_only:0` = free).
Full extracted free list: `windscribe/windscribe_free_servers.json` (85 groups). Country-spread sample
(`windscribe/windscribe_free_sample.json`):

| CC | City | WireGuard host | IP |
|----|------|----------------|----|
| US | Dallas | dfw-86-wg.whiskergalaxy.com | 198.44.137.27 |
| US | Atlanta | atl-109-wg.whiskergalaxy.com | 198.44.138.19 |
| CA | Toronto | yyz-72-wg.whiskergalaxy.com | 198.44.157.27 |
| FR | Paris | cdg-103-wg.whiskergalaxy.com | 146.70.253.162 |
| DE | Frankfurt | fra-113-wg.whiskergalaxy.com | 135.136.0.2 |
| NL | Amsterdam | ams-120-wg.whiskergalaxy.com | 185.212.171.130 |
| NO | Oslo | osl-169-wg.whiskergalaxy.com | 146.70.170.210 |
| RO | Bucharest | otp-105-wg.whiskergalaxy.com | 135.136.1.2 |
| CH | Zurich | zrh-112-wg.whiskergalaxy.com | 37.120.213.210 |
| GB | London | lhr-171-wg.whiskergalaxy.com | 37.221.113.162 |
| HK | Hong Kong | hkg-26-wg.whiskergalaxy.com | 146.70.9.242 |

**Credentials:** per-account (free). The scriptable free path:
1. One-time: create a free Windscribe account, get a **session_auth_hash** via
   `POST https://api.windscribe.com/Session` (user/pass) — store it server-side or on first run.
2. Register the device WG public key + get a peer config:
   `POST https://api.windscribe.com/WgConfigs/init` then `.../connect` with `{hostname, public_key}`
   → returns the server's `PublicKey`, `Endpoint`, and the `AllowedIPs`/assigned address.
   (Windscribe WG listens on **UDP 443** on the `*-wg` hosts.)
3. Feed that into the same `wireguard-android` `Config`. WG port for Windscribe free = **51820/UDP** or
   **443/UDP** (stealth). Simplest: use their **Config Generator** REST equivalent above.

> Simplest ship: hardcode the 15-server sample as selectable locations; do the WG key registration
> per-server at connect time with the stored session hash.

---

## 5. Phase 3 — Proton free (5 countries: US, NL, JP, RO, PL)

- **No shared creds in the APK** — Proton is fully per-account. App is open-source:
  `github.com/ProtonVPN/android-app` (read the connection flow there).
- Automatable free path: free account → SRP login (`/auth/info` + `/auth`) → `x-pm-appversion:
  android-vpn@5.x`, `x-pm-apiversion:3` headers → `GET /vpn/logicals?Tier=0` for free servers →
  generate client WG keypair → `POST /vpn/v1/certificate` with the pubkey → receive WG peer config.
- OSS to crib: `proton-vpn-api-core` (Python). Same `wireguard-android` `Config` at the end.
- Lower priority — WARP + Windscribe already cover the need with far less auth complexity.

---

## 6. Turbo VPN — RE BLOCKED ON APK DOWNLOAD (pipeline ready, needs the APK file)

The one provider that may embed **shared creds directly in the APK** (Innovative Connecting proprietary;
often Shadowsocks with a hardcoded bootstrap key). **The RE pipeline is fully built and ready**
(`turbovpn/run_turbo.sh` — jadx + apktool + gitleaks + secret-hunt). The ONLY blocker: every automated
APK download route is anti-bot-blocked as of 2026-07-21 — APKPure direct (HTML block page),
`apkeep --download-source apk-pure` (returns empty version list), APKCombo checkin flow (JS-gated).

**To finish it (2 min):** download the Turbo VPN APK/XAPK once via a real browser (one click from
apkpure.com / apkmirror.com — real Chrome session passes the anti-bot) and drop the file into
`D:\AI\android-tools\freevpn-re\turbovpn\`. Then run `bash run_turbo.sh` (it skips the download if a
`*.apk`/`*.xapk` is already present — or just point jadx/apktool at the file). Output →
`turbovpn/TURBO_HUNT.txt` (URLs, `ss://`/`vmess://` URIs, ip:port, gitleaks secrets, bootstrap key).

- **If it's Shadowsocks:** its servers plug into DJProxy's **existing SOCKS5 engine** via a bundled
  local `shadowsocks-libev` → `127.0.0.1:1080`, **no WireGuard needed for these**. Best-case, lowest effort.
- **If it's proprietary-over-TLS:** likely not worth reimplementing; skip.

**Net:** Turbo VPN does not change the Phase 1–3 plan; treat it as a possible bonus SOCKS5 source once
the APK is in hand. Phases 1–3 (WARP + Windscribe + Proton) are the shippable core and need no APK.

---

## 7. Data model (suggested)

```kotlin
data class FreeVpnServer(
  val provider: Provider,          // WARP, WINDSCRIBE, PROTON, (TURBO)
  val country: String,             // "US"
  val city: String,                // "Dallas"
  val transport: Transport,        // WIREGUARD | SHADOWSOCKS
  val endpointHost: String,        // "engage.cloudflareclient.com" / "dfw-86-wg.whiskergalaxy.com"
  val endpointPort: Int,           // 2408 / 443
  val peerPublicKey: String?,      // WG only
  // creds resolved at connect-time (WARP: on-device reg; Windscribe/Proton: session-gated key reg)
)
```

## 8. License compliance checklist

- ✅ `wireguard-go` / `wireguard-android` → **MIT / Apache-2.0** — safe, keeps DJProxy permissive.
- ❌ Do **NOT** bundle `ics-openvpn` (GPLv2) — it would relicense the whole app.
- ✅ Public server-list data (Windscribe API, WARP endpoints) — factual IP:port data, no code license.
- ⚠️ WARP/Windscribe/Proton free tiers have ToS around automated/abuse use — keep it one identity per
  install, respect free-tier limits, present it as "community free servers, best-effort."

## 9. Implementer task list

- [ ] Add `com.wireguard.android:tunnel` dependency + a `WireGuardBackend` wrapping it.
- [ ] Add a "VPN mode" switch (SOCKS5/HTTP proxy ⇄ Free VPN) — enforce single active `VpnService`.
- [ ] **Phase 1:** on-device WARP registration (`/reg`) → `Config` → connect. Ship.
- [ ] **Phase 2:** bundle the 15 Windscribe free servers + session-gated WG key registration at connect.
- [ ] **Phase 3 (opt):** Proton free via `proton-vpn-api-core` flow.
- [ ] Liveness/latency probe before offering a server; auto-failover across WARP endpoint IPs.
- [ ] Await Turbo VPN RE result → if Shadowsocks, add SS→SOCKS5 local shim path (reuses current engine).

**Proof artifacts:** `warp-hideme/wgcf-profile.conf` (live WARP config),
`windscribe/windscribe_free_servers.json` (85 servers), `FREEVPN_MASTER.md` (full analysis).
