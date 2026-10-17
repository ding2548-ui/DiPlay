# Hide the BYD call popup

On BYD Android 12L (SDK 32), **Advanced → Advanced media → Hide BYD call popup**
is an opt-in setting, off by default. It needs classic local ADB at
`127.0.0.1:5555` and approval of DiPlay's existing ADB key. Use the existing
**Check ADB access** action to approve it; the background guard never opens an
authorization prompt while driving.

When connected CarPlay is in the foreground, a shell helper sets only the
`SYSTEM_ALERT_WINDOW` package app-op of `com.byd.bluetoothcall` to `ignore`.
Minimizing CarPlay, opening its settings, disconnecting, reconnecting, or
stopping it releases the lease and restores the exact original raw mode.
Changing this setting does not reconnect CarPlay. It does not change Bluetooth
audio, call handling, video, or the package's enabled state. A separate full-screen
Bluetooth phone Activity is not an overlay and is not hidden by this feature.

The package shares the system UID on tested DiLink firmware. The helper therefore
uses package-level `setMode`, never UID-wide `setUidMode`. An already ignored
popup is left alone; a later external mode change is not overwritten on release.
Only one helper can own the lease. Sequenced heartbeats expire after ten seconds
and restore the mode if DiPlay crashes or loses ADB. Restoration is checked and
retried four times; a lost acknowledgement is not reported as confirmed recovery.

The popup hide/restore commands were manually confirmed on a DiLink 5.0 Android
12L head unit. The automatic upstream integration has JVM regression coverage,
but has not yet been tested on that head unit. Other Android versions and BYD
firmware are not claimed supported. If ADB, reflection, or the BYD package is
unavailable, the optional feature cannot hide the popup.

## Recovery and verification

Killing the **helper itself** with SIGKILL, a reboot, a power loss, or a stuck
system service can prevent restoration. The lease is not a durable reboot
recovery mechanism. After such an interruption, check the mode with:

```sh
adb shell cmd appops get com.byd.bluetoothcall SYSTEM_ALERT_WINDOW
```

For the tested car, the original mode was `default`; its manual recovery is:

```sh
adb shell cmd appops set com.byd.bluetoothcall SYSTEM_ALERT_WINDOW default
```

Use your own recorded original mode if it was different. To validate on the car,
enable the opt-in, connect CarPlay, and check that a native call popup is hidden.
Then minimize CarPlay and verify that the native popup returns. Also verify
disconnect, settings open/close, app process death (leave the shell helper alive),
and ADB loss. Perform these checks while parked.

The settings tests also render compact English and full-width Arabic rows at
160% font size into `common/build/call-popup-screenshots/`. These are local UI
renders, not head-unit screenshots.
