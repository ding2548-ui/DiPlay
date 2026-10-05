# Hotspot automatic-join repair validation

This report separates software checks, observed device API behavior and
end-to-end wireless CarPlay acceptance. See the
[joining guide](WIRELESS_HOTSPOT_JOIN.md) for the repair workflow and the
controlled broadcast comparison.

## Software checks

| Suite | Tests | Failures / errors / skipped |
| --- | ---: | --- |
| shared | 692 | 0 / 0 / 1 |
| common | 531 | 0 / 0 / 0 |
| home | 4 | 0 / 0 / 0 |
| Total | **1,227** | **0 / 0 / 1** |

The normalization-gate follow-up was revalidated on macOS. The one skipped test is
an existing network-port test whose host assumption does not hold when wildcard
and specific-address listeners may overlap. All 33 focused hotspot-repair tests
ran without failures, errors or skips.

The 28 focused regression tests cover:

- Vendor-element preservation, duplicate/conflict rejection, API and band gates,
  authoritative AP capabilities, supported channels and capability changes
  between Check and Apply.
- Full configuration copying and parcel roundtrips with synthetic credentials
  and unrelated vendor/client/shutdown settings.
- Durable backup before mutation, explicit file and directory sync failures,
  integrity/version/firmware validation before decoding, verified apply/restore
  and retention of outstanding rollback records.
- Configuration drift, partial writes, lost readback, cancellation, failed
  completion markers and overlapping operations.
- Callback cleanup after denied, timed-out, interrupted or malformed replies,
  sanitized protocol parsing and shell argument validation.
- Inert UI rendering, separate Apply/Restore confirmation, active-session
  rejection, busy-state handling and cancellation when the activity closes.

Five additional Android 13 framework-normalization regressions exercise the
production configuration adapter, full-parcel journal and transaction together.
The test setter models Android's forced user-configured flag: Check and Apply
refuse non-user originals, including an older prepared record, before any write.
Eligible user configurations restore completely after transaction recreation and
after rejected/cancelled writes. Unexpected changes to other fields still retain
the pending original and refuse rollback through a mismatched configuration.
These checks do not replace the remaining physical-device acceptance below.

Validation command:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug :automotive:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug :automotive:assembleDebug --console=plain
```

All tests, four debug lint checks and source-only APK builds passed using the
repository's JDK 25 / Android SDK 37 / NDK 28.2.13676358 toolchain. Lint reported
zero errors; existing warning counts were 18/5/2/4 for Mobile/Home/map-host/
Automotive. All six app languages contain the repair controls and status text.
Public-tree credential and whitespace checks passed. No private configuration,
authentication asset, signing material or APK is included in the source change.

## Device API observations

Read-only checks on a OnePlus 7T Pro / Android 13 established that:

- The production Binder callback received authoritative hotspot 5 GHz capability
  and a nonempty supported-channel list under shell UID.
- The production adapter and parcel codec preserved a complete configuration
  through an in-memory 5 GHz copy/merge/roundtrip on real framework objects.
  No configuration change was persisted.
- A saved mixed 2.4/5 GHz band configuration was refused despite an active
  5220 MHz hotspot. Actual radio frequency does not establish that a saved mixed
  configuration is safe for the tested 5 GHz element.
- The isolated source-only debug app displayed the repair UI and the correct
  plain localhost:5555 requirement when only TLS wireless debugging was available.
- Full stored-configuration readback matched the private baseline afterward.
  No hotspot writes or restarts were performed during these API checks.

These observations are outside the project's BYD support scope. The separate
broadcast add/remove/re-add comparison used the original DiPlay 0.2.12 APK and
a privileged experimental helper: automatic joining and CarPlay succeeded with
the tested element, failed after removal and succeeded after re-add. Channel 0
and IPv6 worked, and other stored fields were preserved. This comparison does
not validate the new app's complete Apply/Restore path.

## Remaining hardware acceptance

App-issued ADB authorization, on-device journal durability, Apply/Restore,
broadcast refresh, automatic joining with the new app, reboot persistence and
long sessions still require physical acceptance on supported BYD firmware.
The source-only debug APK contains no private CarPlay authentication identity.
Android TLS wireless debugging alone cannot exercise the plain localhost:5555
LocalAdb path. Older firmware without the required APIs is refused.

Android provides no atomic compare-and-set against independent settings writers.
The helper serializes its own operations, rechecks state before and after writes
and retains recovery state on unexpected drift. Users must disconnect clients
and turn the hotspot off before writes, then restart it themselves to refresh
broadcasts. A lost or cancelled ADB response remains uncertain until a fresh
Check/readback recovers the durable state.
