## What and why

<!-- One or two sentences. Link the issue: Fixes #123 -->

## How it was tested

<!-- Automatic checks, and on which devices / simulator if any. Test numbers +10000000001…9 only. -->

## Checklist

- [ ] `tools/test-all.sh` passes (or `--quick` / `--no-ios`, saying which)
- [ ] Wire change: `protocol/PROTOCOL.md`, both implementations, `protocol/fixtures/` (and vectors for crypto or framing) updated — or no wire change
- [ ] User-facing text in English **and** Persian
- [ ] No personal data: phone numbers, names, device ids, team ids, keys, tokens or logs from real phones (`tools/check-private.sh`)
- [ ] `CHANGELOG.md` (Unreleased) updated for user-visible changes
- [ ] Docs updated where behaviour changed, incl. the Persian guide, the SDK docs and the examples where they are affected ([CONTRIBUTING.md › Done means everything is updated](https://github.com/amirwmr/tether/blob/main/CONTRIBUTING.md#done-means-everything-is-updated))
- [ ] The iPhone app and the Android client both have the change, or the description says why not
- [ ] No Apple ID, team id, profile, keystore, password or token in the change
