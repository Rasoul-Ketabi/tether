# Contributing

Thanks for helping. The most useful contributions right now:

- **Compatibility reports**: which Android phone and version, which iPhone or iPad, what works and what does not
  (especially call audio: run *Run call-audio self-test* during a call and include its result). Open an issue.
- Bug reports with the link logs (see [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md#what-to-include-in-a-report)).
- Fixes and features through pull requests.

## Before you open a pull request

1. Build and test both sides ([docs/BUILD.md](docs/BUILD.md)):
   ```bash
   (cd ios/TetherCore && ./test.sh)
   (cd android && ./gradlew :app:testDebugUnitTest :app:assembleRelease)
   (cd ios && xcodegen generate && xcodebuild -project Tether.xcodeproj -scheme Tether -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build)
   ```
2. A change to the wire format updates `protocol/PROTOCOL.md`, both implementations and, for crypto or framing,
   the vectors (`swift run tether-vectors` in `ios/TetherCore`, then copy `vectors.json` to `protocol/` and to the
   TetherCore tests).
3. A new message type: a `T` constant in `android/app/.../core/Messages.kt`, its handler in `Hub.onMessage`, the
   `MsgType` and body in `TetherCore/Messages.swift`, handling in `AppModel.received`, a row in PROTOCOL.md §5, and
   tests on both sides.
4. Keep the link free of Bluetooth security (`LinkSecurityTest` fails otherwise) and test hooks dry (they may only
   touch the self-managed test line and the numbers `+10000000001…9`).
5. Never commit personal data: phone numbers, device ids, team ids, keys or logs from your phones. Local values go
   in the git-ignored files listed in BUILD.md.
6. User-facing text comes in English and Persian (`ios/Tether/Resources/*.lproj`, `android/app/src/main/res/values*`).

## Testing on devices without real calls

`tools/dbg.sh loopback_call` runs complete calls on the Android app's self-managed test line with an in-process
client (ring, answer, Opus both ways, keypad, hang up) and prints latencies. `tools/dbg.sh only none` keeps test
traffic away from a linked iPhone; `only off` restores it. Never test with real calls or texts to real numbers.

## Style

Kotlin and Swift as in the surrounding code; comments explain why, not what. Commit messages: one line saying what
changed and why.
