# DiPlay 0.2.14 — public preview

This update improves Settings, connection recovery, CarPlay video, Siri and vehicle-specific call controls. **Android 9 or newer (API 28+) remains required.** Compatibility still depends on head-unit firmware and hardware.

This release includes the 20 reviewed contributions below, with integration fixes and regression coverage.

[Download website](https://shihabal3amri.github.io/DiPlay/) · [Release and assets](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.14) · [Full source comparison](https://github.com/shihabal3amri/DiPlay/compare/v0.2.13...v0.2.14)

## Settings and everyday use

- Redesigned Settings with an Overview, quick controls, search and categories for Connection, Display, Audio, Navigation, Vehicle, Diagnostics and Advanced. Layouts adapt to small screens, split screen and larger displays, with improved keyboard navigation and Arabic RTL support. Changes that need a new connection show a **Reconnect now** notice. [#369](https://github.com/shihabal3amri/DiPlay/pull/369)
- New **Interface size** control enlarges DiPlay's text and controls on large, low-density screens. Automatic and 100%, 125%, 150% and 200% choices affect DiPlay's own screens; CarPlay's picture keeps its separate sizing. Scaling follows rotation, window resizing and system density changes, and selected languages are restored on affected firmware. [#378](https://github.com/shihabal3amri/DiPlay/pull/378)
- Choose **Last used**, **Wireless** or **USB** as the default for automatic connection. Manual connection buttons keep their selected transport. Tapping the launcher icon during an active session returns to CarPlay; explicitly opening Settings still opens Settings. [#373](https://github.com/shihabal3amri/DiPlay/pull/373), [#376](https://github.com/shihabal3amri/DiPlay/pull/376)
- Schedule CarPlay day/night appearance using the head unit's local time, including overnight schedules. [#365](https://github.com/shihabal3amri/DiPlay/pull/365)
- More reliable custom car-button image selection, including a document browser and a fallback when the head unit has no system document picker. [#364](https://github.com/shihabal3amri/DiPlay/pull/364), [#377](https://github.com/shihabal3amri/DiPlay/pull/377)

## Connection and video

- Narrow USB compatibility repairs handle the captured diagnostic-frame trailer and try a smaller read request after an explicitly rejected large request. Bluetooth receive queues resume after draining, and unusable RFCOMM streams produce clearer diagnostics. These repairs do not establish that every reported connection failure is resolved. [#362](https://github.com/shihabal3amri/DiPlay/pull/362)
- Car-hotspot discovery publishes usable IPv4 and scoped IPv6 addresses from the selected interface. A rendered wireless session that falls back without tunneled iAP2 keeps Bluetooth control available for Now Playing updates; a later successful tunnel completes the normal handoff. [#394](https://github.com/shihabal3amri/DiPlay/pull/394), [#396](https://github.com/shihabal3amri/DiPlay/pull/396)
- Compatible SurfaceView output is selected for app windows without hardware acceleration, with aligned video layout and touch mapping. Picture adjustments are unavailable in this mode; saved values are kept. Android's crypto implementation is used for video decryption when available, with a compatibility fallback. [#362](https://github.com/shihabal3amri/DiPlay/pull/362), [#332](https://github.com/shihabal3amri/DiPlay/pull/332)
- New **Smooth video (experimental)** paces main-screen frames with an adaptive delay. It is **off by default**, can add touch-response delay, and disables picture adjustments while using SurfaceView. Background/return and decoder teardown are also improved. [#367](https://github.com/shihabal3amri/DiPlay/pull/367)
- Rotation and split-screen view areas account for system bars on supported turning head units. Non-square album/video artwork keeps its proportions in the dashboard's square artwork area. [#389](https://github.com/shihabal3amri/DiPlay/pull/389), [#375](https://github.com/shihabal3amri/DiPlay/pull/375)

## Siri, calls and BYD integration

- Assign a steering-wheel key to Siri. It keeps the car's own action without CarPlay or during a call; reserved keys and background operation can require the existing wheel-key service. Siri capture falls back to the communication microphone source when the recognition source is refused by the head unit. [#360](https://github.com/shihabal3amri/DiPlay/pull/360), [#371](https://github.com/shihabal3amri/DiPlay/pull/371)
- Optional audio-focus handling mutes CarPlay media during temporary focus loss and restores it on gain, helping avoid competition with the car's call audio. [#339](https://github.com/shihabal3amri/DiPlay/pull/339)
- Repairs to BYD dashboard call-watcher ownership and wheel call keys, including avoiding the audio-status switch observed to silence calls on a DiLink 3 Han. Vehicle-specific dashboard controls still require compatible firmware and authorized local ADB. [#370](https://github.com/shihabal3amri/DiPlay/pull/370)
- New **Call echo cancellation** and **Clearer call voices** controls are experimental, **off by default**, and apply at the next connection. Software echo cancellation requires matching mono call audio; unsupported or failed processing retains/restores the platform path. Results need testing on each head unit. [#370](https://github.com/shihabal3amri/DiPlay/pull/370)
- The existing experimental cluster-map path recognizes the specifically observed 1280×480 DiLink 3 projection surface. Actual map output on that surface still needs a vehicle retest; this does not add general support for all DiLink 3 displays or HUDs. [#388](https://github.com/shihabal3amri/DiPlay/pull/388)

The [source-build guide](BUILD.md) now identifies the main `mobile` app and explains its build and runtime-authentication requirements. [#374](https://github.com/shihabal3amri/DiPlay/pull/374)

## Updating and reporting problems

Use the official mobile APK for the same package/variant you already installed. An in-place update with the same signing certificate preserves saved settings and pairing records.

If a problem remains, reproduce it on **0.2.14**, including the first connection attempt through the failure, then:

1. Open **Settings → Diagnostics → Save diagnostic report**.
2. Android 10+ normally saves the `.txt` in **Downloads/DiPlay**, named `DiPlay-yyyyMMdd-HHmmss-SSS.txt`. Android 9 opens a document picker to choose the destination. **Choose save location** is also available; if a picker or public storage is unavailable, DiPlay uses app-specific external or private storage and identifies the destination in the confirmation.
3. Select **View report** or **Share** in that confirmation. If no sharing app is installed, the report view lets you select and copy its text. Nothing is uploaded automatically.
4. Attach the readable `.txt` to the matching [existing issue](https://github.com/shihabal3amri/DiPlay/issues), or [create an issue](https://github.com/shihabal3amri/DiPlay/issues/new/choose). Include car/head-unit model, exact firmware/DiLink/Android, iPhone/iOS, connection mode, relevant settings, reproduction steps and failure time. Review the report for personal information before posting; do not include hotspot passwords or private authentication files.

Automated checks and contributor results do not establish acceptance on every vehicle. See [validation](VALIDATION.md), [connection reliability](CONNECTION_RELIABILITY.md) and the [smooth-video guide](SMOOTH_WIRELESS.md) for the evidence and remaining hardware-test limits.

## Contributors

Thanks to [@aloaiza-dev](https://github.com/aloaiza-dev), [@romanchukg-cloud](https://github.com/romanchukg-cloud), [@sa3eedo12](https://github.com/sa3eedo12), [@yeukchan-dev](https://github.com/yeukchan-dev), [@yanwuu](https://github.com/yanwuu), [@HannaFrangi](https://github.com/HannaFrangi), [@wdubaiyu](https://github.com/wdubaiyu) and [@shihabal3amri](https://github.com/shihabal3amri), plus the reporters who supplied logs and vehicle tests. Each merged contribution is linked above.
