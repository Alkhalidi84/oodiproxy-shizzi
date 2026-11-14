# NexaLink VPN engine architecture

## Decision

NexaLink will use a **single-active-engine** architecture. WireGuard remains the existing native engine. Sing-box is the preferred next engine for proxy profiles (VLESS, VMess, Trojan and their supported transports). Xray-core remains an optional compatibility engine, not a mandatory dependency in the first integration milestone.

This is intentionally not a design that starts two independent VPN engines at once. Android grants one app-owned VPN interface at a time, and competing TUN owners, routes, DNS handlers or foreground services would make connection state and recovery unreliable.

## Current implementation (verified in source)

- Android VPN service: `SeloomVpnService`.
- Current backend: WireGuard Android `GoBackend`.
- Capacitor bridge: `VpnBridgePlugin`.
- Current connect payload and parser: WireGuard-specific.
- `ProtocolType` is currently restricted to `WireGuard`, matching the only protocol implemented by the current parser, native bridge and service. VLESS, VMess and Trojan are intentionally not exposed as supported profile types until a real engine adapter is integrated and exercised.

## Target architecture

```text
React / Capacitor UI
        |
        v
Typed profile model + protocol parser
        |
        v
VpnEngineManager (one active engine, serialized transitions)
        |
        +---- WireGuardEngineAdapter (existing GoBackend)
        |
        +---- SingBoxEngineAdapter (planned libbox integration)
        |
        +---- XrayEngineAdapter (optional compatibility adapter)
        |
        v
One app-owned Android VpnService / TUN owner
        |
        +---- route/DNS/app-exclusion policy
        |
        +---- Shizuku / Shizzi tethering datapath
```

The diagram describes the target boundary, not completed code. The existing WireGuard service is the only implemented VPN engine today.

## Engine selection policy

1. A profile is parsed and validated before it reaches the native layer.
2. The profile protocol selects exactly one adapter.
3. The manager serializes connect, disconnect and switch operations.
4. A failed start must close any TUN descriptor, stop the selected engine and publish a failed state.
5. A server switch must stop the current adapter before starting another.
6. Engine state is queried from the native service; the UI must not infer a live tunnel from a successful start request alone.
7. Secrets are passed through private native calls and are never written to diagnostics, logs or crash reports.

## Why Sing-box first

Sing-box offers a unified configuration and routing model for common proxy profiles and also implements a WireGuard outbound. Its Android integration is based on the libbox platform API, which is a more appropriate starting point for a managed Android TUN service than trying to wrap a command-line executable.

The current WireGuard GoBackend should remain intact during the first Sing-box milestone. This minimizes regression risk and lets us compare the new engine with the known working WireGuard path.

## Why keep Xray optional

Xray-core is highly relevant for VLESS/VMess/Trojan compatibility, including deployments using REALITY and XHTTP. It should be added only if a defined profile or transport cannot be handled acceptably by the pinned Sing-box version, or if interoperability tests show a meaningful gap.

Running both engines concurrently is out of scope. The manager may choose either adapter for a profile, but only one owns the Android VPN interface at a time.

## Integration milestones

### M0 — truthful capability model
- Separate profile protocol from engine identity.
- Add explicit capabilities and reject unsupported profile types before requesting Android VPN permission.
- Keep current UI limited to protocols with a real parser and engine.

### M1 — Sing-box native proof of concept
- Pin a reviewed sing-box/libbox release and record its source commit and license.
- Generate/import the Android libbox binding for the app's supported ABIs.
- Implement a small adapter that accepts a validated config, starts/stops libbox and receives the Android TUN file descriptor from the app-owned VPN service.
- Add lifecycle, error, and traffic-stat callbacks.
- Start with VLESS, VMess and Trojan only after config validation and test fixtures exist. Add REALITY/XHTTP only after explicit compatibility tests.

### M2 — shared VPN lifecycle
- Extract common permission, foreground notification, TUN ownership, route/DNS, app exclusion and state reporting from the current WireGuard-only service.
- Keep WireGuard as a separate adapter and verify existing profiles still connect.
- Ensure a failed proxy-engine start cannot leave a stale TUN or foreground service.

### M3 — Xray compatibility decision
- Build a compatibility test matrix using real sanitized sample profiles.
- Add Xray only for verified gaps, using a separately version-pinned native binding and the same single-active-engine contract.
- Do not ship duplicate engines merely to advertise protocol names.

### M4 — tethering integration
- Test tethered TCP and UDP through each engine independently.
- Verify LAN bypass, DNS, IPv4-only networks, IPv6-capable peers, reconnect, and Shizuku session restart.
- Treat per-client counters as experimental until traffic accounting is reconciled against router-side measurements.

## Release gates

No new engine is considered supported until all are true:

- Configuration parser tests cover valid and malformed profile samples.
- ABI packaging is verified for every advertised device ABI.
- CI builds the Android APK with the pinned engine artifact.
- Start/stop/reconnect tests cover permission denial, invalid configuration, process recreation and rapid profile switching.
- Traffic is confirmed on both the phone and a tethered client.
- No private key, UUID, password, token or full share URI appears in logs or exported diagnostics.
- License notices and source-offer obligations are satisfied for every bundled native dependency.

## Current support status

| Capability | Status |
|---|---|
| WireGuard native VPN | Implemented; requires device validation |
| Shizuku smart tethering | Implemented; device/OEM dependent |
| Per-device usage | Experimental; userspace payload counters only |
| Sing-box Android adapter | Planned; not integrated |
| VLESS / VMess / Trojan | Not currently supported by the active VPN service |
| Xray Android adapter | Optional future work |
| Simultaneous Sing-box + Xray TUN | Not supported by design |

## Sing-box Android integration findings

The official Android client does not consume libbox from a normal Maven coordinate in its Gradle file. Its Android app declares a local `app/libs/libbox.aar`; the corresponding Sing-box source provides `cmd/internal/build_libbox`, which uses gomobile binding to generate the Android AAR from `experimental/libbox`. The libbox platform contract includes `OpenTun`, interface enumeration, network monitoring, DNS transport hooks, and related callbacks. NexaLink therefore needs a real Android `PlatformInterface` adapter that hands the file descriptor from its app-owned `VpnService.Builder.establish()` to libbox, plus a tested lifecycle around `CommandServer`. Copying only the service class from the upstream app would omit essential platform behavior.

The reviewed upstream Sing-box Android application source is GPL-3.0-or-later and its LICENSE also contains an additional condition restricting use of the upstream application's name or implied association in derivative works without prior consent. NexaLink must not copy/rebrand the upstream application or imply endorsement. Before distributing a libbox-based build, pin the exact Sing-box source revision, audit the license and all linked dependencies, include required notices and corresponding source as required, and obtain legal review of the additional license condition. The current NexaLink app has not yet incorporated libbox or upstream Sing-box Android application code.

The current source review is architectural research only; it does not establish a supported protocol or a working Android integration.
