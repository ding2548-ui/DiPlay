# DiPlay 0.2.12 — 2026-10-04

- The final combined source was tested and built at `c1a7f670dfe99860d21d57ebbb383a7e75772eef`, including corrected PR #230 `7504bf38` and #235 `1eac74af`, plus the contributions merged since 0.2.11. Later release preparation changes documentation and reconciles merge history; publishing checks require application code and build inputs to remain unchanged from the signed build.
- **1,193 unit tests passed**: 662 shared, 527 common and 4 Home. Zero failures/errors. One additional shared wildcard-bind test skips on macOS because its socket reuse semantics prevent the intended conflict; ordinary port-conflict and cleanup coverage passes. The map-host task has no unit-test sources.
- Mobile, Home, map-host and Automotive source-only debug builds and debug lint passed. Production-signed mobile release build and release lint passed. Lint has zero errors; existing warnings remain (18 mobile debug, 5 Home, 2 map-host, 4 Automotive; 4 mobile release).
- APK verified: package `com.shihab.diplay`, version `0.2.12`, version code `31`, minimum SDK `28`, target SDK `37`, not debuggable. Wi-Fi Direct requires Android 10+.
- APK signing certificate SHA-256 `87b38b12788dcb202a961215f2572e30ec2dc9d8ef4bc070d05f77e49291a363` matches the published 0.2.11 APK. The two runtime authentication assets match the selected local inputs and 0.2.11 byte for byte. No Android signing keystore or extra credential container is packaged. Manifest permissions are unchanged from 0.2.11.
- Native library entries are unchanged. The four AndroidX native files are byte-identical to 0.2.11. The six rebuilt project libraries differ only in their validated GNU build-ID descriptors; all remaining ELF bytes, including executable code, data and relocations, are identical. JNI sources, build definitions, dependency versions and NDK configuration are unchanged.
- The signed build records a clean source commit/tree before and after Gradle and freezes its APK size and SHA-256. Verification and publication require the same bytes. The corresponding source archive is generated from the final release commit; public-tree and archive checks exclude signing keys, runtime identities, private directories and build outputs. Source-only CI does not provision runtime authentication assets. The APK, tagged source ZIP and SHA-256 checksums are supplied together.
- Focused review tests exercise #230's negotiated-canvas decoder limits, resolution persistence and dialog cancellation, and #235's firmware-advertised commands, checked shell results, AP-state observation, cancellation/deadline and blocked ADB socket cleanup. Earlier regression coverage includes Same LAN ownership, DiLink 3 recovery, scan-pause restoration, route tokens, stale wheel commands, live dashboard delivery and song/artwork lifetimes.
- No fresh vehicle test of the complete repaired release is claimed. DiLink 3 cluster/scan integration, DiLink 4 cluster routing, repaired wheel modes and firmware-specific hotspot startup need current-device retests. Unverified DiLink 4 HUD output remains disabled. General startup, stutter, calls/Siri/microphone, iOS 15 and colour/theme reports remain open for fresh evidence. Force-stop can defer restoration until DiPlay reopens.
- See [0.2.12 release notes](RELEASE-NOTES-0.2.12.md) for all reviewed contributions, feature limits and diagnostic reporting steps.

# DiPlay 0.2.11 — 2026-10-03

- Release source starts from merged main `a83ded7`, including PR #175 and the reconciled changes merged through PR #173. App version/build validation ran at `d6ed55f`; subsequent release preparation changes documentation only.
- Final source workflow passed: **773 unit tests passed** (499 shared, 270 common, 4 Home), zero failures/errors. One additional shared wildcard-bind test skips on macOS when socket reuse semantics prevent the intended conflict; the ordinary port-conflict and socket-cleanup tests pass.
- Mobile, Home and map-host source-only debug builds and debug lint passed. Production-signed mobile release build and release lint passed. Lint has zero errors; existing warnings remain (18 mobile debug, 5 Home, 2 map-host; 4 mobile release).
- APK verified: package `com.shihab.diplay`, version `0.2.11`, version code `30`, minimum SDK `28`, target SDK `37`, not debuggable. Wi-Fi Direct still requires Android 10+.
- APK signing certificate SHA-256 `87b38b12788dcb202a961215f2572e30ec2dc9d8ef4bc070d05f77e49291a363` matches 0.2.10, preserving installation over the prior public release. The runtime authentication assets match the explicitly selected local inputs and 0.2.10 byte for byte. No Android signing keystore or extra credential containers are packaged; native library entries are unchanged from 0.2.10.
- The only added manifest permission versus 0.2.10 is `WRITE_SETTINGS` for the optional, default-off own-package hotspot feature. Diagnostics add no permissions or automatic uploads.
- Public-tree credential and source-archive preflight scans passed. Android signing keys, accessory identities and build outputs remain outside Git and the source archive. Source-only CI does not provision runtime authentication assets. The corresponding source archive and SHA-256 checksums accompany the release.
- No fresh 0.2.11 vehicle validation is claimed. Qin Plus startup, Wi-Fi Direct loss/stutter, Siri/microphone quality, iOS 15 connection and day/night firmware reports remain open for current-device evidence. Manual channel choice is not a confirmed stutter fix. Optional legacy data/hotspot, parked-video readiness, TV/knob inputs, turn-card placement, wired app-only VPN and geometry reconnect need device acceptance.
- See [0.2.11 release notes](RELEASE-NOTES-0.2.11.md) for feature scope and fresh diagnostic export instructions.

