# Changelog

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
