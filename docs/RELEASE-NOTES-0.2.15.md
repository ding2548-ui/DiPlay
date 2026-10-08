# DiPlay 0.2.15 — 2026-10-08

Public preview for compatible BYD Android head units. Android 7.1+ (API 25) is now supported by the app; Android 7.1–8.1 still needs vehicle testing. USB and wireless remain available, subject to the head unit's firmware and hardware. Android 9+ keeps its existing connection paths.

## Highlights

- Android 7.1+ compatibility, with guarded newer Android APIs and legacy networking/storage paths. This is not a guarantee that every older head unit works. [#407](https://github.com/shihabal3amri/DiPlay/pull/407)
- A first-launch setup guide detects the DiLink version and explains relevant choices. It can be skipped or reopened from Settings. [#416](https://github.com/shihabal3amri/DiPlay/pull/416)
- Light, Dark and Auto appearance for DiPlay's own interface, plus more compact landscape and portrait layouts. Dark remains the default; CarPlay's appearance is controlled separately. Language and About have dedicated settings destinations. [#420](https://github.com/shihabal3amri/DiPlay/pull/420), [#422](https://github.com/shihabal3amri/DiPlay/pull/422)
- Manual update checks in About find official GitHub releases, download the APK and hand installation to Android. Download validation rejects incomplete files and verifies the published SHA-256 checksum. Android's installation permission flow is supported on both older and newer versions. [#413](https://github.com/shihabal3amri/DiPlay/pull/413)

## Audio, connection and video

- Music playback rebuilds its prebuffer after an underrun. The review also corrected retained PCM accounting so refill does not block a paused AudioTrack. This targets recovery from underruns; it does not establish that all audio cutouts are fixed. [#401](https://github.com/shihabal3amri/DiPlay/pull/401)
- The main-screen decoder receives an operating-rate hint for the requested frame rate, with the existing untuned fallback when the codec rejects it. [#417](https://github.com/shihabal3amri/DiPlay/pull/417)
- Wi-Fi Direct adds **Auto · 5 GHz** and **Auto · 2.4 GHz** choices alongside the existing automatic/channel choices. Availability and frequency verification remain firmware dependent; Android 7.1–9 uses the legacy path. [#432](https://github.com/shihabal3amri/DiPlay/pull/432)
- An optional setting opens DiPlay when the selected iPhone reconnects over Bluetooth. It is off by default and remains subject to Android/firmware background-launch restrictions. Connect-on-open remains a separate choice. [#439](https://github.com/shihabal3amri/DiPlay/pull/439)
- **CarPlay audio over the car's Bluetooth (experimental)** is off by default. It uses the car's Bluetooth audio route instead of DiPlay's normal CarPlay audio path; calls, Siri and navigation behavior need vehicle feedback. [#432](https://github.com/shihabal3amri/DiPlay/pull/432)

## Dashboard and vehicle integration

- Square-canvas turning reports the real physical width of the long display side. [#404](https://github.com/shihabal3amri/DiPlay/pull/404)
- The custom dashboard turn-card placement controls use 1% steps. [#440](https://github.com/shihabal3amri/DiPlay/pull/440)
- Optional small-window navigation-marker positions can follow the foreground app using local ADB, with the usage-access fallback. Auto following is experimental and off by default; switching between small/full layouts reconnects CarPlay. The review fixed map streaming remaining paused after the pause option was disabled. This does not add an independent small-window custom turn-card layout. [#438](https://github.com/shihabal3amri/DiPlay/pull/438)
- An explicit, experimental local-ADB boot-start repair checks permissions for DiPlay's own package. It requires already-authorized ADB and does not override BYD's private auto-start manager or guarantee boot launch on every firmware. [#441](https://github.com/shihabal3amri/DiPlay/pull/441)
- Corrected Ukrainian wording for multi-window and the instrument cluster. [#399](https://github.com/shihabal3amri/DiPlay/pull/399)

## Updating and reporting problems

Install the official APK over the previous public release to preserve settings and pairing records. The package remains `com.shihab.diplay`; release packaging verifies the existing signing certificate. Source builds omit runtime authentication assets by default; see [building from source](BUILD.md).

Reproduce remaining problems on **0.2.15**, then open **Settings → Diagnostics → Save diagnostic report**. Android 10+ normally saves to **Downloads/DiPlay**; Android 7.1–9 uses a document picker. If it is unavailable, follow the save confirmation and use **View report** or **Share**. Review the `.txt` and attach it to a matching [existing issue](https://github.com/shihabal3amri/DiPlay/issues) or [new issue](https://github.com/shihabal3amri/DiPlay/issues/new/choose). Reports are not uploaded automatically.

Include car/head unit, Android/DiLink and full firmware, iPhone/iOS, USB or wireless mode, relevant settings, reproduction steps and failure time.

See [validation](VALIDATION.md) for automated and emulator checks. No new maintainer vehicle test of the complete release is claimed. General audio, calls/Siri and vendor-specific behavior still need current-device evidence. The separate draft echo-alignment work (#421) and Bluetooth pause/call work (#307) are not included in this release.

Thanks to the contributors linked in the 14 pull requests above, and to the users supplying diagnostic reports and vehicle feedback.
