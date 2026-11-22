# Using Tether

Everything happens in the iPhone app; the Android phone can stay in a bag. Both phones keep working on their own:
what you do on the Android phone shows up on the iPhone, and the other way round.

## Calls

- **Incoming calls** ring on the iPhone with the system call screen, also on the lock screen. With two SIMs, the
  SIM's name follows the caller's name ("Sara · Work").
- **Answer, decline, hang up, mute, hold, keypad, speaker** work as in the Phone app. Holding the phone to your ear
  turns the screen off.
- **Dialling**: Keypad, Recents or a contact. With two SIMs you choose the SIM; the phone's own default is used
  otherwise. While the call connects you hear a ringback tone from the iPhone.
- **Call waiting**: a second call rings as usual; answering it holds the first.
- **Move the call here**: a call answered on the Android phone itself shows on the iPhone with *Move here*, which
  moves its audio to the iPhone.
- **Audio** is Opus at 16 kHz in both directions. The Link log lists, after each call, frames sent and received,
  gaps concealed and the link's round-trip time.

## Messages

- All conversations, with new texts arriving live. Long texts, emoji and right-to-left text are shown as in
  Messages; links, phone numbers and one-time codes are recognised (codes get a Copy button in the notification).
- **Send** from either SIM (the conversation remembers the last one used). A text that could not be confirmed shows
  *Not Delivered* and is never sent twice by itself.
- **Read state** is shared: reading on either phone marks the conversation read on both.
- **Swipe** a conversation to delete, mute (no notifications) or block the sender. Deleting removes it from the
  Android phone too.
- Search finds names, numbers and message text.

## Contacts and Recents

- Contacts: search (any notation of a number, Persian digits too), edit, add, delete numbers, favourites at the
  top. Edits are written to the Android phone's contacts and keep each number's account and label.
- Recents: all calls with the SIM used, search, delete one or all, a *Missed* filter. Missed calls badge the tab and
  the app icon and post a notification, as Phone does. Blocked numbers never count.
- Blocking a number blocks it on the Android phone (Android's own block list), so it is blocked whichever phone
  you use.

## Internet sharing

Use the Android phone's mobile data on the iPhone:

- **Wi-Fi hotspot** (Link tab): turn it on, then join it in the iPhone's Settings › Wi-Fi. Fastest.
- **Bluetooth tethering**: turn it on, then on the iPhone pick the Android phone under Settings › Bluetooth (pair
  it once when asked). Slower, but no Wi-Fi needed. While it is on, Tether's own link also uses the classic
  Bluetooth connection, see [TROUBLESHOOTING.md](TROUBLESHOOTING.md#bluetooth-tethering).

## Share VPN

Android does not send hotspot or tethered traffic through a VPN app. *Share VPN* runs a small proxy in Tether that
does: apps on the iPhone that use it go out through the Android phone's VPN.

1. Turn the VPN on in its own app on the Android phone, and the hotspot on.
2. Turn on **Share VPN** in the Link tab.
3. Once, on the iPhone: Settings › Wi-Fi › the hotspot network › Configure Proxy › Automatic, URL as shown under
   Share VPN in the Link tab.

The proxy listens only on the hotspot and tethering interfaces, never on the phone's own Wi-Fi or mobile network.
iOS has no proxy setting for Bluetooth tethering, so Share VPN works with the hotspot.

## Notifications

New texts and missed calls are notified on the iPhone; reply from the notification, copy one-time codes, and mute
conversations you do not want notified. There are two build variants:

| Variant | When | Notifications show |
| --- | --- | --- |
| Communication Notifications | Signed with a paid Apple developer account | The sender's picture, as in Messages |
| Plain | Free Apple account (`tools/reinstall-ios.sh` falls back to it automatically) | The app icon |

## Both phones in your hand

The Android phone stays a normal phone: you can call and text on it as usual and the iPhone follows. A call
answered on one is ended on the other's screen.
