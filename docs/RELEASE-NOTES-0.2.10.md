# DiPlay 0.2.10 — 3 October 2026

Public preview for compatible Android 9 and newer head units. This release adds targeted connection improvements, CarPlay song artwork, optional video while in P, and further BYD integration controls.

## Release highlights

- CarPlay song title, artist, album, playback position and album artwork are available through Android's media session for compatible launchers and media displays.
- Wireless startup selects an available AirPlay port when a factory service already occupies port 7000. The selected port is advertised to the iPhone.
- Wi-Fi Direct startup handles an unknown reported security type and retries busy channels, with a bounded fallback from 5 GHz to 2.4 GHz.
- Wired startup handles bounded padding around recognized USBMUX control replies while preserving fragmented and combined frames, and accepts unfinished Manual hotspot settings when using USB.
- CarPlay calls can use Android's supported echo cancellation and noise suppression. Availability and effectiveness depend on the head unit.
- BYD battery reporting selects CAN or CANFD commands from the vehicle's reported protocol.
- The home-screen mirror of the dashboard map has a show/hide setting; the instrument-cluster map remains available.
- Optional video uses a new player with seeking and ten-second skip controls, including supported media URLs loaded through the iPhone.
- More Ukrainian translations cover the map card, dashboard song, launcher map sharing, permission help and navigation widget.
- Saved reports add Bluetooth, USB, reconnect and boot-stage diagnostics, plus microphone startup/failure details and periodic capture, encoding and send counters for investigating calls and Siri.

## Changes

### Connections

