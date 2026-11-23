# Hybrid BLE + Wi-Fi fast lane (experimental)

Status: feature branch; not production-tested. Both phones need this version to use the fast lane. An older peer continues using BLE because unknown messages are ignored.

## Preserved original capabilities

The original BLE L2CAP/GATT connection, background CoreBluetooth wake characteristic, iOS CallKit notifications, Android Telecom and wireless-debugging shell audio helper are unchanged. Wi-Fi is OPTIONAL for Opus call audio and large sync pages. BLE remains essential for wake and urgent call/SMS control. No cloud server, second pairing or account is needed.

## Connection offer and networking

Android opens a short-lived TCP listener after a valid BLE iPhone session has established. The listener binds only to a private IPv4 address on a Wi-Fi/hotspot interface, never to wildcard, VPN or cellular addresses. If there is no usable interface, it opens no listener and uses BLE normally.

Android sends a sealed BLE message with type wifi.offer and body fields v=1, host (actual interface address), port (ephemeral bound TCP port), token (32-byte base64url secret). The secret is generated for this BLE session and is never persisted. iOS may request a fresh offer with a sealed wifi.probe message on BLE. iOS explicitly restricts the connection to a Wi-Fi interface and requires Local Network permission. Joining a hotspot is a user-controlled step.

## Authentication and encryption

1. Client sends ASCII THW1 followed by a random 16-byte client nonce Nc.
2. Server replies THW1 + 16 random bytes Ns + HMAC-SHA256(secret, UTF8("tether/hybrid-wifi/1/server") || Nc || Ns).
3. Client verifies HMAC and sends HMAC-SHA256(secret, UTF8("tether/hybrid-wifi/1/client") || Nc || Ns).
4. Server verifies the HMAC in constant time. Both derive 64 bytes from HKDF-SHA256 with salt Nc||Ns, IKM=secret and info="tether/hybrid-wifi/1". The first 32 bytes are Android-to-iOS; the second 32 are iOS-to-Android.
5. Server sends wifi.ready as the first AES-256-GCM sealed record; only then does the client mark Wi-Fi ready.

The existing Tether record framing, audio/control channel numbering, strict replay counters and SecureSession are reused with SEPARATE Wi-Fi keys and counters (never those of BLE).

The Wi-Fi server accepts only authenticated audio from iOS. SMS sending, call control and network switching stay on BLE. The listener closes when the primary BLE session ends.

## Transport selection

| Traffic | Route |
| --- | --- |
| Incoming call and SMS events; iOS wake; call answers/mute/hold/DTMF | BLE |
| Pairing and all small privileged commands | BLE |
| SMS, call-history, contact and sync-ID pages | Wi-Fi if authenticated at the start of a sync; BLE fallback |
| Two-way Opus/PCMU 20-ms voice frames | Wi-Fi if authenticated; BLE fallback |

Android and iOS select the fast lane dynamically. If it is not ready or a send fails, the original BLE transport carries the same frame. After Wi-Fi closes in the middle of a sync, the iOS client must request another incremental sync over BLE to reconcile possible missing data.

A successful TCP write is not an end-to-end application ACK. A more comprehensive delivery/ACK and resume protocol is a subsequent hardening step.

## Compatibility and realistic limitations

Older peers ignore the new messages and retain their old behavior. A faster network does not guarantee better cellular voice quality; source capture, Opus settings, latency and jitter remain important. Existing codec/rate choices are deliberately unchanged.

iOS does not keep arbitrary TCP sockets active indefinitely in the background. The established BLE wake/CallKit mechanisms remain the reliable notifications path. Whether an active call keeps the Wi-Fi lane available while iOS is locked must be confirmed on physical devices.

The current Android listener selects one private IPv4 Wi-Fi/hotspot interface. Complex concurrent AP+STA configurations, OEM-specific interface names and IPv6-only networks require extended discovery.

## Acceptance tests on real devices (NOT YET EXECUTED)

1. Both devices on the same router: Wi-Fi fast lane connected, BLE remains available.
2. iPhone joins Android hotspot: same outcome without public internet.
3. iPhone locked, incoming call: BLE wakes CallKit; two-way audio uses Wi-Fi when ready.
4. Locked iPhone receives SMS instantly; send SMS and sync full SMS/contact/call history.
5. Disconnect Wi-Fi mid-call: verify BLE audio fallback without ending the call.
6. With no Wi-Fi, all original BLE workflows remain intact.
7. Malformed HMAC/AEAD, replay, unknown device, huge frames, repeated joins.
8. Compare jitter, latency, power consumption and recovery on identical call routes.
