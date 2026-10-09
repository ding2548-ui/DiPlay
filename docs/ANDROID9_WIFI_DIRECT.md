# Android 9 Wi-Fi Direct

Android 9 uses the public two-argument `WifiP2pManager.createGroup` overload and reads the
system-generated credentials from `requestGroupInfo`. Unlike the Android 10 configuration
overload, Android 9 asks the framework to create or reuse a persistent group-owner profile.
DiPlay removes its active group on close; it never deletes persistent profiles belonging to
the framework or another app. A retained group is reclaimed only by exact recorded ownership.

The hidden Android 9 `setWifiP2pChannels` API changes the supplicant's shared operating-frequency
restriction. DiPlay calls it only after checking that no foreign active group is present. It
serializes selection and cleanup, clears its own restriction before a system-default retry,
and clears on close only when the current group is absent or still has this attempt's identity.
Clearing means operating channel 0 (unrestricted); Android exposes no readback for restoring a
previous inactive app's custom restriction. Another app's replacement group is left alone.

An unanswered channel command has an unknown outcome and stops startup without retry or group
creation. Compensation is sent on the same callback channel before closing it, so the Android
9 service processes the selection and reset in order. Rejected or unanswered cleanup is logged
without claiming restoration, and a failed clear prevents default creation. Firmware whose
channel setter or cleanup is unreliable should use the manual car hotspot.

Android 9 does not expose the group's negotiated frequency. Reported channel/frequency is the
accepted request, marked unverified in diagnostics; system-default mode reports channel 0.
Android 10+ continues verifying the actual group frequency. Legacy configurations are not saved
as proven automatic channel choices. DFS/radar channels remain excluded.

Android 9 source references:

- [WifiP2pManager](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/wifi/java/android/net/wifi/p2p/WifiP2pManager.java)
- [WifiP2pServiceImpl](https://android.googlesource.com/platform/frameworks/opt/net/wifi/+/android-9.0.0_r1/service/java/com/android/server/wifi/p2p/WifiP2pServiceImpl.java)
- [SupplicantP2pIfaceHal](https://android.googlesource.com/platform/frameworks/opt/net/wifi/+/android-9.0.0_r1/service/java/com/android/server/wifi/p2p/SupplicantP2pIfaceHal.java)

The author's Redmi K20 Pro/iPhone test supports basic bring-up; automated tests exercise default
retry, close, late callbacks, foreign replacement and failed cleanup. BYD Android 9 cancellation
and cleanup acceptance has not been run by this review.
