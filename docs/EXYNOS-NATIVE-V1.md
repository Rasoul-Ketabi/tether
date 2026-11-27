# Exynos 7870 native capability test (J7 Core)

This Android-only experimental build extends the previous Root Helper and independent 8/16 kHz Android tests. No iPhone protocol change. Use the existing self-test in a controlled cellular call with a consenting participant and handset microphone On. The native probe is gated to j7velte, UID 0, SDK 30–32 and in-call mode. It may briefly open idle non-modem ALSA descriptors, then immediately close them; it does not configure, start or stream samples. End the call if normal audio is disturbed.

## Native checks
- NDK/JNI compiled native library for ARM32 and other standard Android ABIs; only the J7 Core root helper executes this experiment.
- Actual TinyALSA dynamic library/export availability; native Linux ALSA ioctl fallback independent of private library namespace.
- PCM_INFO substream availability for PCM 2, 0, 7, 3 and 6; modem PCM 2 is never opened. Busy/unknown streams are skipped.
- Nonblocking open and HW_REFINE for idle alternate PCM endpoints at 8/16 kHz, mono/stereo S16_LE. Zero means the constraint was accepted, not that call audio is accessible.
- Read-only current hardware mixer values (AP/CP/SCO routing controls), compared to the supplied mixer_paths_0.xml routes.
- Existing independent capture/injection diagnostics and handset safety fallback remain in place.

## Boundary
The supplied HAL owns the modem PCM and uses separate SCO PCMs for an actual Bluetooth call route. The XML contains call_forwarding and CP/BT paths, but their existence does not show that the installed HAL exposes a software-accessible independent RX/TX stream. The PCM capabilities alone cannot verify that a sample comes from the far end or reaches it. Do not write mixer controls, force a route, kill audio services or change SELinux, policy or kernel blindly. There is no enabled native sample bridge in this build. An actual software bridge requires a confirmed separate capture and injection path (or an independently validated HAL/driver implementation). Never report the native diagnostic rows as successful call audio.

The Android APK is published as a prerelease after successful CI. Existing iOS installation remains compatible.