[PR #114](https://github.com/shihabal3amri/DiPlay/pull/114) and its release correction harden USBMUX parsing around padded version replies and payload-free TCP SYN-ACK control replies. A narrow recovery rule accepts one four-byte padding boundary only after a recognized control reply and only when a complete, bounded, valid next TCP header confirms the boundary. Ordinary fragmented and combined frames retain their data; whole USB transfers and media payloads are not discarded or scanned. This targets the captured iOS 27 framing pattern. End-to-end wired media and reconnect operation on the non-BYD tablet in issue #100 still need device confirmation.

[PR #130](https://github.com/shihabal3amri/DiPlay/pull/130) applies Manual hotspot validation to wireless sessions. USB CarPlay can start even when the saved wireless hotspot name or password is unfinished. This addresses the reported startup exception in issue #124; wireless sessions still need valid hotspot settings.

[PR #121](https://github.com/shihabal3amri/DiPlay/pull/121) handles devices that report an unknown Wi-Fi Direct security type, including contributor-reported HyperOS cases. Startup retries busy channels and can fall back to 2.4 GHz when the attempted 5 GHz group is unavailable. The security-report compatibility change does not remove hotspot encryption. Wi-Fi Direct requires Android 10 or newer.

[PR #143](https://github.com/shihabal3amri/DiPlay/pull/143) tries the preferred AirPlay port, then ports 7001–7010, and finally a system-assigned free port. Wireless Bonjour and both wired and wireless iAP2 advertise the bound port. Review corrections close sockets if binding setup or fallback notification fails. This addresses a factory-listener port conflict; it does not change a factory daemon's USB ownership.

### Music and calls

[PR #82](https://github.com/shihabal3amri/DiPlay/pull/82) publishes CarPlay song metadata and downscaled album artwork through Android's media session. Compatible launchers can show the source app, duration and playback position alongside the title, artist and album. Review fixes bound artwork decoding and publication queues, coalesce repeated transfer IDs, and discard stale work when sessions change.

[PR #116](https://github.com/shihabal3amri/DiPlay/pull/116) enables communication mode and available platform echo cancellation and noise suppression for telephone capture. Effects are released and the previous audio mode is restored after the call. These effects depend on Android and the head unit; there is no software echo-cancellation fallback. Siri retains its existing speech-recognition capture path.

### BYD battery and map controls

[PR #123](https://github.com/shihabal3amri/DiPlay/pull/123) detects the vehicle's reported CAN/CANFD protocol for battery reads through the existing network ADB connection. Supported CAN vehicles can report charge percentage, electric range and charging state; unverified energy and capacity values are omitted. Unknown protocols suppress readings, and failed samples clear stale values. Availability depends on the vehicle and its firmware.

[PR #133](https://github.com/shihabal3amri/DiPlay/pull/133) adds a saved show/hide setting for the home-screen mirror of the dashboard map, enabled by default. Hiding that mirror does not disable the instrument-cluster map. The existing map-stream and overlay requirements still apply.

### Optional video while in P

[PR #129](https://github.com/shihabal3amri/DiPlay/pull/129) uses Media3 ExoPlayer, opens the player when the iPhone starts playback, and adds a seek bar plus ten-second back and forward controls. Supported Safari and application media URLs can be loaded through the iPhone. Protected FairPlay video remains unsupported.

The feature is optional, requires network ADB and a valid gear reading, and permits video only while the gear is **P**. Leaving P or losing a valid gear reading closes playback. Review fixes reject Android local-resource URLs, recheck and bound redirects, and clean up failed source opens so playback can retry.

### Ukrainian translations

[PR #128](https://github.com/shihabal3amri/DiPlay/pull/128) translates twenty additional strings for the home-screen map card, permission help, dashboard song, launcher map sharing and navigation widget. The release also translates the new map-mirror setting into Ukrainian.

### Microphone diagnostics

Saved reports now include microphone source, codec, routed-device type, startup/failure stages and aggregate capture, read, encoded-frame and UDP-send counters. Statistics are reported every five seconds while capture is processing, with a final summary when capture ends. These diagnostic lines do not record audio, packet contents, keys, endpoint addresses or device names. Reporting failures do not stop microphone capture. New microphone and connection diagnostic writes run on a background worker with at most 64 pending entries; overload drops the oldest pending entry. These logs are best-effort and may be incomplete if the process exits before they are written.

This adds evidence for investigating calls, voice messages and Siri; it is not a microphone-routing or speech-recognition correction. Recorder sources, codecs, frame buffers, thread priority and retry behavior retain their existing configuration.

### Connection and boot diagnostics

Saved reports add anonymous connection-attempt and transport-run numbers with stage and phase. Bluetooth summaries report adapter enabled state, bond state and cached iAP2 service presence/count, without Bluetooth names, addresses or UUID values and without initiating service discovery or adding scan permission.

USB summaries describe configuration readiness, reuse versus transition decisions, read/write calls, timeouts, failures and maximum durations. Aggregates are bounded and reported at most once per ten seconds plus teardown, alongside initial-event/failure details. Teardown diagnostics record resource durations, executor termination and restart-wait completion.

A boot snapshot records the latest actual `BOOT_COMPLETED` receipt, the saved boot-open flag at that time and the activity-request result or failure class. Current boot-open and connect-on-open settings remain distinct. An accepted activity request does not prove the head unit displayed the app. These additions help investigate unresolved reports without changing transport timing, orientation/reset behavior or supported devices.

## Validation and downloads

The combined source checks passed **528 unit tests**, with zero failures/errors and one explicit macOS wildcard-binding skip. Mobile, Home and map-host lint and debug builds passed; mobile release lint and the signed release build passed. Tests cover the review fixes for video URLs/redirects, artwork queue bounds and stale sessions, USB frame preservation, port/socket ownership, call audio-mode restoration, and diagnostic privacy/queue bounds. Lint warnings remain.

The APK is `com.shihab.diplay`, version code **29**, signed with the same certificate as 0.2.9. The release includes `DiPlay-0.2.10.apk`, corresponding `DiPlay-0.2.10-source.zip` and `SHA256SUMS.txt`. Runtime authentication assets are explicitly selected for the APK; Android signing keys and accessory identities are excluded from Git and the source archive.

No new on-car validation was performed for this release. Previous hardware tests and automated regression coverage do not establish compatibility on every head unit. See [validation details](VALIDATION.md).

## Fresh reports needed for unresolved issues

Several reports remain under investigation, including:

- Bluetooth/wireless startup and connection timeouts (#98, #134, #137, #142).
- Connection drops and USB reconnects after rotation (#119, #141).
- Wi-Fi Direct audio stutter and packet loss (#131).
- Microphone, phone-call, voice-message and Siri input problems (#103, #115, #117, #138).
- Automatic startup problems (#118, #127) and CarPlay display-size behavior (#92).

If your problem still occurs, we need a **fresh reproduction and diagnostic report from 0.2.10** to continue the investigation:

1. Update to 0.2.10 and reproduce the problem, keeping the report from the first connection attempt through the failure. For an auto-start problem, reboot the head unit, then open DiPlay manually to export the report.
2. Open **Settings → Diagnostics → Save diagnostic report** after the failure.
3. Attach the exported `.txt` report to your existing issue. Include the head-unit model, Android/firmware version, iPhone/iOS version, connection mode, the action that triggered the failure and its approximate time so we can match it to the logs.

The new diagnostics help identify the failing stage. Earlier reports do not contain all of these new fields, so please share a new report even if you already supplied logs from an older version.

## Compatibility and remaining reports

Android 9 remains the minimum supported Android version. This release does not add Android 7/8 support or establish wireless support for HarmonyOS and other unverified Bluetooth firmware. Existing Wi-Fi Direct packet-loss, device-specific microphone and reconnect reports still require device logs and hardware investigation. Targeted fixes are not a claim that every connection issue is resolved.

Update the existing signed DiPlay installation to retain settings and pairing records. Compatible-launcher media display, Android call effects, BYD battery reads and optional video still depend on the head unit, firmware and relevant permissions.

Thanks to **aloaiza-dev**, **kuishou68**, **lpcheng1208**, **liuqianhe**, **romanchukg-cloud**, **serein-morii** and **sa3eedo12** for these contributions.
