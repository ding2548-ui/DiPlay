> Historical engineering notes: Local hotspot is no longer offered in the app. Saved local-hotspot selections migrate to built-in car hotspot; the internal transport code remains for compatibility. Use [Connection setup](CONNECTION_SETUP.md) for current instructions.

# Standalone 5 GHz local hotspot

This transport is optional; existing Wi-Fi Direct and car-hotspot choices keep their settings. The app requests and releases its own Android LocalOnlyHotspot reservation. The car does not need an internet subscription. The phone still needs mobile data or downloaded maps for navigation data. No ADB, root, Shizuku or separate helper is needed at runtime.

On the tested DiLink 5.1/Android 13 firmware, Nearby devices permission allows the configured 5 GHz reservation through a capability-checked framework SystemApi. This is firmware-dependent, not a guarantee for every Android 13 device. API 36+ uses the configured-hotspot entry point. The firmware can override the requested channel; a fixed request is not proof that the AP stayed on it.

Select **Settings → Wireless link → Local hotspot · experimental**. On DiLink 5.1, use **Open car Wi-Fi settings** and turn **the car's Wi-Fi switch off**, then return to DiPlay and connect. Keep the car's Bluetooth and the iPhone's Bluetooth/Wi-Fi on. Merely disconnecting home Wi-Fi leaves background network searches running. The app's separate hotspot can run while the car's client switch is off. To use Wi-Fi Direct again, re-enable the car's Wi-Fi. The app never silently changes this system setting.

## In-car evidence, 27 September 2026

- The initial hotspot builds selected the wrong interface and then advertised automatic channel 0. Those builds did not connect. The current build requires the actual AP interface and reads its settled live channel/BSSID with the permitted LOHS radio callback. Ethernet, station and ambiguous interfaces are rejected.
- 5 GHz with the car Wi-Fi client enabled connected but still stuttered/froze. BYD changed AP channel 40 to 149 even after accepting a fixed channel-40 request.
- With the Wi-Fi client off, an existing session had 23 consecutive five-second audio windows with zero underruns or packet loss. A separate cold launch connected both screens and had another 22 clean windows: main display median 33.8 fps, cluster 17 fps. Re-enabling car Wi-Fi reproduced stutters and a 7.7-second receive gap without changing the rendering code.
- The control socket now has TCP health deadlines. A real dead connection timed out and automatically reconnected during testing. This prevents the observed indefinite freeze; it does not hide all short network stalls or guarantee uninterrupted playback.

A final installed-build check through the ordinary BYD Wi-Fi switch repeated the result for approximately five minutes: 58 audio windows, no underruns/packet loss, no reconnects, main median 33.6 fps and cluster 18.4 fps. Enabling car Wi-Fi reproduced underruns within five seconds.

These are parked-car observations on one firmware, not a promise of zero lag on every car. A longer drive/reboot test remains necessary. The DiLink 5.1 theme fix still uses one cluster stream without reconnecting when themes change; the older DiLink 5 path is retained. Tests use temporary development instrumentation with automatic restoration, not a runtime helper bundled into the app.

## Historical legacy Android compatibility candidate

The retained internal fallback requests the ordinary local-hotspot reservation on older Android and reads the selected AP interface with the read-only Wireless Extensions `SIOCGIWFREQ` ioctl. Android's unprivileged socket ioctl policy permits this query; the vendor driver still has to implement it. It uses no shell, root, ADB, Shizuku or privileged network-setting calls, and it never reads the station interface as a substitute for the AP. Frequency/channel decoding rejects unknown/ambiguous data and waits for two seconds of stable readings before connecting. Automatic channel 0 is never advertised to the phone.

The Android 13 configured-hotspot path remains in place. Older firmware chooses its own default band; this fallback cannot force 5 GHz. A 2.4 GHz result is rejected explicitly, with instructions to select a manually configured 5 GHz car hotspot or Wi-Fi Direct. A driver that cannot report the frequency also fails explicitly. These candidate builds require DiLink 3 vehicle testing before compatibility can be claimed. A car hotspot does not require the car to have an internet subscription.

The native query is scoped to an interface already selected from the app's owned reservation, and all descriptors/reservations are closed on failure. See [Android's unprivileged socket query list](https://android.googlesource.com/platform/system/sepolicy/+/android-10.0.0_r1/public/ioctl_macros) and [Android 10 local-hotspot band selection](https://android.googlesource.com/platform/frameworks/opt/net/wifi/+/android-10.0.0_r1/service/java/com/android/server/wifi/WifiServiceImpl.java).
