# Tether

Tether puts an Android phone's calls, text messages, contacts and call history on your iPhone or iPad, or on a
second Android phone. The Android phone keeps the SIM cards and can stay in a bag; the two talk directly over an
end-to-end encrypted Bluetooth link, with no server and no account.

<table>
  <tr>
    <td align="center"><img src="docs/images/recents.png" width="190" alt="Recents: calls from both SIMs, missed calls in red"><br><sub>Recents</sub></td>
    <td align="center"><img src="docs/images/thread.png" width="190" alt="A text conversation"><br><sub>A conversation</sub></td>
    <td align="center"><img src="docs/images/one-time-code.png" width="190" alt="One-time codes with a Copy button, links and numbers recognised"><br><sub>One-time codes</sub></td>
    <td align="center"><img src="docs/images/contacts.png" width="190" alt="Contacts and Favourites"><br><sub>Contacts</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/keypad.png" width="190" alt="Keypad, the number grouped as you type"><br><sub>Keypad</sub></td>
    <td align="center"><img src="docs/images/in-call.png" width="190" alt="Call screen with the Audio menu for headsets and the car"><br><sub>In a call</sub></td>
    <td align="center"><img src="docs/images/phone-tools.png" width="190" alt="Link tab: carrier codes, Find phone and the ringer"><br><sub>Phone tools</sub></td>
    <td align="center"><img src="docs/images/messages-persian.png" width="190" alt="Messages in Persian, right to left"><br><sub>Persian</sub></td>
  </tr>
</table>

*Screenshots from the iOS simulator with demo data (test numbers and made-up names).*

## What works

- **Calls** through the iPhone's own call screen (CallKit): incoming ringing, answer, decline, dial, hang up, hold,
  mute, keypad tones, speaker, call waiting, "move the call here" between devices. Call audio is bridged both ways
  (Opus, wideband) on Android phones that allow it, see [Compatibility](#compatibility). An **Audio** menu moves a
  call to a Bluetooth headset or the car and back.
- **Numbers from other apps**: a "Call with" action for Shortcuts and Siri (also finds a number in shared text),
  `tether://dial` links, call-backs from the Phone app's Recents, and on iOS 18.2+ with a paid developer account,
  the default calling app for `tel:` links. Numbers are grouped like phones show them, on the keypad as you type.
- **Messages** (SMS): every conversation, live new texts, send from either SIM, read state shared with the Android
  phone, delete, mute, block, one-time codes with a Copy button, links and phone numbers recognised.
- **Contacts**: browse, search, edit, add, favourites; **Recents**: search, delete, clear, missed-call badge.
- **Notifications** for texts and missed calls, with quick reply.
- **Link tab**: battery and signal of each SIM, and remote switches for mobile data, the data SIM, Wi-Fi hotspot,
  Bluetooth tethering, airplane mode, network mode, and "Share VPN" (use the Android phone's VPN from the iPhone).
- **Phone tools**: carrier codes such as a balance check (USSD) on either SIM, "Find phone" (the Android phone rings
  loudly) and its ringer mode.
- English and Persian (right to left), in a dark design.

### On a second Android phone

The **Android client** (`android/client`, "Tether Client") does the same on another Android phone (Android 10 or
later): pairing by QR code, calls through Android's own call system (Bluetooth headsets and the car included) with
audio both ways, the keypad, Recents, Contacts and Favourites, Messages, notifications, the Link screen and its
switches, and "Call with" for `tel:` links in other apps. See [docs/ANDROID-CLIENT.md](docs/ANDROID-CLIENT.md); it is
tested on the build machine only so far (against a simulated Android phone), not yet on a second phone.

## What you need

| | |
| --- | --- |
| Android phone | Android 10 or later with Bluetooth LE advertising. Call audio needs Android 11 or later (full support on 13+) and a build that lets the shell user capture call audio; see [features by Android version](docs/devices/android-versions.md). Tether becomes its default phone app. |
| iPhone or iPad | iOS 16 or later. |
| Or a second Android phone | Android 10 or later with Bluetooth LE, for the Android client. |
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
| [ANDROID-CLIENT.md](docs/ANDROID-CLIENT.md) | The Android client: what it does, building, first run, tests |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the parts fit together; the wire protocol is in [protocol/PROTOCOL.md](protocol/PROTOCOL.md) |
| [SECURITY.md](SECURITY.md) | Threat model and reporting a vulnerability |
| [CONTRIBUTING.md](CONTRIBUTING.md) · [CHANGELOG.md](CHANGELOG.md) | |

## Compatibility

Tested on one pair only. Everything else is expected from the Android and iOS APIs used, not tried: please report
what you see (open an issue with your phone model, Android version and the Link log).

| Device / feature | Status |
| --- | --- |
| Samsung Galaxy A54, Android 17 (One UI) + iPhone 17 | **Tested**: everything above, including call audio |
| iPad (iOS 16+) | Builds and lays out in the simulator; untested on a device |
| Other Samsung phones, Android 13+ | Expected to work like the A54, including muting the phone's own earpiece and microphone during a bridged call |
| Other Android 13+ phones | Calls, messages, contacts and switches expected to work. Call audio works only if the build lets the shell user use the call-audio interception API; the phone's own earpiece is muted during a bridged call (voice-call stream), and on Qualcomm and MediaTek phones also its microphone, through each vendor's own audio controls (from their sources, untested on devices; the Link tab shows "Android phone silent") |
| Android 11-12 | Everything, with call audio on a best-effort path (voice-call capture); untested |
| Android 10 | Everything except call audio; the helper needs the TCP adb path; untested |
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

- One iPhone or iPad per Android phone at a time is the supported setup. The Android client can be paired next to it
  (protocol §5.1); that combination is untested on devices.
- Bluetooth range: the phones need to stay within a few metres.
- MMS, RCS, group messages, video calls and visual voicemail are not supported.
- iOS apps signed with a free Apple account expire after 7 days; rebuild with `tools/reinstall-ios.sh`.

## Legal

Use Tether only with your own phones and SIM cards, and follow your local law on recording and handling calls and
messages. Tether does not record calls.

## License

MIT, see [LICENSE](LICENSE). Third-party components and their licences: [NOTICE](NOTICE).
