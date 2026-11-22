# Tether

Tether puts an Android phone's calls, text messages, contacts and call history on your iPhone or iPad. The Android
phone keeps the SIM cards and can stay in a bag; the two talk directly over an end-to-end encrypted Bluetooth link,
with no server and no account.

<p>
  <img src="docs/images/recents.png" width="260" alt="Recents on the iPhone, with calls from two SIMs">
  <img src="docs/images/thread.png" width="260" alt="A text conversation on the iPhone">
</p>

*Screenshots from the iOS simulator with demo data.*

## What works

- **Calls** through the iPhone's own call screen (CallKit): incoming ringing, answer, decline, dial, hang up, hold,
  mute, keypad tones, speaker, call waiting, "move the call here" between devices. Call audio is bridged both ways
  (Opus, wideband) on Android phones that allow it, see [Compatibility](#compatibility).
- **Messages** (SMS): every conversation, live new texts, send from either SIM, read state shared with the Android
  phone, delete, mute, block, one-time codes with a Copy button, links and phone numbers recognised.
- **Contacts**: browse, search, edit, add, favourites; **Recents**: search, delete, clear, missed-call badge.
- **Notifications** for texts and missed calls, with quick reply.
- **Link tab**: battery and signal of each SIM, and remote switches for mobile data, the data SIM, Wi-Fi hotspot,
  Bluetooth tethering, airplane mode, network mode, and "Share VPN" (use the Android phone's VPN from the iPhone).
- English and Persian, light and dark.

## What you need

| | |
| --- | --- |
| Android phone | Android 12 or later with Bluetooth LE advertising. Call audio needs Android 13 or later and a build that lets the shell user intercept call audio (see below). Tether becomes its default phone app. |
| iPhone or iPad | iOS 17 or later. |
| Mac | Xcode with an Apple account (free is enough), [XcodeGen](https://github.com/yonaskolb/XcodeGen), JDK 17 and the Android SDK, to build both apps. |

## Quick start

```bash
git clone https://github.com/amirwmr/tether.git && cd tether
cp ios/Config/Local.xcconfig.example ios/Config/Local.xcconfig   # set your team id and a unique bundle id
cd android && ./gradlew :app:assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk && cd ..
tools/reinstall-ios.sh                                            # builds, signs and installs on your iPhone
```

Then follow [docs/SETUP.md](docs/SETUP.md): open Tether on the Android phone, grant what it asks, make it the
default phone app, pair Wireless debugging for call audio, and scan its QR code from the iPhone.

## Documentation

| | |
| --- | --- |
| [BUILD.md](docs/BUILD.md) | Building and installing both apps; Apple accounts, the 7-day signature, team and bundle id |
| [SETUP.md](docs/SETUP.md) | First run on both phones, permissions, Wireless debugging, pairing, the Link switches |
| [USAGE.md](docs/USAGE.md) | Calls, messages, contacts, internet sharing, Share VPN, notifications |
| [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Not linking, no audio, helper not starting, expiry, which logs to collect |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the parts fit together; the wire protocol is in [protocol/PROTOCOL.md](protocol/PROTOCOL.md) |
| [SECURITY.md](SECURITY.md) | Threat model and reporting a vulnerability |
| [CONTRIBUTING.md](CONTRIBUTING.md) · [CHANGELOG.md](CHANGELOG.md) | |

## Compatibility

Tested on one pair only. Everything else is expected from the Android and iOS APIs used, not tried: please report
what you see (open an issue with your phone model, Android version and the Link log).

| Device / feature | Status |
| --- | --- |
| Samsung Galaxy A54, Android 17 (One UI) + iPhone 17 | **Tested**: everything above, including call audio |
| iPad (iOS 17+) | Builds and lays out in the simulator; untested on a device |
| Other Samsung phones, Android 13+ | Expected to work like the A54, including muting the phone's own earpiece and microphone during a bridged call |
| Other Android 13+ phones | Calls, messages, contacts and switches expected to work. Call audio works only if the build lets the shell user use the call-audio interception API; the phone's own earpiece and microphone are not muted during a bridged call |
| Android 12 | Calls are controlled from the iPhone, but their audio stays on the Android phone |
| Single-SIM phones | Expected to work; SIM choices are hidden |
| Phones without Wi-Fi hotspot or Bluetooth tethering | Those switches are hidden |
| Bluetooth tethering between the same two phones | Works; the link then runs over its GATT transport (see [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)) |

Device notes for the test phone, and how to check your own: [docs/devices/galaxy-a54.md](docs/devices/galaxy-a54.md).

## Security in brief

Pairing is a one-time QR code that carries the Android phone's public key and a short-lived token. Every session
starts with a P-256 key agreement that authenticates both sides; everything after it is encrypted with AES-GCM.
Bluetooth's own pairing is not used or trusted. Nothing leaves the two devices. Details and the threat model:
[SECURITY.md](SECURITY.md).

## Limits

- One iPhone or iPad per Android phone at a time is the supported setup (a second paired client works but is
  untested).
- Bluetooth range: the phones need to stay within a few metres.
- MMS, RCS, group messages, video calls and visual voicemail are not supported.
- iOS apps signed with a free Apple account expire after 7 days; rebuild with `tools/reinstall-ios.sh`.

## Legal

Use Tether only with your own phones and SIM cards, and follow your local law on recording and handling calls and
messages. Tether does not record calls.

## License

MIT, see [LICENSE](LICENSE). Third-party components and their licences: [NOTICE](NOTICE).
