# CI builds for Android and iOS

This fork includes a GitHub Actions workflow in `.github/workflows/build-apps.yml`.

## Running the workflow

1. Open **Actions** on this repository.
2. Choose **Build Android and iOS** and click **Run workflow** after the workflow has been merged into `main`.
3. Once a run completes successfully, open it and download the artifacts:
   - **tether-android-apks** contains both APKs and SHA-256 checksums.
   - **tether-ios-unsigned** contains an **unsigned**, non-installable IPA package and its checksum.

Pull requests and pushes to `main` also run both jobs automatically.

## Android installation

The included release APK is signed with the temporary Gradle debug key **generated on the runner** unless you
configure a persistent signing identity separately. It can be used for initial testing but **do not rely on it
for updates**: GitHub runners may generate different keys on later runs, and Android will reject updating an
installed app with a different signing key. The debug APK is similarly for disposable testing.

To install one APK on a device with ADB enabled:

```bash
adb install path/to/app-debug.apk
```

Do not expose Android signing keys to untrusted pull-request jobs. For long-term personal builds, add a separate
manual-only signed release workflow using protected repository secrets and environment approval, or sign locally
with a persistent keystore. Keep the key backed up securely.

## iOS installation

The CI IPA is **not signed and cannot be installed on a real iPhone**. The workflow's purpose is to check that
Swift tests and compilation succeed without requiring a developer account or exposing signing credentials.

For a personal iPhone installation, use Xcode on a Mac with your Apple account and the steps in
[`docs/BUILD.md`](BUILD.md), including a unique bundle identifier and a local `Local.xcconfig`.
GitHub-hosted signing with a paid Apple Developer team can be set up separately with protected
certificates, provisioning profiles, and secrets. Do not commit signing identities to the repository.

## Troubleshooting

A failed workflow does **not** mean an APK/IPA was produced. Check the failing job's full logs first.
The pipeline cannot verify Android call-audio interception or Bluetooth/CallKit behavior; those require
physical phones and a supported Android build.

**Security:** These builds do not remove the risks documented in the source security audit, including the
unauthenticated Share VPN proxy. Do not use sensitive data until the app and dependencies have been validated.
