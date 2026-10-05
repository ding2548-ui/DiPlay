# Compatibility

This public preview is an independent receiver, not an Apple-certified CarPlay accessory. The experimental bundled accessory identity is extractable and its future acceptance is not guaranteed.

| Area | Current scope |
| --- | --- |
| Head unit | Android 9+ APK; wireless Wi-Fi Direct path needs Android 10+ |
| Phone | Standard, non-jailbroken iPhone with CarPlay enabled; device/iOS compatibility varies |
| Physical evidence | Previous private builds: wired and wireless picture, touch and audio confirmed on the development car with iPhone XS / iOS 18.7.10 |
| Other cars | Mixed community reports across DiLink generations; not a certified model support list |
| Current release | DiLink5.1: HUD/street names and Car hotspot confirmed; Wi-Fi Direct improved, occasional audio cutouts remain |
| Wi-Fi | Prefer 5 GHz without an established station connection; align to a supported existing station channel; explicit 2.4 GHz fallback for firmware that rejects 5 GHz or automatic channel selection |
| Video | Default H.264 / 30 fps; 60 fps and HEVC increase device-specific demands |

## BYD HUD and car hotspot

See [BYD navigation](BYD_NAVIGATION.md) for the exact verified firmware and lifecycle limits. Car hotspot now starts CarPlay on the development car using scoped IPv6. The phone must join the configured car hotspot. Neither result guarantees support on every firmware.

## Known limitations

- If the iPhone leaves its current Wi-Fi network but does not join the car hotspot, check that Auto-Join is enabled for that hotspot. If selecting it manually starts CarPlay without a password prompt, record that distinction in the diagnostic report. AP broadcast information elements can affect automatic joining even when credentials and the subsequent CarPlay transport work. See [automatic hotspot joining](WIRELESS_HOTSPOT_JOIN.md) for Apple's documented requirements and a controlled comparison; this does not establish a supported fix for every head unit or iOS version.
- Some units stutter, particularly under higher video load. A 2.4 GHz link alone does not prove the cause: interference, firmware and decoder stalls can all contribute. Try Default icons, 30 fps and a lower resolution, then attach a report.
- A contributor reported periodic wireless CarPlay stutter on a 2023 Han with GCC DiLink 3 when the car's Wi-Fi client was disconnected: scans every 10 s took the radio off the CarPlay channel for 3-6 s. On Android 10, with network ADB already authorized and the framework transaction available, DiPlay pauses connectivity scans for hotspot/P2P sessions. Same LAN is excluded so station reconnect and roaming remain available. Controller leases share one serial worker; scans restore after the last lease closes. A durable marker precedes suppression, failed restores retry every 30 s while the app runs, and app startup recovers an interrupted restore. A force-stop can delay restoration until the app is opened again. Missing approval or transaction support leaves scans alone. The updated in-app lifecycle needs a parked vehicle retest; without ADB, joining the car to a hotspot also slowed scans in the contributor's test.
- Some iOS/head-unit combinations do not visibly apply icon and text size. Reconnection is implemented; that does not guarantee the iPhone chooses the requested layout.
- A radio that supports joining a 5 GHz network may still reject a 5 GHz Wi-Fi Direct group. The capability flag is diagnostic, not proof of group-owner support.
- Automatic startup depends on the car's firmware and startup permissions.
- USB requires a data port and correct host/device-role behavior.
- Calls, Siri, background reconnection, long journeys and future iOS releases need broader testing.

Reports record requested and actual frequencies, station association state, fallback failures and remembered-configuration events. Wi-Fi credentials and protocol payloads are excluded. A successful hotspot is not itself a successful CarPlay session.

Android references: [SupplicantState](https://developer.android.com/reference/android/net/wifi/SupplicantState), [explicit P2P operating frequency](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig.Builder#setGroupOperatingFrequency(int)).
