# DiPlay 0.2.11 — 3 October 2026

Public preview for compatible BYD Android 9 and newer head units. This update adds a preferred Wi-Fi Direct channel, a movable dashboard turn card, configurable settings gestures and optional legacy vehicle-data detection. It also includes connection, media and privacy corrections reviewed since [0.2.10](RELEASE-NOTES-0.2.10.md).

## Release highlights

- Choose **Auto** or a preferred Wi-Fi Direct channel for the next connection. Auto remains the default; manually selected channels depend on the head unit and regional radio support.
- Place a custom turn card over the dashboard map, with size choices and position adjustments in 2% steps. Unknown maneuvers do not become invented straight-ahead arrows, and expired guidance clears.
- Open settings with a two-, three- or four-finger downward swipe. Three fingers remains the default.
- Keep the default DiLink 5.0 vehicle-data path, or explicitly select read-only detection for a compatible legacy head unit. Saved fields are accepted before their readings become runtime data.
- Optionally start the car's existing hotspot automatically on supported firmware. The setting is off by default and verifies permissions for DiPlay's own package before enabling.
- Use D-pad/remote controls on Android TV and non-touch devices while preserving ordinary head-unit touch and knob behavior.
- Retain the artist when an incremental song update omits it, and avoid republishing unchanged song metadata/artwork on every position update.
- Correct an Android 9 audio API call, use settled geometry and normal startup checks after reconnect, and require the wired VPN to be scoped to DiPlay.
- Export additional bounded wireless, media, theme and own-app process-exit diagnostics. These additions do not record audio, video or packet payloads, and reports are not uploaded automatically.

## Wi-Fi Direct channel and startup

