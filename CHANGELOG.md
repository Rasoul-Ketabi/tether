# Changelog

## Unreleased

- Stable link while the phones are also paired for Bluetooth tethering (the Android phone holds an LE link during a
  session, so the session runs over LE / L2CAP and does not move between links).
- Reconnects after the app is closed and reopened (within ~1-3 s), after a link drop while the app is in the
  background (under 1 s), and after the Android app restarts.
- The Android phone kept failing to advertise after many link changes ("too many advertisers"): fixed.
- Calls: the fast Bluetooth connection interval is requested at the first ring, and the iPhone's playout buffer
  adapts to bursty arrival (about 1 % of a call concealed instead of 4-6 %).
- A "Calls paused" notification when the app is closed while running; docs on what happens when it is swiped away.

## 1.0.0 — first public release

- Calls on the iPhone through CallKit with two-way call audio (Opus 16 kHz) on Android phones that allow call-audio
  interception; ringback while dialling, speaker, hold, mute, keypad, call waiting, moving a call between devices.
- Messages: full sync, live texts, send from either SIM, shared read state, delete, mute, block, one-time codes,
  rich text, notifications with reply.
- Contacts with favourites and editing; Recents with search, delete and a missed-call badge.
- Link tab: battery and signal per SIM, remote switches for mobile data, data SIM, hotspot, Bluetooth tethering,
  airplane mode, network mode, and Share VPN.
- End-to-end encrypted link (P-256 handshake, AES-256-GCM) over Bluetooth LE L2CAP with a GATT fallback; no
  dependence on Bluetooth pairing, so it works alongside Bluetooth tethering.
- iPhone and iPad; English and Persian; light and dark.
- Local configuration for team id, bundle id and display name; scripts that find the connected devices.
