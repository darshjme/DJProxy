# DESIGN_V6 — Proxy Vault, Live Status, Free Public Proxies, Rename

Single source of truth for DJProxy v6. The proxy **core is frozen** (`vpn/**`, `engine/**`,
`proxy/*Dialer.kt`, `proxy/LocalSocksServer.kt`, `proxy/Validator.kt`, `proxy/ProxyError.kt`,
`net/**`, `cpp/**`, `core/ProxyConfig.kt`, `core/ProxyParser.kt`, `vpn/CredentialStore.kt`).
v6 adds **only two new packages** (`store/`, `freeproxy/`) plus UI, and plugs into the existing
seams: `VpnController.apply(ProxyConfig)`, `Validator`/`PreflightValidator`, `CredentialStore`,
`ProxyConfig`. No core file is edited.

---

## 0. What v6 ships

| # | Feature | Lane(s) | Core edits |
|---|---------|---------|-----------|
| 1 | Proxy vault — save/list/reuse/edit/delete/reorder/default | store + ui | none |
| 2 | Live per-proxy status via `PreflightValidator` (no tunnel) | store + ui | none |
| 3 | Free public SOCKS5/HTTP list (jetkai + proxifly) | freeproxy + ui | none |
| 4 | Rename to **"DJProxy by Darshj.ai"** | ui (`res/values`) | none |
| 5 | VPN-Gate / OpenVPN | docs only (future epic) | none |

Design invariant: **status checks and vault reuse never widen the trust or route surface.** A
status check is a pre-flight dial on a normal (or protected) socket; it does not bring the tun up.
Vault reuse funnels through the exact `VpnController.apply()` path v5 already uses.

---

## 1. Seams (frozen contracts the UI plugs into)

Three new interfaces are introduced by the `store`/`freeproxy` lanes. **The UI depends only on these
interfaces**, never on their implementations, and never on core. The lanes own both the interface and
the concrete impl in their package.

### 1.1 `store/ProxyStore.kt` — the vault contract
```kotlin
package ai.darshj.djproxy.store

interface ProxyStore {
    /** Reactive vault, already ordered by [SavedProxy.order]; default entry (if any) is flagged. */
    val proxies: kotlinx.coroutines.flow.StateFlow<List<SavedProxy>>

    /** Insert a new saved proxy (assigns id + appends to order). Password persisted via CredentialStore. */
    suspend fun save(name: String, config: ai.darshj.djproxy.core.ProxyConfig,
                     origin: ProxyOrigin = ProxyOrigin.USER): SavedProxy
    suspend fun update(id: String, name: String, config: ai.darshj.djproxy.core.ProxyConfig)
    suspend fun delete(id: String)
    suspend fun reorder(orderedIds: List<String>)
    suspend fun setDefault(id: String?)          // null clears the default

    /** Reconstruct the FULL ProxyConfig incl. decrypted password for a reuse/apply. Null if the
     *  entry is gone or the password blob could not be decrypted (fail-closed — never returns a
     *  half-credential). */
    suspend fun resolve(id: String): ai.darshj.djproxy.core.ProxyConfig?

    /** The default entry to preselect on the hero, or null. */
    val defaultId: kotlinx.coroutines.flow.StateFlow<String?>
}
```

### 1.2 `store/StatusChecker.kt` — liveness contract
```kotlin
package ai.darshj.djproxy.store

interface StatusChecker {
    /** Live status map keyed by SavedProxy.id (and by FreeProxyEntry.key for free rows). */
    val statuses: kotlinx.coroutines.flow.StateFlow<Map<String, ProxyStatus>>

    /** Check ONE target now. Publishes Checking -> Reachable/Unreachable into [statuses]. */
    suspend fun check(key: String, config: ai.darshj.djproxy.core.ProxyConfig): ProxyStatus

    /** Bounded-concurrency "check all". Respects battery (see §3.3). */
    suspend fun checkAll(targets: List<Pair<String, ai.darshj.djproxy.core.ProxyConfig>>)
}
```

