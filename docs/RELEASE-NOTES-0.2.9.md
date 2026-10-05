# DiPlay 0.2.9 — 2 October 2026

Public preview for compatible BYD Android head units. This release includes the contributions from @lpcheng1208 and @romanchukg-cloud, the review corrections, and the floating-map fixes from vehicle testing.

## Release highlights

- CarPlay now follows BYD head-unit day/night changes while it is on screen, including firmware that does not reliably send Android configuration callbacks.
- Media and navigation audio stream selection supports 0–20 again. Older saved navigation selections are preserved when no new selection exists. Use 0 for automatic routing; vendor-specific outputs depend on the head unit.
- CarPlay stays connected when the surround-view camera temporarily resizes its window during an existing full-screen session. Video keeps its proportions, and touch input follows the visible picture. If CarPlay connects while the camera window is already narrow, closing the camera triggers one reconnect to restore the full-screen canvas.
- Ukrainian is now available in the app, Android's app-language settings, and the website.
- An optional dashboard music card shows the CarPlay song title, artist, and play/pause state on supported BYD head units. It uses the existing network ADB connection.
- A CarPlay navigation widget shows the next turn, road, distance, arrival information, and song in launchers that host standard Android widgets.
- An optional floating map card brings the dashboard map to the centre screen. Drag to move it, pinch to resize it, or tap to open CarPlay. The dashboard keeps its map.
- Compatible launchers can embed the live dashboard map on Android 11 and newer. Sharing is off by default, and disabling it closes existing shared map views.
- Developers get a minimal map-host sample and the DiPlay Home sample launcher, which combines the live map, Android widgets, a clock, and an app list.
- GPS reports leave the course empty when the direction is unknown, instead of claiming the car points north.

Thanks to @lpcheng1208 and @romanchukg-cloud for these contributions.

The dashboard song option requires network ADB. The floating map card requires permission to draw over other apps; Usage Access keeps it on home screens, and without that permission it can appear over other apps. The navigation widget needs a launcher that accepts standard Android widgets; BYD's built-in home does not accept arbitrary widgets. The map card and launcher embedding use the instrument-cluster map stream, so **CarPlay map on instrument cluster** must be enabled. The sample apps are separate developer examples.

## Merged changes

### BYD day and night appearance

