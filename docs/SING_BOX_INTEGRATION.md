# Sing-box integration plan

## Current status

NexaLink currently has a working WireGuard engine and an exclusive VPN-engine lifecycle manager. Sing-box is **not yet integrated into the Android runtime**. The VLESS, VMess, and Trojan options must remain unavailable or clearly marked as not implemented until the native engine, configuration validation, and connection lifecycle tests pass.

This repository deliberately does not copy or rebrand the separate sing-box-for-android application. It integrates the upstream Sing-box core through its libbox AAR and NexaLink-owned Android platform adapter.

## Pinned native build

The manual GitHub Actions workflow `.github/workflows/build-singbox-libbox.yml` builds the upstream `SagerNet/sing-box` tag `v1.14.4` for `arm64-v8a`. It follows the upstream build process and pins the toolchain:

- Go 1.26.10
- OpenJDK 17
- Android NDK r28
- Upstream libbox build command: `go run ./cmd/internal/build_libbox -target android -platform android/arm64`

The workflow publishes both `libbox.aar` and `libbox-legacy.aar` as a CI artifact with SHA-256 checksums. This is a reproducible native-library build step, not a runtime integration or an APK containing Sing-box. The artifact must be inspected and explicitly imported into the Android build only after API and ABI checks.

## Verified native artifact (2026-11-14)

The pinned upstream build completed successfully for commit `387e742113f1f265058136b144ad8458845108d8`.

- `libbox.aar`: 28,678,035 bytes.
- Native library: `jni/arm64-v8a/libbox.so` (80,947,216 bytes inside the AAR).
- The generated Java API includes `PlatformInterface`, `TunOptions`, `CommandServer`, and `CommandServerHandler`.
- SHA-256: `9b6d9a862907e77683ba1f00dbcb28c5d76f3e431c039f50f22a38f7b8471ede`.

A follow-up CI gate now checks those required API classes, confirms that the AAR contains exactly one native ABI (arm64-v8a), and verifies the published checksums before the artifact is uploaded. This is a build-integrity check only: it does not imply that the AAR is packaged in NexaLink or that a Sing-box VPN session has been tested.

## Runtime integration design

1. **One VPN owner.** Keep `AlkhalidiVpnService` as the sole Android `VpnService`. WireGuard and Sing-box must implement the same internal engine contract and be mutually exclusive through `VpnEngineManager`. Never let both engines establish or own a TUN concurrently.
2. **Platform adapter.** Implement the complete libbox `PlatformInterface` required by the pinned upstream version. Treat unsupported platform capabilities explicitly; do not return fake success values.
3. **TUN descriptor ownership.** Sing-box's `OpenTun(TunOptions)` must create the Android TUN through the service's `VpnService.Builder`, configure addresses, routes, DNS, MTU and allowed/disallowed applications, and transfer a valid descriptor to libbox. Define exactly one owner and close path for every descriptor to prevent leaks and double-close races.
4. **Command server lifecycle.** Create, start, monitor, and close `libbox.CommandServer` with the VPN service lifecycle. Surface native startup errors and service state to the UI; do not report connected until the engine reports a running service and the TUN is established.
5. **Network changes.** Implement default-network/interface monitoring and bind outbound sockets to the selected Android `Network` where appropriate. Handle network loss, handover, DNS updates, and service stop without leaving stale callbacks.
6. **Config boundary.** Parse and validate user-provided Sing-box JSON before starting the native service. Reject malformed or unsupported configuration with actionable errors. Never place private keys, passwords, UUIDs, or full config content in logs or diagnostics exports.
7. **Protocol support.** Enable VLESS, VMess, and Trojan only after protocol-specific sample configurations and integration smoke tests succeed. A URI parser or UI selector alone is not protocol support.
8. **No-root constraint.** Use Android's public `VpnService` APIs for tunnel ownership. Use Shizuku only for supported hotspot operations; do not depend on root shell, iptables, or hidden privileged APIs for the VPN engine.

## Required verification before enabling protocols

- Build the pinned AAR and verify it contains the expected Android ABI and libbox classes.
- Add Android adapter tests for TUN setup failure, descriptor cleanup, command-server startup/stop, and network callbacks.
- Add engine-manager tests for WireGuard-to-Sing-box and Sing-box-to-WireGuard switching, including stop failure and rollback failure.
- Validate a minimal local test configuration for each supported protocol and confirm traffic traverses the Android TUN.
- Test VPN permission denial, process recreation, service destruction, airplane mode, Wi-Fi/cellular handover, DNS failure, and repeated connect/disconnect.
- Confirm the app still builds and runs without Shizuku permission; Shizuku is optional for VPN use.

## Licensing and notices

Review the exact upstream tag's license and the licenses of the modules included in the generated AAR before distributing builds. Preserve upstream copyright and license notices, provide corresponding source and modification notices where required, and keep the app's own attribution distinct from upstream project branding. Do not copy the separate Android client application's source or assets as a shortcut.