### 1.3 `freeproxy/FreeProxySource.kt` — free-list contract
```kotlin
package ai.darshj.djproxy.freeproxy

interface FreeProxySource {
    /** Fetch + parse + SSRF-screen the maintained public lists. Cached (§4.4); [force] bypasses TTL. */
    suspend fun fetch(force: Boolean = false): FreeProxyResult
}

sealed interface FreeProxyResult {
    data class Ok(val entries: List<FreeProxyEntry>, val fetchedAt: Long, val fromCache: Boolean) : FreeProxyResult
    data class Failed(val reason: String) : FreeProxyResult
}
```

The `ProxyViewModel` (ui lane) receives `ProxyStore`, `StatusChecker`, `FreeProxySource` as
constructor/attach params. Wiring is done by the ui lane in `MainActivity`/`DjProxyApp` (both ui-
owned) — the same pattern used to attach `VpnController`. No `VpnDependencies`-style global is
needed because these are process-simple singletons the ui lane constructs.

---

## 2. Data model

### 2.1 `store/SavedProxy.kt`
```kotlin
data class SavedProxy(
    val id: String,                 // UUID
    val name: String,               // user label, e.g. "DE residential"
    val type: ProxyType,            // reuse core enum
    val host: String,
    val port: Int,
    val username: String,           // plain (not a secret)
    val dnsServer: String,          // reuse ProxyConfig default 1.1.1.1
    val hasPassword: Boolean,       // true if an encrypted blob exists for this id
    val origin: ProxyOrigin,        // USER or FREE_PUBLIC
    val isDefault: Boolean,
    val order: Int,
)
enum class ProxyOrigin { USER, FREE_PUBLIC }
```
`SavedProxy` is the **display/metadata** projection. It deliberately carries **no plaintext password**
so it is safe to log/snapshot/recompose. The password lives only as ciphertext (see §2.3) and is
materialised on demand by `ProxyStore.resolve(id)` into a full `ProxyConfig` right before an apply.

`SavedProxy.toConfig(password)` and `ProxyConfig.toSavedProxy(name, ...)` are pure mappers in this
file (unit-tested).

### 2.2 Metadata persistence — `SharedPreferences`, no new dep
The vault metadata is a **hand-serialised** record list (no JSON lib, so it is fully unit-testable
under `unitTests.isReturnDefaultValues=true`, which stubs `org.json`). One prefs file
`djproxy_vault` (`Context.MODE_PRIVATE`):

- Key `vault.v1` → newline-delimited records; each record is a URL-encoded, ``-delimited tuple
  `id␟name␟type␟host␟port␟username␟dnsServer␟origin␟order`. URL-encoding neutralises the delimiter
  and any control chars in names.
- Key `vault.default` → default id or empty.
- Codec lives in `store/VaultCodec.kt` (pure `encode(List<SavedProxy>): String` /
  `decode(String): List<SavedProxy>`), the seam that makes serialization testable off-device.

Rationale vs Room/DataStore: the vault is a tiny, single-writer, single-process list (tens of
entries). A prefs blob + a pure codec is zero-dep, migration-trivial, and matches the app's existing
"no accidental complexity" posture. Room would add a KSP toolchain and a DB file for ~20 rows —
rejected.

### 2.3 Password at rest — **reuse `vpn/CredentialStore`, unchanged**
`CredentialStore` is `internal object` in the `vpn` package → **module-visible**, so the `store`
package calls it directly with zero edits:

- On `save`/`update` with a non-empty password:
  `val blob = CredentialStore.encrypt(password)` (AES-256-GCM, AndroidKeyStore, non-extractable).
  Store `blob` under prefs key `pw.<id>` in the same `djproxy_vault` file. `hasPassword = blob != null`.
- On `resolve(id)`: read `pw.<id>`, `CredentialStore.decrypt(blob)`; on null → return the config with
  an **empty** password (fail-closed: a dropped key never becomes plaintext, matching CredentialStore's
  own contract). If the proxy required auth, the subsequent `apply()` pre-flight will surface
  `AuthRejected` honestly rather than the app inventing a credential.
