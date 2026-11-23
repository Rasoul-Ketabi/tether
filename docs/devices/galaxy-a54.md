# Device notes: Samsung Galaxy A54 (SM-A546E), the reference test phone

Measured on 2026-11-21 over USB `adb`, with `tools/spike.sh probe`.

| Item | Value |
|---|---|
| Build | Android 17 (SDK 37), One UI, `CP2A.260605.016.A546EXXUNGZJ5`, Exynos 1380 (`s5e8835`) |
| SIMs | 2 loaded (dual SIM) |
| Default dialer / SMS app | `com.samsung.android.dialer` / `com.google.android.apps.messaging` |
| Wireless debugging (`adb_wifi_enabled`) | 1 (on) |

**Shell user (uid 2000) permissions that matter** (`dumpsys package com.android.shell`):
`CALL_AUDIO_INTERCEPTION`, `CAPTURE_AUDIO_OUTPUT`, `CAPTURE_VOICE_COMMUNICATION_OUTPUT`,
`CAPTURE_MEDIA_OUTPUT`, `MODIFY_AUDIO_ROUTING`, `MODIFY_AUDIO_SETTINGS_PRIVILEGED`,
`MODIFY_PHONE_STATE`, `CONTROL_INCALL_EXPERIENCE`, `RECORD_AUDIO`, `WRITE_SECURE_SETTINGS`,
`MANAGE_ROLE_HOLDERS`, `BLUETOOTH_PRIVILEGED`.

**Audio policy** (`dumpsys media.audio_policy`): device ports `Telephony Tx`
(`AUDIO_DEVICE_OUT_TELEPHONY_TX`) and `Telephony Rx` (`AUDIO_DEVICE_IN_TELEPHONY_RX`), and an
output profile `incall_music_uplink` with `AUDIO_OUTPUT_FLAG_INCALL_MUSIC` whose sink is
Telephony Tx. That is the hardware path for injecting audio into the call uplink.

**Helper probe as shell, no call active** (`app_process`, see `android/helper`):

| Method | Result |
|---|---|
| `AudioManager.isPstnCallAudioInterceptable()` | **true** |
| capture `intercept` (`getCallDownlinkExtractionAudioRecord`) | refused: "not available in mode MODE_NORMAL" (needs a live call) |
| capture `voice_downlink` (`AudioSource.VOICE_DOWNLINK`) | opens and records (silence, no call) |
| capture `voice_call`, `voice_uplink` | open and record |
| capture `telephony_rx` (preferred device Telephony Rx) | opens, all zeros |
| inject `intercept` (`getCallUplinkInjectionAudioTrack`) | refused: "not available in mode MODE_NORMAL" (needs a live call) |
| inject `telephony_tx` (AudioTrack, preferred device Telephony) | plays, routed to device type 18 (Telephony) |
| inject `policy_mix` (dynamic AudioPolicy mix → Telephony Tx) | plays, routed to device type 18 |

**Conclusion.** The A54 supports Android's system call-audio interception API, which is
exactly downlink extraction + uplink injection for PSTN calls, and the shell user holds its
permission. It is the first method the helper tries; `voice_downlink` / `telephony_tx` /
`policy_mix` are fallbacks. What is still unproven is the audio content during a real call:
no call was placed (no test number was available). Run `tools/spike.sh selftest` during a
call, or the in-app self-test, which plays a 1 kHz tone through each inject method while
recording `VOICE_UPLINK` and detects it with a Goertzel filter.

Samsung-specific workaround needed: the fake `ActivityThread` needs a
`ConfigurationController`, otherwise `CompatSandbox` crashes while creating the system context.

## Call-audio measurements (2026-11-23, test line)

The app's self-managed test line runs the interception API on its **VoIP path** (audio mode IN_COMMUNICATION: the
record and track are remote-submix policy mixes), not the PSTN path a real call uses. Its capture comes in bursts
(backlog up to 160 ms, average 40-55 ms), the inject track reports 256 ms of latency, and `VOICE_UPLINK` does not
hear the injected tone (the self-test's inject checks fail there). So loopback figures for capture and inject do not
carry over to real calls; codec, link and Telecom figures do:

| Figure | Value |
| --- | --- |
| Ring → client (Telecom to the hub) | 160-250 ms |
| End → ended | 13-19 ms |
| Opus encode / decode, 20 ms frame (Concentus, complexity 3) | ~3 ms (~0.9 ms once the CPU has ramped up) / ~0.5 ms |
| Inject buffer cap 40 ms (default) / 20 ms | 0 underruns / 60-70 underruns and ~95 dropped uplink frames per 5 s call: keep 40 |
| LE PHY on the owner's link | 2M |
| Voice-call stream mute as shell (`HelperMain silence on/off` with `TETHER_SILENCE=other`) | works (index 0, restored) |
