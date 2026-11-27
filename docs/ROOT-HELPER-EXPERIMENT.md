# Experimental Magisk Root audio helper

The default remains the upstream ADB/Shell helper (UID 2000). On an already rooted test device, select the experimental Root helper in the Android Audio card, confirm the warning, and grant the Magisk prompt. The displayed actual UID must be 0; UID 2000 is not a successful Root launch. A denied or failed Root launch is visible and does not silently fall back to ADB. Retry from the Audio card or explicitly switch back to ADB. End all calls before changing mode. Existing onboarding permissions still apply.

Test with a consenting participant on a real cellular call: verify both directions, run the call-audio self-test, and compare the results with ADB mode. Root access does not add a missing audio HAL route; the Exynos 7870 native backend is not implemented here. Do not change SELinux, system audio policy, or the kernel for this test.

The Root helper reuses mutual challenge-response on loopback, reads its launch token via stdin, verifies the selected UID via HELLO, and exits after approximately 45 seconds without an authenticated session. It does not write privileged files in the shell-writable temporary directory. Do not share helper command lines or authentication tokens in reports. This is an experimental path requiring physical-device validation.