# DiPlay 0.2.10 — 2026-10-03

- Final combined source workflow: 528 unit tests passed (384 shared, 140 common, 4 Home), zero failures/errors. One additional wildcard-bind test skips explicitly on macOS when its socket reuse semantics prevent the intended conflict; the ordinary port-conflict and socket-cleanup tests pass.
- Mobile, Home and map-host debug lint and all three source-only debug APK builds passed. Release lint and the production-signed mobile release build passed. Lint warnings remain (18 mobile debug, 5 Home, 2 map-host; 4 mobile release).
- All ten corrected PR heads passed GitHub Android checks before merging. Tests cover remote-video URL/redirect rejection, bounded artwork queues and stale sessions, USB padding/fragment/coalescing controls, available-port fallback and socket ownership, call effect/mode lifecycle, and bounded privacy-safe diagnostic persistence under blocked writes and callback failures.
- Package `com.shihab.diplay`, version `0.2.10`, version code `29`, minimum SDK `28`. Signing certificate matches the published 0.2.9 APK, preserving the upgrade path.
- Public-source credential checks pass. Release runtime authentication assets match the explicitly selected inputs; no Android signing keystore is packaged. The corresponding source archive and checksums are supplied with the release.
- No fresh on-car validation of 0.2.10 was performed. The captured issue #100 framing pattern is fixed in synthetic and real-reader replay tests; complete media and reconnect operation still needs device confirmation. Huawei/RK3326 Bluetooth, P2P loss and microphone-routing reports remain under investigation. See [release notes](RELEASE-NOTES-0.2.10.md).

# DiPlay 0.2.9 — 2026-10-02

- 420 unit tests passed: 109 common, 307 shared, and 4 Home sample tests, with zero failures, errors or skipped tests.
- Mobile release lint and the production-signed release build passed; lint warnings remain.
- Package `com.shihab.diplay`, version `0.2.9`, version code `28`. Signing certificate matches the published 0.2.8 APK.
- Ten floating-map gesture tests include stable initial contact, both size limits, pointer changes, persistence, and enlarging a reopened minimum-sized card.
- Public-tree and source-archive scans exclude runtime identities, signing keys, and build output. Runtime authentication assets in the APK match the explicitly selected local inputs; the Android signing key is excluded.
- The test variant was installed on DiLink 5.1 and user feedback drove the floating-map fixes. The production APK has not had a separate on-car test. Broader vehicle checks remain documented in [release notes](RELEASE-NOTES-0.2.9.md).

# Restored 0.1.0 release — 2026-09-25

- Built from the current public source with explicitly selected external authentication assets and the existing local Android signing key.
- 172 JVM/Robolectric tests passed; zero failures/errors. Release lint and signed release build passed.
- Public-tree credential scan passed. Source tests generate identities at runtime; no credential containers or private-key blocks are tracked.
- Verified that the APK contains the intended runtime accessory identity and no Android signing keystore.
- Signing certificate SHA-256: `87b38b12788dcb202a961215f2572e30ec2dc9d8ef4bc070d05f77e49291a363` (unchanged).
- Package `com.shihab.diplay`, version `0.1.0`, version code `10`; restoration changes packaging and public documentation, not app behavior.
- Existing USB-only TLS trust-manager warnings and unused-resource warning remain; this is not a completed security audit.
- No fresh physical-car validation was performed for the restored artifact. Previous emulator and private-build testing do not establish universal compatibility.
