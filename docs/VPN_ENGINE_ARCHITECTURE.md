# NexaLink VPN engine architecture

## Decision

NexaLink uses a single-active-engine architecture. WireGuard and Sing-box are separate adapters managed by one serialized lifecycle manager. They must never own competing Android VPN interfaces or TUN descriptors concurrently.

## Current source implementation

- Android VPN owner: `AlkhalidiVpnService`.
- Lifecycle coordinator: `VpnEngineManager`.
- WireGuard adapter: `WireGuardEngine`, backed by the existing WireGuard Android GoBackend.
- Sing-box adapter: `SingBoxEngine.kt`, backed by the pinned libbox Android binding.
- Capacitor bridge: `VpnBridgePlugin`.
- Proxy share-link parser: `SingBoxProfileParser`.
- Shizuku/Shizzi tethering datapath: separate from the app VPN engine; it must not be treated as a second phone VPN owner.

## Runtime flow

```text
React / Capacitor UI
        |
        +---- WireGuard profile -----------------------------+
        |                                                     |
        +---- VLESS / VMess / Trojan share link               |
                         |                                    |
                         v                                    v
                 VpnBridgePlugin                        VpnBridgePlugin
                         |                                    |
                         v                                    v
                WireGuard payload                     SingBoxProfileParser
                         |                                    |
                         +----------------+-------------------+
                                          |
                                          v
                                AlkhalidiVpnService
                                          |
                                          v
                                  VpnEngineManager
                                   /           \
                                  v             v
                          WireGuardEngine   SingBoxEngine
                                  \             /
                                   \           /
                                    v         v
                              One Android VpnService
                                  and one TUN owner
```

## Lifecycle and failure policy

1. Validate a profile before starting the VPN service.
2. Request Android VPN permission before starting an engine.
3. Serialize all engine transitions through `VpnEngineManager`.
4. Stop the previous engine and confirm it is no longer running before starting the requested engine.
5. After start, verify the requested adapter reports a running state.
6. On start failure, attempt to stop the requested engine and release its native resources.
7. If stop or rollback fails, retain an uncertain owner and block a competing engine. A false claim of a clean disconnect is more dangerous than a visible failure.
8. Stop callbacks, statistics clients, network callbacks and TUN descriptors during service teardown.
9. Do not report a connection as established solely because the Capacitor bridge accepted a start request. The UI must use native connection state and surface asynchronous startup failures.

## Sing-box adapter

The Sing-box adapter creates a libbox `CommandServer`, starts or reloads the service with a validated config, implements the required `PlatformInterface` callbacks, and creates its TUN through the owning `VpnService.Builder`. It also receives native status counters and monitors the default Android network.

The current adapter intentionally does not claim support for every libbox platform capability. System proxy management, shell/SSH, bridge features, local DNS transport, notification delivery and neighbor monitoring are not fully implemented. Unsupported capabilities must remain unavailable rather than return fake success.

## WireGuard adapter

WireGuard continues to use the existing GoBackend and profile data path. The shared lifecycle manager provides exclusive ownership and safe switching; it does not replace WireGuard's native backend or its established configuration format.

## Shizuku / Shizzi relationship

Shizuku/Shizzi is used for supported hotspot/tethering operations. It is not the owner of the phone's Android VPN service. Its TestNetworkManager/gVisor datapath is a separate data path and is subject to Android version, OEM implementation, permission and shell identity constraints.

A working phone VPN does not prove tethered-client routing works, and a working Shizzi hotspot session does not prove Sing-box or WireGuard can connect. Test the paths independently and then together.

## Current limitations

- Current APK native ABI filter is `arm64-v8a`; other ABIs are not packaged.
- Protocol URI parsing does not guarantee server interoperability for every transport or extension.
- Per-client hotspot byte accounting is not yet authoritative on all Android/OEM builds.
- Device-level TUN, network handover, VPN switching and tethered-client tests remain release gates.
- Xray is not an active engine in this architecture. Add it only for demonstrated compatibility gaps, using the same single-engine contract.

## Release gates

A release may be called production-ready only after:
- Parser tests and native config validation pass.
- CI builds and verifies the APK and every advertised ABI.
- Real endpoint tests cover each advertised protocol/transport.
- WireGuard/Sing-box switching, cleanup and rollback pass on devices.
- IPv4, IPv6, DNS, LAN bypass and excluded-app routing are verified.
- Shizuku permission granted/revoked and tethered TCP/UDP/DNS are verified.
- Network handover, process death, service recreation and repeated connect/disconnect pass.
- Diagnostics contain no secrets.
- License and corresponding-source obligations are met.
