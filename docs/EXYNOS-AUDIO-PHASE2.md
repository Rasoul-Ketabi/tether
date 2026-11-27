# Exynos 7870 audio diagnostics — phase 2

The Galaxy J7 Core (j7velte), Android 11, showed UID 0 with the experimental Root helper, but capture and injection remained unavailable. The observed modem PCM 2 was already opened by the audio HAL at 8 kHz. A PCM playback/capture descriptor does not establish a safe independent userspace audio path.

This build separates failure to capture VOICE_UPLINK from failure to open the injection route. When the monitor fails, it attempts the output route independently without sending a test tone; a route opening is not a verified uplink. It also checks both directions at 8 kHz on a rooted Exynos 7870 device running Android 11–12, without changing the live codec. The native diagnostic rows report ALSA procfs state, selected PCM configuration fields, policy declarations, and TinyALSA file presence. These are diagnostics, not passing audio tests.

If either live audio path fails, the helper restores local handset audio and avoids applying its mic mute plan. The app restores its saved earpiece volume and clears its Telecom mute. A call-audio self-test row requires both paths. No SELinux or vendor configuration changes, no direct opening of busy modem PCM nodes, and no new raw PCM streaming are performed.

## Test procedure

1. Install the Android host APK; a differently signed APK may require uninstalling the previous build.
2. Select the Magisk Root helper and verify actual UID 0. Keep the handset microphone On for the experiment.
3. Establish a normal cellular call with a consenting participant and run the iPhone's existing Link self-test. Ask them to speak during capture tests.
4. Save the self-test screen (including all native and 8k rows). If a route opens but is unverified, do not treat it as successful audio.
5. Verify an ordinary call on the handset still works after ending the test.

The next decision depends on the diagnostic evidence. If the policy exposes no usable TX and no capture route, a device-specific HAL/driver solution or a different audio transport must be evaluated rather than force-opening PCM 2.
