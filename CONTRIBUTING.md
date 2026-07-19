# Contributing to DJProxy

DJProxy is a small, focused app. The whole point is that a device-wide VPN either closes every
leak it claims to close, or it doesn't ship. That bar applies to every contribution.

## Before you open a PR

1. **Read `DESIGN.md`.** It is the single source of truth for the leak model, the two-process
   architecture, and the module ownership map. If your change disagrees with it, update the doc in
   the same PR and explain why.
2. **Know which lane you're touching.** The codebase is split into `core`/`net` (parsing and packet
   primitives), `proxy` (dialers, validator, loopback SOCKS front), `vpn` (the service, routing,
   watchdog, fail-closed logic), `engine` (the native transport and its process boundary), and `ui`.
   Cross-lane changes are fine, but keep the diff scoped and say in the PR description which lanes
   you touched.
3. **Never weaken a leak guarantee to make a feature easier to build.** If you think a guarantee is
   wrong, open an issue first.

## Build and test locally

```sh
# from the repo root
gradle assembleDebug
gradle testDebugUnitTest
```

There's no Gradle wrapper committed in this environment's setup; if you don't have one either,
call your local `gradle` binary directly the same way.

Release builds need a keystore and are entirely environment-variable driven — see the Build section
in `README.md`. Don't add a hardcoded signing fallback; the build is written to fail loudly instead.

## Rules that exist for a reason

- **The tunnel is never per-app.** No `allowBypass()`, no `addAllowedApplication`, no
  `addDisallowedApplication`, ever. A PR that adds any of these will be closed, not merged with
  changes requested — this is a hard product requirement, not a style preference.
- **`VpnService.protect()` has exactly one call site.** Every socket that talks to the real proxy
  goes through the shared `SocketProtector` seam in the `vpn` lane. Adding a second `protect()` call
  site anywhere in the codebase is a leak waiting to happen.
- **Fail closed, not open.** If a code path can't tell whether traffic is safely tunnelled, it drops
  the packet. It does not forward it "just this once."
- **Errors are typed and human.** New failure modes get a new `ProxyError` case with a real message
  and a one-line fix hint, not a generic exception bubbling up to the UI.
- **The native engine stays out-of-process.** Anything that runs the hev/lwIP transport needs to
  keep living in the isolated `:engine` process so a native crash can't take the tun down with it.

## Testing expectations

Anything touching packet parsing, the TCP state machine, or the DNS interceptor needs a unit test
that runs on the JVM (`app/src/test`) — these are fast, deterministic, and don't need a device.
Anything touching the live tun, the VPN builder, or Keystore-backed credential storage can only be
verified on a real device or emulator; say so explicitly in the PR and note what you actually ran.

Don't claim a fix is verified unless you ran it. "Should work" is not "works."

## Reporting a leak

If you find a way for traffic to escape the tunnel, please open an issue with concrete repro steps
(the proxy type, the traffic type, and how you observed the leak — e.g. ipleak.net or
browserleaks.com output). Leak reports get priority over feature requests.

## Style

Kotlin, standard formatting, no new dependencies without a reason in the PR description. Match the
existing doc-comment style — comments explain *why* a constraint exists, not what the next line of
code obviously does.