[PR #71](https://github.com/shihabal3amri/DiPlay/pull/71) checks Android's resource configuration every two seconds while the host activity is visible, and when it regains window focus. This handles BYD firmware that changes its resources without reliably calling `onConfigurationChanged`. Explicit day/night changes are sent to the iPhone; an undefined night-mode value keeps the previous state.

### Extended audio stream selection and saved navigation settings

[PR #88](https://github.com/shihabal3amri/DiPlay/pull/88) restores the 0–20 selection range across media and navigation settings, persistence, and preview. The new navigation setting inherits the older saved `navigation_stream_type` value only when the new setting is absent. Explicit new selections, including 0, take precedence; fresh installs default to automatic routing.

Nonzero preview selections use the same legacy `AudioTrack` constructor as playback. A device that rejects the selected stream reports an unavailable preview. English and Simplified Chinese descriptions explain that vendor-specific outputs require head-unit support. Playback fallback and audio focus behavior are unchanged.

### CarPlay continuity during surround view

[PR #89](https://github.com/shihabal3amri/DiPlay/pull/89) preserves the existing CarPlay session and negotiated video canvas when a camera window shrinks the host view and then returns it to its original size. Video fits the available window without distortion. Touch coordinates follow the visible content, and touch sequences starting in the surrounding bars are ignored. Background-session adoption preserves the display state. Actual display rotation, explicit system-bar layout changes, and connection failures retain their reconnect paths.

The startup-window correction in [commit 7eb4a3f](https://github.com/shihabal3amri/DiPlay/commit/7eb4a3ffc6860b5a24c9a2f7e231fa330814b4d7) handles connecting or reconnecting while the camera window is already narrow. When the window later grows beyond its startup dimensions, CarPlay reconnects once to negotiate a canvas for the larger window. The comparison uses the original window dimensions rather than the scaled video resolution, so resolution scaling does not cause unnecessary reconnects during normal camera open/close cycles.

### Ukrainian language support

[PR #93](https://github.com/shihabal3amri/DiPlay/pull/93) adds Ukrainian to the app language picker, Android per-app language settings, shared connection messages, and the website. Display diagnostic safe-area text now uses one translatable string with matching placeholders. The Ukrainian audio description reflects the 1–20 stream range introduced by #88. Strings introduced by the new song, widget, and map features currently fall back to English in Ukrainian.

### CarPlay song on the dashboard

[PR #101](https://github.com/shihabal3amri/DiPlay/pull/101) adds an optional, default-off dashboard song display using the existing network ADB connection. It follows song title, artist, and playback status from CarPlay, uses BYD's Other music source, limits text without splitting characters, and stops the card when the session ends or the setting is disabled. Explicitly empty or blank song titles clear the previous cached song and stop the card.

### CarPlay navigation widget

[PR #106](https://github.com/shihabal3amri/DiPlay/pull/106) adds a resizable standard Android widget with turn arrows, distance, road, arrival time, remaining time and distance, and the song. A tap opens CarPlay, or DiPlay when disconnected. Visible guidance is refreshed at most once a second, including when no new metadata arrives, so stale routes and persistently empty maneuver lists expire. Session end and cleared song metadata also clear the widget state.

### Floating dashboard map on the centre screen

[PR #105](https://github.com/shihabal3amri/DiPlay/pull/105) adds an optional map card while DiPlay is in the background. The card mirrors the dashboard stream through a separate decoder, remembers its size and position, and supports dragging, pinch resizing, and tapping to open CarPlay. Permission to draw over other apps is required; Usage Access limits it to home screens. Without Usage Access, it can appear over other apps. Integrating the host startup callbacks preserves both day/night polling and overlay setup, and background-session adoption retains the display state from #89.

Follow-up from car testing on 2 October: small pinches did not resize the card. The DiLink 5.1 head unit configures Android's scaling detector with a 32 mm minimum finger separation. The fix measures two-finger movement directly after normal touch slop. It waits for settled contact coordinates, limits sensitivity when fingers are close together, and updates its scale reference continuously so reversing at the minimum or maximum responds immediately. It preserves size limits, aspect ratio, position, and tap/drag behavior. Ten gesture tests cover these cases, including enlarging a reopened minimum-sized card; regression tests reproduced both the original ignored pinch and the subsequent contact jump and size-limit lock. The follow-up passed all 109 common tests, mobile lint with zero errors, and the standalone APK build. The updated APK was installed on the DiLink 5.1 head unit for vehicle testing, preserving app settings.

### Live map embedding for launchers

[PR #107](https://github.com/shihabal3amri/DiPlay/pull/107) adds a public map-embedding service and a minimal sample in `samples/maphost`. Compatible launchers can attach, resize, and detach a live map view on Android 11 and newer. Sharing is off by default; when enabled, any local app can request the map. Disabling it removes existing mirror surfaces, releases attached views, and sends the launcher a disabled error. Delayed callbacks from released views cannot restore map access. Re-enabling sharing requires a fresh attach.

See [launcher integration](LAUNCHER_INTEGRATION.md) for the protocol, permissions, and sample setup.

### DiPlay Home sample launcher

[PR #108](https://github.com/shihabal3amri/DiPlay/pull/108) adds the separate `samples/home` launcher with a clock, embedded map, app list, standard Android widget hosting, and a button to return to BYD home. It requires Android 11 or newer. Back handling uses AndroidX callbacks to dismiss the app list and widget edit mode, while keeping the root home screen open. CI now checks both sample builds and lint, plus Home back-navigation tests on Android 12 and Android 16.

### GPS course when direction is unknown

[PR #109](https://github.com/shihabal3amri/DiPlay/pull/109) leaves the NMEA RMC course field empty when GPS bearing is absent or non-finite. It previously sent 0.00, which represents north. Finite GPS bearings and the remaining sentence fields are preserved.

## Validation and remaining vehicle checks

The release validation passed 420 local unit tests: 109 in `common`, 307 in `shared`, and 4 in the Home sample, with zero failures, errors, or skipped tests. Release lint completed with zero errors, and the production APK was built with version code 28 and the same signing certificate as 0.2.8. Mobile, automotive, map-host, and Home debug builds passed. Mobile, map-host, and Home lint completed with zero errors; warnings remain. The six website editions regenerated without changes, and public-source credential and whitespace checks passed. The checks include the earlier day/night, audio, and surround-view fixes. The test variant was installed on DiLink 5.1 for floating-map feedback. The production-signed APK has not had a separate on-car test, and automated checks do not establish compatibility across head units.

Regression tests cover cleared song titles, route expiry without another metadata frame, the empty-maneuver-list grace period, map-sharing revocation and delayed surface callbacks, and Home back handling on Android 12 and Android 16. The map-service test isolates consent and lifecycle behavior using simulated native rendering; it does not validate a physical display or decoder.

These features still need broader vehicle validation:

- Switch day/night mode with CarPlay visible and after returning from another car app.
- Verify channel 15 preview and navigation output on the affected head unit, including simultaneous media playback and persistence after reconnect.
- Open and close surround view repeatedly, verify uninterrupted media and accurate touches, and check background/foreground transitions and physical screen rotation.
- Connect with the camera window already narrow, then close it and confirm one reconnect restores full-screen CarPlay. Repeat with resolution scaling enabled.
- Check dashboard song changes, play/pause, empty metadata, and disabling the setting through the head unit's network ADB connection.
- Add the navigation widget to a compatible launcher; verify route updates, route expiry, song clearing, and disconnect/reconnect behavior.
- Check floating map drag, resize, home-screen restriction, simultaneous dashboard playback, and hiding while a launcher embeds the map.
- Disable live-map sharing with a launcher attached, confirm the existing map stops, and confirm a fresh attach is required after re-enabling it.
- Install the Home sample separately and check widget binding/configuration, back navigation, app launching, and returning to BYD home.
- Verify parked and moving GPS reports with Apple Maps; an unknown course must remain empty while valid directions continue to be sent.

The earlier #71, #88, and #89 batch used independent local validation when their PR checks were not green. All seven @romanchukg-cloud PRs had successful GitHub checks before merging. The final [combined integration check](https://github.com/shihabal3amri/DiPlay/actions/runs/36937079551) passed on source identical to the complete local review. Keep main-branch CI green before release publication. Physical vehicle checks remain to be recorded.

## Merge references

| PR | Merge commit |
| --- | --- |
| [#71](https://github.com/shihabal3amri/DiPlay/pull/71) | [0429420](https://github.com/shihabal3amri/DiPlay/commit/04294209449de38df4bb8e3affb18793e88bb82f) |
| [#88](https://github.com/shihabal3amri/DiPlay/pull/88) | [0bcc777](https://github.com/shihabal3amri/DiPlay/commit/0bcc77715f0116e1b8faf12ecd25c10ff4099bb5) |
| [#89](https://github.com/shihabal3amri/DiPlay/pull/89) | [0d21434](https://github.com/shihabal3amri/DiPlay/commit/0d21434883306a6273470439cdbea077ca2ab9c0) |
| [#93](https://github.com/shihabal3amri/DiPlay/pull/93) | [37d899a](https://github.com/shihabal3amri/DiPlay/commit/37d899ac1a2a86787f1ba0d0978487c630382a98) |
| [#101](https://github.com/shihabal3amri/DiPlay/pull/101) | [ac6f5a3](https://github.com/shihabal3amri/DiPlay/commit/ac6f5a36e8970397a6adfef1c5b333453345ff80) |
| [#105](https://github.com/shihabal3amri/DiPlay/pull/105) | [cd5ccb5](https://github.com/shihabal3amri/DiPlay/commit/cd5ccb5dff0463ed4f89353c81e84910f5def772) |
| [#106](https://github.com/shihabal3amri/DiPlay/pull/106) | [2df71dd](https://github.com/shihabal3amri/DiPlay/commit/2df71dd6355b3f45207fba7e459681345f2485e1) |
| [#107](https://github.com/shihabal3amri/DiPlay/pull/107) | [ebc7150](https://github.com/shihabal3amri/DiPlay/commit/ebc7150eddb36001dd6aa44efaa2ab58eae6555e) |
| [#108](https://github.com/shihabal3amri/DiPlay/pull/108) | [b374296](https://github.com/shihabal3amri/DiPlay/commit/b3742964fc369257c6bd3d94e94f8269e3322d33) |
| [#109](https://github.com/shihabal3amri/DiPlay/pull/109) | [48d0f91](https://github.com/shihabal3amri/DiPlay/commit/48d0f9185f2131d47203c561868fce68d76315ce) |

PR #105 was included through the history of #107; GitHub records it as merged at the commit linked above.
