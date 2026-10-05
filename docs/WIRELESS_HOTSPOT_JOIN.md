# Automatic joining of a CarPlay hotspot

An iPhone can receive usable hotspot credentials yet fail to join automatically.
If it leaves its current Wi-Fi network, but selecting the receiver's hotspot
manually starts CarPlay without a password prompt, distinguish that association
failure from a failure after the Wi-Fi link is established.

First check Bluetooth, Wi-Fi and Auto-Join for the CarPlay network, as described
in [Apple's CarPlay troubleshooting](https://support.apple.com/en-us/105109).
Reproduce while parked and share a reviewed DiPlay diagnostic report with the
receiver firmware/Android version, phone/iOS version, connection mode and whether
manual selection succeeds. Keep passwords, full network captures, addresses and
private device backups out of public reports.

## Documented requirements

[Apple's WWDC 2017 wireless CarPlay session](https://developer.apple.com/videos/play/wwdc2017/717/)
describes automatic joining after credential exchange and requires Apple Device
and Interworking information elements in the access point's broadcasts. Sending
Wi-Fi credentials through iAP2 alone does not demonstrate that the AP advertises
those elements.

[Apple's WWDC 2023 connectivity discussion](https://developer.apple.com/videos/play/wwdc2023/10150/)
states that the simplified endpoint exchange supports iOS 14 onward. No reviewed
public Apple notice identifies the failure below as an iOS 14.0.1-specific defect.
These public presentations do not certify DiPlay or describe every private
accessory specification detail.

## Controlled interoperability observation

A controlled comparison used the following receiver and phone:

- DiPlay **0.2.12**, package `com.shihab.diplay`, original installed release.
- iPhone **8 Plus / iOS 14.0.1**, with hotspot Auto-Join enabled.
- OnePlus **7T Pro / Android 13 (API 33)**, WPA2 hotspot, actual **5220 MHz /
  channel 44**. This is a receiver interoperability observation outside DiPlay's
  BYD support scope; it does not add OnePlus support.
- DiPlay advertised channel **0**, because its app could not read the effective
  channel. Android's stored channel was also 0 (automatic selection).
- Manual joining worked without asking for a password. A renamed hotspot
  reproduced the automatic-join failure, and manual joining still worked.

Fresh Windows Native Wi-Fi BSS scans showed Interworking IE **107** but no Apple
vendor element. The original vendor element was WMM (`00:50:f2`, type 2).
The same receiver, phone, release and stored hotspot configuration were used
for this comparison:

| AP broadcast | Observed automatic result | Receiver evidence |
| --- | --- | --- |
| Add Apple element | Joined automatically; CarPlay started | One AP client; IPv6 TCP accepted; session active |
| Restore original configuration, removing the element | Left home Wi-Fi; did not join | Zero AP clients; first-TCP timeout after 30 seconds |
| Re-add the same element | Joined automatically; CarPlay started again | One AP client; IPv6 TCP accepted; session active |

The experiment used the 5 GHz vendor IE `dd0800a0400000020021` (ID 221,
OUI `00:a0:40`, type 0), following the public
[LIVI implementation at a pinned revision](https://github.com/f-io/LIVI/blob/a9562234429fc9d19d9f9804b6b288f9d435a922/native/livi-helperd/crates/livi-wifi/src/server.rs#L408).
The bytes identify the tested element; they are not a general certified CarPlay
configuration recipe.

A complete private Android hotspot configuration backup was taken first. The
privileged shell API copied that configuration and changed only its vendor
element list. Removal restored the exact original configuration. After re-add,
clearing the added list from a copy produced a configuration equal to the
original, verifying preservation of credentials, security, band and other
stored fields. Each broadcast change required a hotspot restart. Fresh scans
verified the element's presence/absence and channel 44; restarts can change the
AP's BSSID, so this was not a fixed-BSSID comparison.

Windows returns received beacon/probe-response IEs through
[WLAN_BSS_ENTRY](https://learn.microsoft.com/en-us/windows/win32/api/wlanapi/ns-wlanapi-wlan_bss_entry).
This was not a packet capture of the iPhone's own association exchange. Scan
freshness was checked to avoid treating an old BSS cache entry as the current AP.

## Conclusions and implementation boundary

The remove/re-add comparison identifies the missing Apple broadcast element as
the reproducible cause on this setup. Channel 0 and IPv6 both succeeded on the
original release when the element was present. No channel override or IPv4 preference change was needed.

This does not establish an iOS-version-specific bug or a regression introduced
by 0.2.12. Other iOS versions, BYD firmware, 2.4 GHz, Wi-Fi Direct, reconnect after
reboot and long-session reliability remain untested by this comparison.

Android 13 exposes vendor elements through a privileged system API rather than
an ordinary app permission; see
[AOSP SoftApConfiguration](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android13-release/framework/java/android/net/wifi/SoftApConfiguration.java).
Older Android and vendor firmware need separate capability and permission
validation. A privileged write accepted on this test phone is insufficient
evidence for a general in-app fix.

## Opt-in app repair

**Connection setup → Built-in car hotspot → Check hotspot repair** runs DiPlay's
authorized local-ADB/app_process helper. Opening the screen, resuming the app or
starting CarPlay does not run this repair. Check reads the saved configuration
and prepares a private full rollback snapshot; it does not change the hotspot.
After a successful check, **Add automatic-join information** requires a separate
confirmation. This is experimental, capability-based BYD work, not support for a
new vehicle brand.

The helper requires Android 13/API 33 or later, the firmware's complete
`SoftApConfiguration` getter/setter and vendor-element/copy APIs, authoritative
hotspot 5 GHz capability with supported channels, and a saved **5 GHz-only**
band configuration already marked as user-configured by the framework. It rejects
2.4 GHz, automatic/mixed bands, bridged APs,
6/60 GHz and ambiguous/conflicting Apple Device elements. It does not change bands, credentials, security, channel, addresses or
Interworking IE 107. Existing vendor elements are retained in order; an exact
existing Apple element is left alone without claiming rollback ownership.
The copy is checked against the original before any write.

Default/non-user configurations are refused before a backup or write. Android 13's
[Wi-Fi configuration store](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android13-release/service/java/com/android/server/wifi/WifiApConfigStore.java#179)
forces the user-configured flag to true whenever a configuration is saved, so an
original false value could not be restored verbatim through this API. Apply repeats
the same gate, including for an older prepared snapshot. Equality checks for every
other field remain strict; unexpected firmware normalization or configuration drift
retains recovery state instead of being treated as successful preservation.

Ordinary app permissions, including WRITE_SETTINGS, are insufficient for this
API on many builds. LocalAdb supports **plain localhost:5555 ADB only**. It does
not implement Android's TLS wireless-debugging pairing protocol. Check can ask
for ADB authorization; Apply/Restore never request authorization in the
background. A receiver accessible only through a computer's paired TLS ADB
connection is not automatically accessible to this app path. No root command,
adbd reconfiguration or TLS downgrade is performed.

While parked, disconnect CarPlay and turn the hotspot off in car settings before
Apply or Restore. Both writes require a fresh, authoritative **disabled** AP
state; starting/stopping/failed/hidden states are refused. DiPlay never stops or
restarts the hotspot. Turn it on yourself afterward to update broadcasts, then
verify a fresh scan and automatic joining. Saved-configuration readback is not
proof that an OEM HAL broadcasts the element or that CarPlay joins successfully.

### Rollback and uncertain outcomes

The shell helper retains the complete original and intended configurations in a
versioned, integrity-checked atomic journal under
`/data/local/tmp/diplay-hotspot-join-<package>/`. Directory mode is 0700 and new
files are 0600, owned by the executing shell/root UID. Existing foreign,
world/group-accessible or symlinked entries are rejected. Snapshot files and the
directory rename and its parent entry are synced and read back before mutation.
Backups contain credentials: never copy them into reports, source archives or GitHub. They are
outside app diagnostic export, FileProvider and Android app backup storage.

An outstanding original is never replaced by another Check. Check recovers the
rollback action after process/app recreation. **Restore saved hotspot** requires
confirmation, the same framework/vendor firmware identity, and current
configuration equal to the original or this transaction's exact intended
configuration. Configuration drift is refused rather than overwritten. Firmware
identity includes framework/vendor build properties and a digest of the Wi-Fi
framework module, whose updates can occur independently of the Android build.
Missing/unreadable module identity fails closed. Identity is checked before
decoding an old Android parcel; incompatible or
corrupt backups remain in place for private investigation.

A rejected/exceptional write is compensated only when fresh readback proves the
saved configuration is still the original or our target and restoration is safe.
Lost readback or transport, interrupted writes, failed completion markers and
external edits retain the pending journal and report recovery/uncertainty. A
cancelled network call does not prove the helper made no change: turn the hotspot
off and Check again. A cancellation marker and bounded helper deadline gate work;
already-entered Binder calls may finish, after which verified compensation is
attempted. Process and file locks reject concurrent repair calls. Android provides
no compare-and-set transaction against independent OEM/settings writers: fresh
checks narrow that race, but unexpected changes are surfaced for recovery.

The journal survives app process death and can remain after uninstall. Keep the
same package and firmware for recovery. Do not delete an outstanding backup. A
fresh Check may replace an unused prepared snapshot or a completed rollback;
an outstanding pending/applied snapshot is retained. A preexisting element is never
removed by this repair. No credentials, backup contents, private firmware identity
or exception payloads are printed by the helper or added to diagnostic reports.

### Validation boundary

Focused JVM/Robolectric tests exercise merging, platform copy/parcel preservation,
full rollback, API/band/conflict rejection, durable storage failure, configuration
and firmware drift, cancellation, lost readback, partial writes, overlapping
locks, framework setter normalization and separate UI confirmation. The normalization
regressions use real Android 13 configuration parcels with the production adapter,
journal and transaction: default configurations and older prepared snapshots are
refused before writing; eligible user configurations apply, restore and compensate
failed/cancelled writes without relaxing drift detection. Build/lint results are
recorded in
[hotspot repair validation](HOTSPOT_JOIN_VALIDATION.md).

The controlled phone experiment above used the original APK and a separate
privileged helper. It did not exercise this new in-app path. BYD firmware,
app-issued authorization, broadcast refresh, automatic joining, reboot persistence
and long sessions still require physical-device acceptance. Android 9/10 BYD
receivers without these APIs remain unsupported by this repair; ordinary CarPlay
connection modes continue to work as before.
