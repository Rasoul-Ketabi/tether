# Troubleshooting

Start with the logs: on the iPhone, **Link › Link log**; on the Android phone, with USB debugging,
`tools/dbg.sh linklog`. They show every connect, drop (with Bluetooth's reason code) and setup step.

## The iPhone does not link

| Symptom | What to do |
| --- | --- |
| Link tab says *Searching* for a long time | Bluetooth on both phones; the Android phone's Tether notification should say *Advertising, waiting for iPhone*. If it says *Bluetooth off* or nothing, open Tether on the Android phone once |
| "The Android phone does not recognise this iPhone" | The pairing was removed on the Android phone: pair again (SETUP.md step 2) |
| "Pairing code expired" | The QR code is valid for 5 minutes and once only, and is lost if Tether restarts: tap Pair iPhone again for a new one |
| Links, then drops every few seconds | Check the link logs for the reason. Battery savers: give Tether unrestricted battery use on Android |
| Nothing after an update of the Android app | Open Tether on the Android phone once (some phones do not restart its service after an update) |

### Bluetooth tethering

When the two phones are also paired over classic Bluetooth (for Bluetooth tethering), iOS may reach Tether over
that classic connection instead of Bluetooth LE. Tether then uses its GATT transport instead of an L2CAP channel
(Android's L2CAP channels are LE only). It works the same, slightly slower. Tether never uses Bluetooth's own
encryption or pairing, so a Bluetooth pairing made for tethering cannot break it. Older versions required link
encryption and failed with *"PSM read failed: the encryption key size is insufficient"* in this situation: update
both apps.

## No call audio

1. In Tether on the Android phone, the **Audio helper** must say *Running*. If not, see the next section.
2. During a call, tap **Run call-audio self-test**: it lists each capture and inject path and whether it works.
3. The Android version must be 13 or later, and the build must let the shell user intercept call audio. Not every
   manufacturer allows it. If no path works, calls still work from the iPhone but their audio stays on the
   Android phone. Please report your model and the self-test result.
4. You hear the other side on the Android phone too: only Samsung phones let Tether mute the phone's own earpiece
   and microphone during a bridged call.

## The audio helper does not start

- Wireless debugging needs a Wi-Fi connection when the helper starts. Connect the Android phone to Wi-Fi, then tap
  **Start**.
- If the phone forgot the Wireless debugging pairing (some updates do), pair again (SETUP.md, Wireless debugging).
- From a computer, `tools/start-helper.sh` starts the helper over USB `adb` as a fallback.

## The iPhone app stopped opening

Apps signed with a free Apple account stop after 7 days. Run `tools/reinstall-ios.sh` again; your data and pairing
are kept. The Link tab shows how many days are left.

## Messages or calls are missing

Pull down to refresh in Messages, Recents or Contacts (syncs again with the Android phone). Texts deleted on the
Android phone disappear from the iPhone at the next sync.

## What to include in a report

- Phone models, Android and iOS versions, whether Bluetooth tethering was on.
- The iPhone's Link log and the output of `tools/dbg.sh linklog` (they contain no message text; check them for
  anything you do not want to share before posting).
- For audio problems: the self-test result.
