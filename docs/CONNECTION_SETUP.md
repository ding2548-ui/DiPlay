# Built-in car hotspot setup

DiAuto & DiPlay — Built-in car hotspot test builds
28 September 2026

DiAuto-connection-setup-test.apk: Android phone / Android Auto
DiPlay-connection-setup-test.apk: iPhone / Apple CarPlay
Install the APK on the car, not on your phone. Update the matching existing
test app without uninstalling to preserve settings.

These builds remove the Local hotspot option. Built-in car hotspot is the
default; Wi-Fi Direct and USB remain available. Previous Local hotspot
selections switch to built-in hotspot. Check and save the car's real hotspot
details before connecting.

IN-SESSION SETTINGS AND AUTHENTICATION — DIPLAY
In CarPlay, swipe down with the configured number of fingers (2, 3 or 4;
default 3) to open the hidden settings menu. Opening or cancelling the menu
keeps a healthy session connected. Save and reconnect applies the edits;
Back or X discards them. A connection lost while the menu is open recovers
on closing, unless Wi-Fi requires the existing manual reset action.

The hidden menu offers Local offline and USB/CH341 authentication. Local is
the default and requires a provisioned identity for the first connection.
Selecting USB/CH341 and saving uses the configured CH341 bridge for this and
subsequent connections, without installing or loading local identity files,
even if they already exist. Allow Android's USB permission prompt. A missing
bridge waits for hardware; it does not fall back to local authentication.
Connecting the CH341 bridge does not change wireless CarPlay to wired mode.
Switching back to Local validates the identity before saving; failure keeps
the menu open and preserves the previously saved authentication choice.

BUILT-IN HOTSPOT SETUP — BOTH APPS
1. In the car's settings, turn on its built-in Wi-Fi hotspot. Select 5 GHz
   if available. Note the hotspot name and password exactly.
2. Open DiAuto or DiPlay on the car. Go to Settings → Connection setup
   (tap Open connection setup if shown).
3. Select Built-in car hotspot. Tap Save hotspot details and use this mode
   (or Edit saved hotspot), enter the car's hotspot name and password, and
   save. Use Hide keyboard if needed. Leave the car hotspot on.
4. Turn on Bluetooth and Wi-Fi on your phone. Pair it with the car's
   Bluetooth. Allow the app permissions requested on the car.
5. Return to the app and tap Connect phone. Select your phone when asked.
   In DiPlay, use Choose iPhone if you need to select a different phone.
6. Accept the Android Auto or CarPlay prompts on your phone.

You do not need to join the hotspot manually on your phone before tapping
Connect phone. The app sends its details over Bluetooth so the phone can
join automatically. Use the car's hotspot, not your phone's Personal Hotspot.
ADB is not required for this connection setup. A car internet plan is not
required; phone internet availability depends on its network settings.
If you change the car hotspot name or password, update it in the app too.
Test one projection app at a time.

EXISTING WI-FI / SAME LAN — DIPLAY
Connect the car and iPhone to the same external router or portable Wi-Fi in
system settings. In DiPlay Connection setup, select Existing Wi-Fi / Same LAN
and save that network's exact name and WPA2 password. Keep Bluetooth enabled,
then connect as usual. No car hotspot or Wi-Fi Direct group is created. Disable
router client isolation. See [Existing Wi-Fi](EXISTING_WIFI.md) for build
requirements, network limitations and device validation.

WI-FI DIRECT CHANNEL — DIPLAY
In Settings → Connection setup, choose Wi-Fi Direct, then Preferred channel.
Auto is the default and keeps DiPlay's automatic channel selection. You can
choose a 5 GHz channel (36, 40, 44, 48, 149, 153, 157, 161 or 165), or a
2.4 GHz channel (1–11). The car and its regional Wi-Fi settings must support
the selected channel. Save applies the choice to the next Wi-Fi Direct
connection; an existing connection continues until you disconnect/reconnect.
If the car rejects the channel or creates a different one, DiPlay reports an
error. Choose Auto or another channel and reconnect. Switching to the built-in
hotspot preserves this choice without applying it to the car hotspot.

OPTIONAL: DIPLAY BYD FEATURES REQUIRING ADB
Settings → BYD features · needs ADB appears only when BYD navigation services or
the factory BYD car settings app are present, and traditional network ADB is
reachable at 127.0.0.1:5555. The hotspot check also covers older QUALCOMM/qti
head units without the supported navigation services; it does not enable
navigation-output features on those units.
An unapproved ADB key still shows the setup entry; TLS pairing is not supported.
The initial check never requests approval or reads vehicle data.

The BYD ADB section contains battery reporting (with charging connectors and
low-charge warning), wheel speed for tunnels, video while parked, and the
dashboard song. These use autoservice through ADB, not the AMap navigation
receiver. Check ADB access and Apply and reconnect are in this section too.
The cluster-map stream switch retains its existing display and firmware checks.
Navigation arrows, HUD, and map-display settings keep their existing capability
checks in BYD navigation. Saved choices and vehicle-reading behavior are unchanged.

Automatically turn on the car hotspot appears as one switch and description in
the same section only when Built-in car hotspot is selected. Wi-Fi Direct hides
it without changing the saved choice or the other BYD ADB options.
The switch is off by default. Turning it on automatically requests missing
WRITE_SETTINGS through ADB, without a separate setup button or DiPlay confirmation.
Approve the car's system ADB prompt if needed. The switch is enabled only after
the required permissions are verified; a failed grant leaves it off and shows a message.
After the grant, firmware that allows the app's direct hotspot request can turn
on the saved hotspot without ADB. Firmware that blocks that request needs an
already-authorized, reachable traditional network ADB connection at each startup
or connection attempt. The fallback uses only a saved-hotspot start command
advertised by that firmware's service help, checks its result, and waits for an
observed AP enabled state. These commands are not standard Android commands;
this does not establish support for every DiLink version. If neither path is
supported, use the car's own hotspot settings.
Turning ADB off hides this setting but preserves the choice; enable ADB again
to change it. USB, Wi-Fi Direct,
disconnecting, exiting, and turning this option off do not stop the hotspot.
Unsupported firmware, missing permission, and startup failures are reported;
the car's own hotspot settings remain available for manual setup.

For startup after boot, also enable Open after the car starts. When both options
are selected, the switch being enabled also requests missing SYSTEM_ALERT_WINDOW
for boot launch. This does not enable a floating map or turn on the boot option
automatically. The head unit may also require its own auto-start permission.
Connect when DiPlay opens remains a separate choice: the app can start the hotspot
without connecting to an iPhone.

OPTIONAL: DIPLAY AUTOMATIC INSTRUMENT MAP
On the supported DiLink 5.1 firmware, open Settings → BYD navigation →
Automatic map setup · ADB. Follow the displayed one-time computer setup,
then tap Check and enable. Open the cluster's map card or select Map theme.
This permission is for automatic cluster theme/card detection, not hotspot
connection. The guide explains the exact command for the installed app.

WHAT TO TEST / REPORT
Check first connection, reconnect after restarting the app/car, maps,
music/audio, and any instrument-map features supported by your car.
If something fails, note the time and steps, car model, DiLink/Android
version, phone model/OS, and which app you used. Export a diagnostic report
from Settings → Diagnostics → Save diagnostic report. Reports are saved
under Downloads/DiAuto or Downloads/DiPlay. Share the report with your test
feedback; do not include your hotspot password.
