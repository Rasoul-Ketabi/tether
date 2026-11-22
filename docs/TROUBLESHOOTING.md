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

Bluetooth tethering pairs the two phones over classic Bluetooth. iOS then runs Tether's GATT connection over that
classic link whenever it is the only one, where Tether's fast L2CAP channel cannot open, and moves it to Bluetooth LE
as soon as an LE link appears, which ends the session running at that moment. To keep one stable session, the Android
app holds an LE link to the paired iPhone at all times (link log: *LE link held*), so iOS uses LE and L2CAP from the
start and nothing moves underneath a session. If a session does start over the classic link, the first LE link ends it
once and the iPhone sets up again over LE within seconds.

Tether never uses Bluetooth's own encryption or pairing, so the pairing made for tethering cannot break it. Older
versions failed in this setup with *"PSM read failed: the encryption key size is insufficient"*, or connected and
dropped over and over: update both apps.

If it still drops while tethering, send both link logs (each session ends with a line saying its transport, how long
it lasted and why it ended) and use the **Wi-Fi hotspot** instead of Bluetooth tethering meanwhile: it does not
touch Bluetooth at all and is faster.

### After closing the app

- **Opened again**, the app links within a few seconds (it starts a fresh connect and scan every time it comes to the
  foreground).
- **Swiped away in the app switcher** (force quit): iOS does not start the app again for Bluetooth until you open it
  yourself, so calls and texts do not reach the iPhone meanwhile. No app can change this. Leave Tether in the
  background instead of swiping it away; it uses almost no battery while it waits.
- **Stopped by iOS** in the background (memory pressure): iOS starts it again when the Android phone's link comes
  up, which the Android app brings about every 30 s while it has no session. This can take from seconds to about
  a minute and a half.
- **In the background**: the session stays up; nothing to do.



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
