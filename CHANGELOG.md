# Changelog

## Unreleased

- TetherCore carries libopus 1.5.2 (`COpus` target) with `LibOpusDecoder` / `LibOpusEncoder`: Opus with packet-loss
  concealment and complexity control on the iPhone, checked against AudioToolbox's Opus both ways.
- The iPhone app runs on iOS 16 (it needed 17): Observation comes from swift-perception, a few iOS 17 views have
  iOS 16 stand-ins; iOS 17 and later behave as before. The Swift package needs network access at build time.
- Opt-in TCP debugging fallback for the audio helper: where Wireless debugging is missing (Android 10) or fails,
  Tether connects to the phone's adbd in `adb tcpip` mode on `127.0.0.1:5555` (port settable, key approval). Setup in
  [docs/SETUP.md](docs/SETUP.md); the security trade-off is in [SECURITY.md](SECURITY.md).
- Android 10 and later are supported (was 12): call audio is attempted on Android 11 and 12 (voice-call capture and
  the incall-music inject path) and the app says clearly when the phone cannot bridge call audio (Android 10). Version
  guards for the newer Bluetooth, telephony, notification and Telecom APIs, legacy Bluetooth permissions below 12, and
  a lint check that fails on unguarded newer APIs. Feature table: [docs/devices/android-versions.md](docs/devices/android-versions.md).
- Phone numbers are shown grouped like phones show them ("+98 912 345 6789", "+1 (555) 010-0001"), on the
  iPhone's keypad as you type, in Recents, Contacts, Messages, the call screen, CallKit and notifications, and on
  the Android phone's call screen. National numbers follow the SIM's country (new optional `country` per SIM in
  `status`). Short codes, USSD codes and names are left as they are; what is dialled, stored and searched is unchanged.
- Stable link while the phones are also paired for Bluetooth tethering (the Android phone holds an LE link during a
  session, so the session runs over LE / L2CAP and does not move between links).
- Reconnects after the app is closed and reopened (within ~1-3 s), after a link drop while the app is in the
  background (under 1 s), and after the Android app restarts.
- The Android phone kept failing to advertise after many link changes ("too many advertisers"): fixed.
- Calls: the fast Bluetooth connection interval is requested at the first ring, and the iPhone's playout buffer
  adapts to bursty arrival (about 1 % of a call concealed instead of 4-6 %).
- iPhone: call numbers from other apps. `tel:` links (as the iOS 18.2+ default calling app, paid accounts only),
  the `tether://dial?number=` scheme, a "Call a Number" Shortcuts / Siri action that also finds a number in text,
  and optional call-backs from the Phone app's Recents. Links always ask before dialling; service codes and
  extensions are kept.
- Calls on Bluetooth headsets and car kits: an **Audio** menu on the call screen (iPhone, Speaker, each accessory)
  moves the call there and back, routes chosen on the system screen, Control Center or in the car are followed,
  the speaker button follows the real route, and the call audio rebuilds itself when a hands-free microphone
  changes the sample rate instead of going silent. Route changes are listed in the Link log.
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