- On `delete(id)`: remove both the metadata record and `pw.<id>`.
- API 21–22 (no Keystore GCM): `encrypt` returns null → `hasPassword=false`; the entry is still saved
  (host/port/type/user), the password is simply session-only exactly as the live tunnel already
  degrades. **No password is ever written in the clear.** This is documented in the row UI ("password
  not saved on this Android version").

CredentialStore uses a fresh random IV per `encrypt` call and one shared KeyStore key, so multiple
proxy passwords coexist safely under distinct `pw.<id>` blobs.

### 2.4 Status model — `store/ProxyStatus.kt`
```kotlin
sealed interface ProxyStatus {
    data object Unknown : ProxyStatus                       // never checked
    data object Checking : ProxyStatus                      // in-flight
    data class Reachable(val latencyMs: Long, val exitIp: String?, val checkedAt: Long) : ProxyStatus
    data class Unreachable(val reason: String, val hint: String, val checkedAt: Long) : ProxyStatus
}
```
`Unreachable.reason/hint` are copied verbatim from the typed `ProxyError.message`/`hint` the
validator already produces (DNS fail / refused / timeout / auth rejected / not-a-socks5 / …), so the
status list speaks the same honest vocabulary as the connect flow.

---

## 3. Live status — how it uses `PreflightValidator` **without** the tunnel

### 3.1 The exact reuse
`proxy/PreflightValidator` is a public `class` implementing the `Validator` interface. It does a
**real** TCP connect + real SOCKS5/HTTP handshake + a real `GET http://www.gstatic.com/generate_204`
**through** the proxy, and returns `ValidationResult.Success(latencyMs, probeStatus, exitIp)` or
`ValidationResult.Failure(ProxyError)`. The `store` lane constructs it directly:

```kotlin
class ValidatorStatusChecker(
    private val validatorFactory: (SocketProtector) -> Validator = { p -> PreflightValidator(protector = p) },
    private val protector: SocketProtector = VpnRuntime.protector,   // §3.2
    private val maxConcurrency: Int = 4,
) : StatusChecker { … }
```

`check()`:
```kotlin
publish(key, Checking)
val res = validatorFactory(protector).validate(config)   // real dial, NO service start
val status = when (res) {
    is Success -> Reachable(res.latencyMs, res.exitIp, now())
    is Failure -> Unreachable(res.error.message, res.error.hint, now())
}
publish(key, status); status
```

Crucially this calls `Validator.validate()` **directly**. It never touches `VpnController.apply()`,
never starts `DjVpnService`, never builds a tun. Status checking is pure pre-flight — the same code
the Apply button runs *before* it is allowed to bring the VPN up, invoked standalone.

### 3.2 The protector choice (the one subtlety)
`PreflightValidator` needs a `SocketProtector`. Two cases:

- **No tunnel up** (the normal case): `VpnRuntime.protector` is a **no-op returning true** — the
  status socket is an ordinary device socket, dials the proxy directly, nothing is routed. Correct.
- **A tunnel IS already up** (user connected to proxy A, checking proxy B in the list): a naive
  unprotected socket would loop back into the tun and deadlock. Passing `VpnRuntime.protector` means
  the status socket is `protect()`-ed out of the tun via the *single* existing `VpnService.protect()`
  call site — so checks work correctly whether or not a tunnel is live, and **still never bring one
  up**. This reuses the exact seam `VpnController` uses; it does not create a second protect path (CI
  grep invariant preserved).

Result: status checking is correct in every state and remains strictly read-only w.r.t. the route
table.

### 3.3 Battery-respectful scheduling (do NOT hammer)
- **Bounded concurrency:** a `kotlinx.coroutines.sync.Semaphore(maxConcurrency=4)` fans `checkAll`
  out 4-at-a-time. Each check inherits the validator's 8 s connect + 8 s io timeouts.
- **Manual "Check all":** explicit user button on the Servers screen — always allowed.
- **Lightweight auto-refresh:** on entering the Servers screen, auto-check **only** entries whose
  `checkedAt` is older than `STALE_MS = 5 min` (or `Unknown`). A per-key min interval of 60 s debounces
  spam. No background/WorkManager job — checks run **only while the Servers screen is visible**
  (`DisposableEffect`/`LaunchedEffect` scope), so a backgrounded app never dials.
- **Battery-saver guard:** if `PowerManager.isPowerSaveMode` is true, auto-refresh is skipped (manual
  still works). No wakelocks, no alarms.
- Free-proxy lists can be large; the checker caps auto-checks to the **visible window** (the UI passes
  only on-screen rows), never the whole 200-entry free list at once.

### 3.4 What the row shows
Per row: a status dot (green Reachable / red Unreachable / amber spinner Checking / grey Unknown),
`latencyMs` (e.g. "142 ms"), relative last-checked ("2 min ago"), and on Unreachable the short
`reason`. A tap re-checks that single row.

---

## 4. Free public proxy list

### 4.1 Sources (MIT/CC, TXT endpoints only → zero JSON dep)
Fetched over **https** from raw GitHub, liveness-maintained upstream:

- **jetkai/proxy-list** — `raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/`
  `proxies-socks5.txt`, `proxies-http.txt` (plain `ip:port` per line).
- **proxifly/free-proxy-list** — `raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/`
  `socks5/data.txt`, `http/data.txt` (lines `socks5://ip:port` / `http://ip:port`).

TXT (not JSON) is deliberate: line-oriented `ip:port` parses with the existing `ProxyParser`
vocabulary and stays **fully JVM-unit-testable** under `unitTests.isReturnDefaultValues=true` (which
would stub `org.json`). This keeps the freeproxy lane at **zero new dependencies**.

### 4.2 Fetch — reuse existing networking
`freeproxy/RemoteFreeProxySource` performs a plain `HttpsURLConnection` GET (the app already ships no
HTTP client and needs none) with:
- connect/read timeout 10 s, `HTTPS` scheme enforced (reject non-https), follow-redirects **off**,
  `User-Agent: DJProxy`.
- **Response cap:** read at most `MAX_BYTES = 512 KB` per source (bounded `InputStream` read); truncate
  beyond.
- Best-effort parallel fetch of the 2–4 endpoints; a failed endpoint is skipped, not fatal.
- Alternatively may reuse `config/SubscriptionFetcher` (public, in `config/`, not a forbidden file) if
  its https+cap behaviour matches — lane's choice; default is a small self-contained GET to keep the
  lane independent.

### 4.3 Parse + SSRF/junk screening — `freeproxy/FreeProxyParser.kt` (pure)
For each line: extract `type`/`host`/`port` (via `ProxyParser.parse` reuse or a local `ip:port`
split), then **screen**, dropping any entry where:
- host is not a valid IPv4 literal, or port ∉ 1..65535;
- host is loopback (`127/8`), private (`10/8`, `172.16/12`, `192.168/16`), link-local (`169.254/16`),
  CGNAT (`100.64/10`), multicast (`224/4`), reserved (`0/8`, `240/4`), or `0.0.0.0`/broadcast —
  **closes the SSRF vector** where a poisoned list could point the checker/tunnel at the device's own
  LAN/metadata endpoints;
- duplicate `type:host:port` (dedupe).
Cap the merged result at `MAX_ENTRIES = 200` (deterministic order: source priority then as-listed) so
the picker and status checker can't be flooded.

Output: `FreeProxyEntry(key, type, host, port, sourceLabel)` where `key = "free:<type>:<host>:<port>"`
(stable, used in the status map and as a would-be `SavedProxy` seed). `toConfig()` yields a
`ProxyConfig` with no auth (public proxies are auth-less).

### 4.4 Cache — `freeproxy/FreeProxyCache.kt`
- In-memory `List<FreeProxyEntry>` + `fetchedAt`, mirrored to a prefs blob (`djproxy_freeproxy`,
  same hand-codec style as the vault) so the list survives process death.
- **TTL 6 h.** `fetch(force=false)` returns cache if fresh; `force=true` (pull-to-refresh / refresh
  button) always re-fetches. On fetch failure, serve stale cache with a "couldn't refresh" note rather
  than emptying the list.

### 4.5 Untrusted-server caveat (honest, unmissable)
The "Free servers" section header carries a persistent caveat chip:

> **Untrusted public proxies.** These are unvetted, community-listed servers. They can be slow, go
> offline, log traffic, or inject content. Don't send sensitive data. For real privacy use your own
> proxy or Tor.

A first-tap confirmation is shown before a free proxy is applied/saved (one-time per session), mirroring
the v4 import-consent gate. Free entries saved to the vault keep `origin = FREE_PUBLIC` and render a
small "public" badge so their provenance is never forgotten.

---

## 5. UX in the expressive UI (ui lane)

### 5.1 Navigation
Add `Route.Servers` to the existing sealed `Route` (Home/Settings/About → +Servers) + its
`RouteSaver` case. Entry points:
- Home header/`SourceStrip`: a new **"Servers"** action (list icon) opens `Route.Servers`.
- Settings: a "Saved proxies" row also routes there.
The vault is a full route (not a sheet) because it hosts a scrollable list + two tabs.

### 5.2 `ui/ServersScreen.kt` (new)
Two segmented tabs in the M3-Expressive shell (glass surfaces, existing `DjColors`/typography/motion):
- **Saved** — `LazyColumn` of `ProxyRow`s from `store.proxies`. Empty state: "No saved proxies yet —
  connect one and tap Save." Row actions: tap = **reuse** (apply), overflow = Edit / Delete / Set
  default / drag-reorder. A default row is pinned to top with a "Default" chip. "Check all" button in
  the tab header.
- **Free servers** — the §4.5 caveat header + `FreeProxySource.fetch()` results as `ProxyRow`s
  (public badge). Actions: tap = check+preview, "Save to vault" (→ `store.save`, origin FREE_PUBLIC),
  or "Use now" (apply). Pull-to-refresh + a Refresh button (`fetch(force=true)`).

### 5.3 `ui/components/ProxyRow.kt` + `ui/components/StatusChip.kt` (new)
`ProxyRow`: name/label, `type://host:port` (redacted — reuse `ProxyConfig.redacted()`), a
`StatusChip`, and a trailing overflow. `StatusChip`: dot + latency + relative time, colours mapped
Reachable→green, Unreachable→rose, Checking→amber spinner, Unknown→grey. Reorder via long-press drag
(Compose `detectDragGesturesAfterLongPress`, no new dep) emitting `store.reorder(ids)`.

### 5.4 Reuse / save flow (through the existing seam)
- **Reuse:** row tap → `viewModel.applySaved(id)` → `store.resolve(id)` (decrypt) →
  `controller.apply(config)` — **the identical v5 path**, incl. pre-flight and fail-closed bring-up.
  No new connect logic.
- **Save:** new `ui/sheets/SaveProxySheet.kt` (name field + "Save"), reachable from `ManualEditSheet`
  ("Save to vault" button) and from a connected state ("Save this proxy"). Calls `store.save(name,
  ui.config)`. Password is handed to `store.save` which encrypts via CredentialStore; the VM never
  persists plaintext.
- **Edit:** loads a `SavedProxy` into `ManualEditSheet` (add an `editingId` param) → `store.update`.
- **Delete / Set default / Reorder:** direct `store` calls; the reactive `StateFlow` re-renders.

### 5.5 ViewModel additions (`ui/ProxyViewModel.kt`, ui-owned)
New state exposed to the screen (all `StateFlow`): `savedProxies`, `proxyStatuses`, `freeProxies`,
`freeProxyBusy`, `defaultId`. New intents: `applySaved(id)`, `saveCurrent(name)`, `updateSaved(id,
config)`, `deleteSaved(id)`, `setDefault(id)`, `reorder(ids)`, `checkStatus(key, config)`,
`checkAllVisible(keys)`, `refreshFreeProxies(force)`, `saveFreeProxy(entry, name)`,
`applyFreeProxy(entry)`. These delegate to the three seams; the VM adds no persistence or dial logic
of its own. On first launch, if a `defaultId` exists it preselects that config into the hero (no
auto-connect — consistent with the v4 consent gate).

---

## 6. Rename → "DJProxy by Darshj.ai" (ui lane, `res/values`)

- `res/values/strings.xml`: `app_name` → **"DJProxy by Darshj.ai"** (drives `android:label`, launcher
  caption — truncation under the icon is expected and accepted per owner). Keep `tile_connect_label`
  short ("DJProxy") so the QS tile label stays legible.
- In-app title (`HomeContent` header `Text("DJProxy")`): render **"DJProxy by Darshj.ai"** (or keep
  "DJProxy" as the wordmark with the existing "· by darshj.ai" sub-line — the sub-line already exists;
  make the two consistent). About screen `Text("DJProxy")` → **"DJProxy by Darshj.ai"**.
- Splash already reads "DJProxy · by darshj.ai" — unchanged, now consistent.
No manifest edit needed: `android:label="@string/app_name"` already points at the renamed string.

---

## 7. VPN-Gate / OpenVPN — FUTURE EPIC (not built)

Recorded per owner decision ("later"). **Do not implement in v6.**

- VPN Gate publishes **OpenVPN-only** endpoints (`.ovpn` configs); it is not a SOCKS/HTTP proxy
  source, so it cannot ride DJProxy's existing dial path.
- Consuming it needs an embedded OpenVPN engine. `ics-openvpn` is **GPLv2**, incompatible with
  DJProxy's **MIT** license (linking GPLv2 would force the whole app to GPL). `openvpn3` is **MPL-2.0**
  (compatible) but ships **no Android JNI wrapper** — building one (NDK JNI bridge, tun integration,
  cert/credential plumbing, DNS) is a multi-week engine effort on par with the existing hev integration.
- Decision: park as a scoped epic. If pursued, the clean path is an `openvpn3`-MPL JNI sidecar service
  mirroring the `:engine` process model, exposing a local SOCKS front so it plugs into
  `VpnController.apply(socks5://127.0.0.1:port)` exactly like Tor — **zero core edits**, same seam.
- Study: `D:\AI\android-tools\vpngate-re\VPNGATE_STUDY.md`.

---

## 8. Test plan (per lane)

- **store:** `VaultCodecTest` (round-trip incl. delimiter/control-char names, empty, order), 
  `ProxyStoreTest` (save/update/delete/reorder/default, resolve decrypt via a fake CredentialStore
  seam or Robolectric-free pure codec path), `StatusCheckerTest` (Success→Reachable, each
  `ProxyError`→Unreachable mapping, bounded concurrency via a fake `Validator`, Checking transition).
- **freeproxy:** `FreeProxyParserTest` (ip:port + scheme lines, SSRF screen drops private/reserved/
  loopback/malformed, dedupe, MAX_ENTRIES cap), `FreeProxyCacheTest` (TTL fresh/stale, force,
  serve-stale-on-failure), `RemoteFreeProxySourceTest` (fake fetcher → parse+merge; https-only
  enforcement; byte cap).
All `StatusChecker`/`FreeProxySource` tests inject **fakes** for the `Validator`/fetcher — no network,
no device, JVM-only, respecting `unitTests.isReturnDefaultValues=true`. Target: keep the suite green
(254 → 254+new).

---

## 9. Disjoint file-ownership map

**store** (NEW `store/` package — vault + status model + StatusChecker; reuses `vpn/CredentialStore`
and `proxy/PreflightValidator` WITHOUT editing them):
- `app/src/main/java/ai/darshj/djproxy/store/SavedProxy.kt`
- `app/src/main/java/ai/darshj/djproxy/store/ProxyOrigin.kt` *(may fold into SavedProxy.kt)*
- `app/src/main/java/ai/darshj/djproxy/store/ProxyStore.kt`  *(interface + SharedPreferencesProxyStore)*
- `app/src/main/java/ai/darshj/djproxy/store/VaultCodec.kt`
- `app/src/main/java/ai/darshj/djproxy/store/ProxyStatus.kt`
- `app/src/main/java/ai/darshj/djproxy/store/StatusChecker.kt`  *(interface + ValidatorStatusChecker)*
- `app/src/test/java/ai/darshj/djproxy/store/VaultCodecTest.kt`
- `app/src/test/java/ai/darshj/djproxy/store/ProxyStoreTest.kt`
- `app/src/test/java/ai/darshj/djproxy/store/StatusCheckerTest.kt`

**freeproxy** (NEW `freeproxy/` package — free SOCKS/HTTP list provider):
- `app/src/main/java/ai/darshj/djproxy/freeproxy/FreeProxySource.kt`  *(interface + FreeProxyResult)*
- `app/src/main/java/ai/darshj/djproxy/freeproxy/FreeProxyEntry.kt`
- `app/src/main/java/ai/darshj/djproxy/freeproxy/FreeProxyParser.kt`  *(parse + SSRF screen)*
- `app/src/main/java/ai/darshj/djproxy/freeproxy/RemoteFreeProxySource.kt`  *(fetch + sources + merge)*
- `app/src/main/java/ai/darshj/djproxy/freeproxy/FreeProxyCache.kt`
- `app/src/test/java/ai/darshj/djproxy/freeproxy/FreeProxyParserTest.kt`
- `app/src/test/java/ai/darshj/djproxy/freeproxy/FreeProxyCacheTest.kt`
- `app/src/test/java/ai/darshj/djproxy/freeproxy/RemoteFreeProxySourceTest.kt`

**ui** (`ui/**` + `res/values/**` — list/picker/status UI, reuse flow, rename):
- `app/src/main/java/ai/darshj/djproxy/ui/ServersScreen.kt`  *(new)*
- `app/src/main/java/ai/darshj/djproxy/ui/components/ProxyRow.kt`  *(new)*
- `app/src/main/java/ai/darshj/djproxy/ui/components/StatusChip.kt`  *(new)*
- `app/src/main/java/ai/darshj/djproxy/ui/sheets/SaveProxySheet.kt`  *(new)*
- `app/src/main/java/ai/darshj/djproxy/ui/Route.kt`  *(add Route.Servers + saver)*
- `app/src/main/java/ai/darshj/djproxy/ui/ProxyScreen.kt`  *(wire Servers route + nav + rename title)*
- `app/src/main/java/ai/darshj/djproxy/ui/ProxyViewModel.kt`  *(vault/status/freeproxy state + intents)*
- `app/src/main/java/ai/darshj/djproxy/ui/sheets/ManualEditSheet.kt`  *(Save-to-vault + edit mode)*
- `app/src/main/java/ai/darshj/djproxy/ui/SourceStrip.kt`  *(Servers entry action)*
- `app/src/main/java/ai/darshj/djproxy/ui/SettingsScreen.kt`  *(Saved-proxies row)*
- `app/src/main/java/ai/darshj/djproxy/ui/MainActivity.kt`  *(construct + attach store/checker/freeproxy)*
- `app/src/main/res/values/strings.xml`  *(app_name rename + v6 strings)*

**platform** (`AndroidManifest.xml` + `app/build.gradle.kts` ONLY):
- `app/src/main/AndroidManifest.xml`  *(NO change expected — INTERNET already granted; free-proxy
  fetch needs no new permission. File owned here only to confirm no delta.)*
- `app/build.gradle.kts`  *(NO new dependency expected — TXT endpoints + hand-rolled codec avoid any
  JSON lib; kotlinx-coroutines + Compose already present. Owned here only if the freeproxy lane truly
  needs a dep, which the design avoids.)*

**docs** (`README.md` + `DESIGN_V6.md` + `assets/*.svg`):
- `D:\AI\DJProxy\DESIGN_V6.md`  *(this file)*
- `D:\AI\DJProxy\README.md`  *(v6 features: vault, live status, free servers, rename)*
- `D:\AI\DJProxy\assets\v6_vault_architecture.svg`
- `D:\AI\DJProxy\assets\v6_status_flow.svg`
- `D:\AI\DJProxy\assets\v6_freeproxy_pipeline.svg`

**No core file appears in any lane.** Frozen (`vpn/**` incl. `CredentialStore.kt`, `engine/**`,
`proxy/**` incl. `Validator.kt`/`PreflightValidator`, `net/**`, `cpp/**`, `core/**`, `tor/**`) is
read-and-reuse only, never written.
