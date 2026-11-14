# Sing-box integration

## Current implementation status

NexaLink contains an Android Sing-box adapter in `android/app/src/main/java/com/seloomwarp/vpn/SingBoxEngine.kt`. It uses the pinned libbox Android binding and runs inside the same `AlkhalidiVpnService` used by WireGuard. It does not start a second Android `VpnService`.

The Capacitor bridge exposes proxy-link parsing and `connectProxy`. The current share-link parser supports VLESS, VMess and Trojan URI forms and builds a Sing-box configuration. Parser support is not a promise that every server-side transport or option is supported: the generated configuration must still be accepted by the pinned native core and verified against a real endpoint.

The current CI success proves compilation, unit tests and APK packaging only. It does not prove that a real proxy handshake, traffic routing, tethered-client traffic, or device-specific Shizuku operations work.

## Engine and TUN ownership

- `AlkhalidiVpnService` is the only Android `VpnService` declared by the app.
- `VpnEngineManager` serializes start, stop and engine switching.
- `WireGuardEngine` uses the existing WireGuard GoBackend.
- `SingBoxEngine` uses libbox `CommandServer`, `PlatformInterface` and `CommandServerHandler`.
- Only one engine may own the app VPN interface at a time.
- A failed stop prevents a second engine from starting. A failed start attempts cleanup; if cleanup fails, ownership remains marked uncertain so a competing engine is not started.
- Sing-box obtains its TUN through `VpnService.Builder.establish()` in `openTun`. The returned descriptor is retained by the adapter until native service shutdown and cleanup.
- The service's traffic snapshot reads WireGuard backend statistics or Sing-box native status counters, according to the active engine.

## Pinned native dependency

The repository pins upstream Sing-box v1.14.4 and builds the Android libbox binding for `arm64-v8a`. The workflow and artifact verification are documented in `.github/workflows/build-singbox-libbox.yml` and the corresponding CI gate. The app's current native ABI filter is ARM64 only; devices without ARM64 support are not covered by this build.

## Share-link support boundary

The parser currently handles:
- VLESS UUID credentials, TLS and REALITY parameters, and TCP/WS/gRPC/HTTP(H2) transport forms supported by its mapping.
- VMess Base64 JSON share links and the mapped TLS/transport fields.
- Trojan password links with TLS and supported transport mappings.

The parser is intentionally not a full general-purpose Sing-box JSON editor. It does not promise support for every V2Ray URI extension, Xray-specific feature, XHTTP, arbitrary plugins, chained proxies, subscription formats, or every flow/security combination. Unsupported options should fail clearly rather than be silently advertised as working.

## Platform adapter behavior

The adapter implements the libbox callbacks needed by the current integration, including:
- Android TUN establishment and route/address/DNS configuration.
- VPN socket protection via `VpnService.protect`.
- Default-network monitoring and interface enumeration.
- Connection-owner lookup on Android 10+.
- Native traffic-status subscription.

Optional platform features not implemented by NexaLink (for example system proxy control, local DNS transport hooks, notification delivery, shell/SSH integration, bridge support and neighbor monitoring) are reported as unavailable or left unsupported; they must not be represented as successful features.

## Required release validation

Before calling Sing-box production-ready, complete all of the following on physical devices:
1. Import and connect sanitized VLESS TCP/TLS, VLESS WS/TLS, VLESS REALITY, VMess WS/TLS, and Trojan TLS test profiles against controlled endpoints.
2. Verify IPv4, IPv6, DNS, LAN bypass, excluded applications and MTU behavior.
3. Verify WireGuard -> Sing-box -> WireGuard switching, repeated connect/disconnect, invalid configuration, permission denial, service destruction and process recreation.
4. Verify Wi-Fi/cellular handover, airplane mode, network loss and recovery.
5. Verify hotspot clients can pass TCP, UDP and DNS through each selected VPN engine with Shizuku granted and revoked.
6. Compare phone and tethered-client traffic counters against independent measurements.
7. Verify no UUID, password, private key, full share link or raw config appears in logs, notifications or exported diagnostics.
8. Test every advertised ABI and Android API/OEM family. Current CI packaging is ARM64 only.
9. Complete license and source-notice review for libbox and all transitive native dependencies before distribution.

## Security and diagnostics

Never log complete share links, raw Sing-box configuration, UUIDs, passwords, private keys, REALITY public keys or tokens. Diagnostics should report profile type, engine state, error category and redacted native causes only. Treat profile data as untrusted input and validate it before requesting VPN permission or creating a TUN.

## Licensing

Pin the exact upstream source revision used for each release. Preserve required copyright and license notices and provide corresponding source/modification notices where required. Keep NexaLink branding distinct from upstream projects and do not imply endorsement.