[PR #175](https://github.com/shihabal3amri/DiPlay/pull/175) adds **Settings → Connection setup → Wi-Fi Direct → Preferred channel**. Choose Auto, channels 1–11 at 2.4 GHz, or channels 36, 40, 44, 48, 149, 153, 157, 161 or 165 at 5 GHz. Save applies to the next Wi-Fi Direct connection. It does not change an active connection or the car's built-in hotspot.

Auto preserves automatic selection and bounded startup recovery. A manual selection must be supported by the radio and its regional configuration. If the head unit rejects it or creates a group on a different channel, DiPlay reports an error rather than silently selecting another channel. Choose Auto or another supported channel and reconnect. This is a troubleshooting control, **not a confirmed resolution of Wi-Fi Direct audio stutter or packet loss**.

The update also adds a narrow Android 10 compatibility recovery for [issue #15](https://github.com/shihabal3amri/DiPlay/issues/15). Only the exact reported missing `WifiP2pConfig.getNetworkName()` signature on API 29, originating in the framework Builder before group creation, permits one guarded system-default attempt in Auto mode. DiPlay then reads the group's actual generated credentials. Permission failures, timeouts, other linkage errors and explicit channel selections do not enter this fallback. Earlier PR #73 already removed the app-side getter; the old report does not prove the vendor Builder is broken. A current-device retest remains necessary.

Existing foreign-group checks and bounded startup deadlines remain. The recovery does not toggle global Wi-Fi, delete persistent groups or repeatedly alternate between configurations.

## Dashboard turn card and settings gesture

[PR #155](https://github.com/shihabal3amri/DiPlay/pull/155) adds **Map with custom turn card** to the dashboard display choices. DiPlay draws this card over the map, with small/medium/large sizes and horizontal/vertical adjustments in 2% steps. These changes apply without reconnecting. The iPhone's original glass turn card is part of its video and cannot be moved independently.

Review corrections leave the arrow blank for unknown maneuvers, preserve valid text, and clear expired guidance even when no new phone packet arrives. Check placement on your vehicle so the card does not obscure factory driving information. Supported dashboard projection firmware is still required.

[PR #156](https://github.com/shihabal3amri/DiPlay/pull/156) makes the settings swipe use two, three or four fingers, selected under **Settings → CarPlay controls** or the in-CarPlay menu. The default remains three. Choose a count the head unit does not reserve for its own panel.

## Vehicle-data modes, location and parked video

[PR #158](https://github.com/shihabal3amri/DiPlay/pull/158) adds **Settings → Location → Advanced vehicle data**. Default mode remains the default and uses the existing DiLink 5.0 path. Selecting legacy mode runs a fixed, bounded, read-only probe of candidate speed, gear, battery and range fields on compatible firmware. It makes no vehicle writes, enables no ADB service and installs no persistent helper. Network ADB must already be available and authorized.

Only confirmed fields expose their corresponding battery, wheel-speed and parked-video controls. Complete saved snapshots survive app restarts and updates. Transport failure keeps the saved snapshot; two complete checks that cannot read saved fields can trigger one new probe. A candidate losing a previously confirmed field is held for explicit replacement. Held or rejected candidates do not publish battery readings. Destroyed or superseded user operations cannot save stale results; intentional paused/background acceptance remains supported. Accepted battery publication avoids waiting on the shell-reader lock, and an older in-flight read cannot overwrite the accepted sample. These are source and regression-test corrections, not proof that every legacy vehicle is supported.

The PR also keeps location reporting off until precise location is granted. Changing location reporting reconnects an active session because the capability is advertised during identification. Vehicle controls have one owner in Advanced vehicle data; the separate BYD ADB card controls hotspot startup. Hotspot authorization and user vehicle checks/probes wait for one another, and automatic saved-field validation resumes afterward. See [BYD navigation and vehicle-data details](BYD_NAVIGATION.md).

[PR #157](https://github.com/shihabal3amri/DiPlay/pull/157) assigns wireless location and vehicle data to the runtime Wi-Fi iAP2 link rather than the short Bluetooth bootstrap. Wired data continues on its USB iAP2 link. Parked-video availability waits for negotiated SETUP and event-channel readiness before it is sent. Video remains optional and permitted only with a valid P-gear reading; leaving P or losing the reading closes playback. This does not establish that an iPhone uses wheel speed for tunnel navigation.

## Optional automatic car hotspot

[PR #164](https://github.com/shihabal3amri/DiPlay/pull/164), reconciled with the vehicle settings above in [PR #173](https://github.com/shihabal3amri/DiPlay/pull/173), adds automatic startup of the car's existing hotspot on supported BYD firmware. It is **off by default** and applies to built-in-hotspot mode. Explicitly enabling it requests missing permissions through the existing authorized local ADB path and verifies actual own-package permissions before saving the setting. Failed authorization leaves it off.

WRITE_SETTINGS and, when the separate boot-open option needs it, overlay authorization are limited to DiPlay's own installed package. The feature preserves the hotspot's name and password and does not stop it on disconnect or when disabled. Boot-open and connect-on-open remain separate choices. No ADB credentials or runtime accessory keys are added to GitHub build workflows. See [connection setup](CONNECTION_SETUP.md).

## TV controls, music and compatibility fixes

[PR #146](https://github.com/shihabal3amri/DiPlay/pull/146) adds Android TV/remote navigation on leanback or non-touch devices. Direction keys use the existing relative wheel, with select/back down/up events. Normal touchscreen Back behavior and the knob's absolute X/Y semantics are preserved. Its build workflow runs source-only checks with read-only repository access; it does not restore or distribute runtime authentication credentials.

[PR #161](https://github.com/shihabal3amri/DiPlay/pull/161) retains an existing artist when the iPhone omits that field in an incremental title update; explicit clears still clear it. [PR #162](https://github.com/shihabal3amri/DiPlay/pull/162) publishes media-session metadata/artwork only when those values change. Position and play state continue updating. This removes repeated bitmap publication into Android's system process; long-session behavior on other head units still needs device testing.

An Android 9 correction uses configured/effective audio attributes instead of calling an AudioTrack getter introduced in Android 10. This addresses a concrete API compatibility problem consistent with [issue #163](https://github.com/shihabal3amri/DiPlay/issues/163), without proving that report has no other cause. Failed codec configure/start attempts now release the created codec while preserving the original failure. Recorder source, audio quality and negotiated media formats retain their existing settings.

The source correction from closed PR #144 reconnects using the latest settled display size and the normal readiness checks after teardown. Its credential-extraction workflow is excluded. The scoped correction from [PR #168](https://github.com/shihabal3amri/DiPlay/pull/168) requires the wired VPN to allow only DiPlay's runtime package before establishing the tunnel. Scope failure aborts; it never falls back to an unrestricted car-wide VPN. This does not add an internet VPN service or alter other apps' network routing intentionally.

## Diagnostics and fresh reports

Additional local records include wireless startup timing, bounded interface/UDP error deltas, RTP sequence/read summaries, audio formats and decoder/failure stages, theme callback/poll/heartbeat observations and dark-mode command outcomes. On Android 11+, a user-requested export may include up to three recent own-app numeric process-exit records. No process descriptions, traces or state blobs are read. Android 9/10 skip that platform query. Diagnostic callback/read failures do not replace the original media or connection failure.

The new records omit audio/video/packet payloads, credentials, Bluetooth names/addresses and raw exception messages. They add no diagnostic permissions or automatic uploads. Reports still contain app/device and firmware context; review the exported text before sharing it publicly. Bounded best-effort logging can lose entries if the app exits before they are written.

If a problem remains, please provide a **fresh reproduction and report from 0.2.11**, even if you already sent logs from an older version:

1. Update from the [official website](https://shihabal3amri.github.io/DiPlay/) and reproduce from the first connection attempt through the failure. For an auto-start problem, reboot, then open DiPlay manually to export.
2. Open **Settings → Diagnostics → Save diagnostic report**. Android 10+ saves under **Downloads/DiPlay**; Android 9 uses the document picker.
3. Review the `.txt` file and attach it to your existing [GitHub issue](https://github.com/shihabal3amri/DiPlay/issues). Include vehicle/head-unit model, Android/DiLink/firmware, iPhone/iOS, connection mode, steps and approximate failure time. Never include your hotspot password.

Qin Plus startup reports, iOS 15.8.8 wireless startup ([#151](https://github.com/shihabal3amri/DiPlay/issues/151)), Wi-Fi Direct stutter ([#131](https://github.com/shihabal3amri/DiPlay/issues/131)), Siri/microphone quality, iOS 15 connection and day/night firmware reports still need current-device evidence. Added diagnostics do not establish those issues as resolved.

## Validation, downloads and limits

The source baseline is merged main through PR #175. Final **0.2.11** validation passed: **773 unit tests**, with one additional host-specific skip and no failures/errors; all three source-only debug builds and debug lint checks; and mobile release lint and the signed release build. The production APK is not debuggable, its signing certificate matches 0.2.10, and its runtime authentication assets match both the selected local inputs and 0.2.10 byte for byte. Public-tree and source-archive preflight scans pass. The corresponding release source archive and SHA-256 checksums are supplied with the release. See [VALIDATION.md](VALIDATION.md) for measured results and remaining device checks.

The intended package is `com.shihab.diplay`, version code **30**, minimum SDK **28**. Release assets are `DiPlay-0.2.11.apk`, `DiPlay-0.2.11-source.zip` and `SHA256SUMS.txt`. The APK deliberately uses the existing experimental accessory identity described in [BUILD.md](BUILD.md) and the notices. It is **not Apple-certified**; extractability, future iOS acceptance and suitability for general distribution remain unresolved. Runtime accessory identities and Android signing keys remain excluded from Git and the source archive. Source/CI builds do not provision an identity by default.

No fresh on-car validation is claimed for 0.2.11. Android 9 remains supported; Wi-Fi Direct requires Android 10+. Android 7/8 and non-BYD brand-specific issues remain outside the support scope. Supported firmware, permissions and field readings are still required for optional BYD features. Hardware checks remain necessary for TV/knob inputs, turn-card placement, legacy modes and ADB denial/loss, hotspot startup, wired networking, channel selection, reconnects and long media sessions.

Thanks to **kiwizu3**, **serein-morii**, **liqimore**, **Hanxu4131**, **romanchukg-cloud**, **lpcheng1208** and **yanwuu** for the reviewed contributions.
