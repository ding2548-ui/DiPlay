# PR 158 and PR 164 integration rationale

## Validated publication context

The combined candidate `3aacba4` on `merge/reviewed-safety-20261003` includes corrected PR 158 head `f6e1838`, accepted-probe publication guard `bc76616`, PR 164's reconciled hotspot settings, the other reviewed feature changes and local diagnostics. The complete workflow passed: **761 tests passed, one host-specific skip, zero failures/errors; three app lint checks and debug builds passed with zero lint errors and 25 warnings.** Source/APK credential checks and locale checks also passed.

PRs 146, 155, 156, 157, 161, 162 and 168 have merged at this pre-integration checkpoint. The remaining PR 158/164 histories are retained in the integration candidate. The contributor received only the scoped PR 158 corrections, including stale-result rejection and nonblocking accepted-battery publication. PR 164's fork does not permit maintainer edits. PR 169 is held, PR 170 is deferred at the user's request, and PRs 171/172 are outside this batch. Physical vehicle acceptance remains outstanding.

The rationale below originated in an **earlier isolated preflight snapshot** merging corrected PR 158 into safety branch `bb6dd37`. That candidate's first parent included other reviewed PRs and diagnostics, so it was deliberately not pushed to the contributor's PR. Its conflict-resolution rationale is retained and reused in the current combined candidate; the contributor received the narrow corrected head instead.

## Settings ownership

PR 158's **Location → Advanced vehicle data** owns the default/legacy mode, saved probe, supported-field controls, explicit replacement flow, automatic validation and vehicle-data reconnect preflight. Its Dashboard song control appears once, in the navigation card or the advanced section when navigation is unavailable.

PR 164's separate BYD ADB card owns the optional automatic car-hotspot setting. The default remains off. Its existing explicit authorization still checks actual own-package WRITE_SETTINGS/overlay permissions before saving the preference. Boot startup, manual/P2P eligibility, cancellation, fixed permission operations, preservation of hotspot credentials and the existing no-stop behavior are unchanged. The old second set of vehicle controls and its Apply button were removed from this card so they cannot bypass PR 158's mode/capability checks. The corresponding tests now exercise the unified flow.

The merged render function must not increment `adbCheckGeneration`: PR 158 renders its progress immediately after scheduling a check, so PR 164's old render-side invalidation would discard that check's completion. Destruction still invalidates pending checks. A late hotspot eligibility callback only updates its own card and cannot cancel a vehicle reconnect preflight.

Hotspot permission work and user vehicle ADB work do not start alongside one another. Controls disable while the other user operation is pending, and method guards also reject stale callbacks. Automatic saved-field validation is paused for explicit hotspot permission work and resumes after completion. No new permission, arbitrary command, firmware write or vehicle-data field was introduced by this reconciliation.

## Probe and battery concurrency corrections

User-probe persistence checks its volatile generation and captured vehicle-data mode under `vehicleOperationLock`. Destruction and explicit mode selections invalidate that generation under the same lock; selecting the same mode also cancels a superseded selection. Pausing alone does not invalidate an intentional accepted save.

The field store retains atomic snapshot acceptance, including lost-field and expected-snapshot guards. Accepted battery samples no longer acquire the battery shell-reader lock while holding the store monitor. A separate short publication gate serializes cache writes without field-store or shell work inside it. Its generation fence prevents a reader started before the accepted publication from overwriting the newer sample. Default-mode and rejected candidates still do not publish.

The nine new deterministic tests reproduce six failures on source `1f8bab9`, with three positive controls passing. All pass in the final combined workflow. Controlled battery workers release and terminate within bounded waits, even against the failing implementation. The hotspot-wait regression initializes authorization before Activity setup and verifies zero reads while blocked and exactly one resumed validation; it preserves its saved-snapshot assertions.

## Other conflict resolutions

- Resource files retain both feature sets and all diagnostic/settings-gesture/turn-card strings. For the two conflicting dashboard-map labels, PR 158's shorter label wins; the explanatory ADB requirement remains in the description. XML parsing and duplicate-name checks cover all six locales.
- `LocalAdb` retains PR 164's buffered input and bounded approval recheck, together with PR 158's bounded one-shot command read window (`MAX_COMMAND_TIMEOUT_MS`). Both constants remain; no authorization or timeout bound was broadened during reconciliation.
- `AndroidMediaSink` retains the safety branch version unchanged: API 28-compatible effective attributes, diagnostic failure stages, bounded logging and codec failure cleanup survive.
- PR 158's `CarPlayHostActivity` reconciliation adds four mode-aware active-setting substitutions over the safety parent. The current combined candidate also retains the reviewed TV input changes; theme/exit/rotation/VPN diagnostics and fixes remain present.
- Navigation/testing documentation keeps both the runtime Wi-Fi ownership/parked-video readiness guidance and PR 158's mode/probe/location acceptance checks. Raw issue logs are not imported.

## Change provenance and verification

PR 158 feature sources: `BydVehicleSettingsBackend`, `Byd13CatalogProbeMain`, `BydVehicleCapabilityProbe`, `BydVehicleFields`, mode-aware `BydOutputSettings`, supporting ADB/battery/parked-state/wheel-speed reads, their tests, the four host active-setting calls, vehicle/location UI and localized resources. Guard `bc76616` still publishes battery data only after a snapshot is persisted/accepted and only in the selected legacy mode; held candidates stay outside runtime data.

Integration-specific edits: `DiPlayActivity`, `BydAdbSettingsUiTest`, `BydSettingsReconnectTest`, `CarHotspotSwitchTest`, the hotspot and stale-probe cases in `BydVehicleDataSettingsTest`, the conflict resolutions in resources/`LocalAdb`/navigation/testing documents, and this rationale. Existing PR 164 grant/setup/controller/network sources and the safety parent's diagnostic sources are unchanged.

At the earlier isolated-preflight checkpoint, static checks completed: no unresolved markers, all localized strings parsed with no duplicate names, `git diff --check`, and `scripts/check_public_tree.py`. No Gradle run or GitHub mutation had occurred in that isolated worktree, and its new/updated tests were then unexecuted. Those statements are historical, not the current workflow/publication status above. No APK download, new binary, credential import, minSDK change, PR 169 or PR 170 source change was introduced by this reconciliation. Hardware acceptance remains necessary for supported head units, ADB denial/loss, mode changes, accepted/rejected legacy fields and actual hotspot startup.
