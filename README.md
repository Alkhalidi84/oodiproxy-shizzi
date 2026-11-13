# NexaLink

<p align="center">
  <strong>WireGuard VPN & Smart Tethering for Android</strong><br />
  <sub>Secure connectivity, Shizuku-powered hotspot sharing, and network visibility in one app.</sub>
</p>

<p align="center">
  <a href="https://github.com/Alkhalidi84/oodiproxy-shizzi"><img src="https://img.shields.io/badge/Android-11%2B-3ddc84?style=for-the-badge&logo=android&logoColor=white" alt="Android 11+" /></a>
  <a href="https://github.com/Alkhalidi84/oodiproxy-shizzi/tree/rebrand/nexalink-english"><img src="https://img.shields.io/badge/Interface-English-0ea5e9?style=for-the-badge" alt="English interface" /></a>
  <a href="https://github.com/Alkhalidi84/oodiproxy-shizzi/commits/rebrand/nexalink-english"><img src="https://img.shields.io/badge/Build-2.2.0-8b5cf6?style=for-the-badge" alt="Version 2.2.0" /></a>
</p>

## Overview

**NexaLink** is an Android networking application built with React, TypeScript, Capacitor, native Android services, and WireGuard. It combines native VPN connectivity with Shizuku-powered hotspot sharing, allowing tethered devices to use a selected upstream network without requiring root access on supported devices.

The application interface is English-first and uses a left-to-right layout.

## Features

### WireGuard VPN
- Native Android VPN service backed by the WireGuard tunnel library.
- Add and manage multiple WireGuard profiles.
- Import configurations using a `wireguard://` URI or a `.conf` file.
- Export profiles as a WireGuard configuration file or QR code.
- Configure DNS, MTU, Persistent Keepalive, Allowed IPs, and LAN route handling.
- Display live tunnel traffic counters, transfer rates, session duration, and latency.

### Smart tethering with Shizuku
- Integrated Shizzi Android module.
- Start hotspot sharing through Shizuku on supported devices without root.
- Route tethered-client traffic through the selected upstream network, including an active VPN when the VPN binding mode is enabled.
- Go-based datapath with IPv4 and IPv6 support.
- Session status, connected-client count, aggregate traffic counters, and recovery handling.
- Connected-device panel with best-effort MAC/IP inventory and saved blocked-device list.
- Capability-gated device blocking through Android Soft AP configuration when the OEM Wi-Fi stack supports client force-disconnect.
- Per-device byte accounting is not currently available through the public Android tethering counters; NexaLink does not split aggregate traffic into fabricated per-device figures.
- Shizuku permission and battery-optimization entry points.

### Network tools
- Public IP and country display while connected.
- SIM and cellular signal information where Android permissions and device APIs allow it.
- Application exclusions for the WireGuard tunnel.
- Local storage for server profiles, settings, and connection history.

## Requirements

- Android 11 (API 30) or later.
- ARM64 device for the currently integrated native datapath build.
- Shizuku installed and running for Shizzi tethering.
- Wireless debugging or root can be used to start Shizuku; root is not required by NexaLink itself for the supported Shizuku workflow.

Device and OEM tethering restrictions may affect availability. A successful build does not guarantee identical hotspot behavior on every Android device.

## Build from source

### Prerequisites
- Node.js and npm
- JDK 17 or later
- Android SDK / Android Platform 36
- Android Gradle Plugin and Gradle versions configured in the repository

### Build commands

```bash
npm ci
npm run lint
npm run android:sync
cd android
./gradlew assembleDebug
./gradlew assembleRelease
```

APK outputs are written to:

```text
android/app/build/outputs/apk/debug/
android/app/build/outputs/apk/release/
```

For a distributable release, configure a persistent signing keystore using the `RELEASE_KEYSTORE_PATH`, `RELEASE_KEY_ALIAS`, `RELEASE_STORE_PASSWORD`, and `RELEASE_KEY_PASSWORD` environment variables. Keep signing credentials outside the repository.

## Application identifiers

| Property | Value |
|---|---|
| App name | NexaLink |
| Application ID | `com.oodiproxyseloom1.shizzi` |
| Version name | `2.2.0` |
| Version code | `17` |
| Minimum Android | Android 11 / API 30 |
| Target SDK | API 36 |

The existing application ID is intentionally retained to preserve upgrade compatibility with installations signed using the matching original signing key. The application ID and signing key should not be changed casually.

## Project structure

- `src/` — React and TypeScript application interface.
- `src/components/` — screens, dialogs, controls, and visual components.
- `src/utils/` — WireGuard configuration, native bridges, and network helpers.
- `android/app/` — Android application, VPN service, and Capacitor plugins.
- `android/shizzi/` — integrated Shizzi Android module.
- `third_party/shizzi/` — upstream Shizzi source and related datapath implementation.

## Credits and third-party projects

NexaLink builds on open-source projects and libraries. Their original authors retain ownership and attribution for their respective work.

- **WireGuard** — VPN protocol and Android tunnel library: https://www.wireguard.com/
- **Shizuku** — privileged API bridge for Android applications: https://github.com/RikkaApps/Shizuku
- **Shizzi** — hotspot/tethering implementation integrated as a local Android module: https://github.com/carlelieser/shizzi
- **Capacitor** — native bridge for the web application: https://capacitorjs.com/
- **React, TypeScript, Vite, and Go/gVisor** — application and networking technologies.

## Maintainer

**Mustafa Al-Khalidi**  
Independent Android & Network Software Developer

- GitHub: https://github.com/Alkhalidi84
- Project: https://github.com/Alkhalidi84/oodiproxy-shizzi

NexaLink is maintained and customized by Mustafa Al-Khalidi. This attribution does not transfer ownership of third-party libraries or upstream Shizzi/WireGuard code.

## License

Review the licenses of the upstream projects and bundled dependencies before redistributing a modified build. Preserve all required copyright notices and license texts.
