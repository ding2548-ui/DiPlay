# Existing Wi-Fi / Same LAN

Connect the Android receiver and iPhone to the same third-party router or portable
Wi-Fi in system settings. In DiPlay → Connection setup → Wireless, select
**Existing Wi-Fi / Same LAN**, enter the exact SSID and WPA2 password, and save.
For an open network, leave the password empty. Keep Bluetooth enabled and the iPhone
paired with the receiver, then start wireless CarPlay as usual.

Use WPA2-Personal or WPA2/WPA3 mixed mode. WPA3-only, enterprise authentication and
captive portals are outside this mode’s current scope. Both clients must be able to
communicate; allow multicast DNS for service discovery. Prefer 5 GHz when supported.

DiPlay attaches to the existing connection without creating an AP, joining another
network or changing the default route. The router supplies Internet access.
Existing Wi-Fi credentials are stored separately from car-hotspot settings.
Android cannot expose saved passwords to ordinary apps, so the password is entered
manually. A readable live SSID must match the saved name. The manager tries station
WifiInfo when the network capabilities snapshot hides that name; if both snapshots
hide it, verify the manual configuration in Android settings. Precise location
permission and enabled system location can make network details readable, but are
not required merely to start this mode or tied to optional GPS reporting.

## Implementation

`ExistingWifiManager` implements the wireless backend contract. It selects the single
non-VPN Wi-Fi Network, obtains its interface and addresses from LinkProperties, and
reads channel information from WifiInfo. Internet validation is not required for LAN
communication. Ambiguous Wi-Fi connections and observable configuration mismatches
fail with a configuration error.

The bootstrap endpoint prefers a scoped link-local IPv6 address, falling back to IPv4.
Existing Wi-Fi also retains the other usable address family for discovery and TCP.
Each family has a JmDNS registry and a listener bound to the same selected interface
and port. Port fallback is shared and partially bound listeners are cleaned up.
Discovery probes use a matching source family and local IPv6 interface scope.
JmDNS `getInetAddress()` identifies the registry family; deprecated `getInterface()`
can return another address of the same Android interface. TXT feature bits share the
same source as AirPlay `/info`.

The existing Bluetooth/iAP2 sequence is reused: 0x5703 carries network credentials,
and 0x4301 carries the selected endpoint without a local scope suffix. A readable AP
BSSID is an optional 0x5703 hint, separate from the receiver’s AirPlay identity. Unknown,
placeholder, zero, multicast or malformed AP addresses are omitted. The P2P and car
hotspot backends retain their existing single-address defaults and bootstrap behavior.

Network loss or a change to either selected address restarts the wireless session.
Closing the backend unregisters its callback without disconnecting Wi-Fi. Saved
automatic car-hotspot startup applies only to car-hotspot mode.

Related work: [PR #22](https://github.com/shihabal3amri/DiPlay/pull/22) also implements
external Wi-Fi within an Android 7 port. This feature uses the current architecture
without importing that port or its system network modifications.

## Validation

Tests cover station selection, redacted network information, manual configuration,
address scope and family selection, callback cleanup, settings persistence, dual-family
publication/listening, port fallback, mode transitions and bootstrap encoding. Existing
P2P and hotspot tests run alongside them.

The contributor reports a successful clean install of the final implementation on a
2022 BYD Han DM-i with DiLink 4.0 / Android 10 (API 29), firmware
21.1.21.2401160.1: the previous test app was uninstalled, the standalone test APK was
installed and configured normally, and Same LAN entered CarPlay with no issues noticed
during use. This validates that setup; it does not establish compatibility with every
receiver, iOS version or router. Open and WPA2/WPA3 mixed networks have not been
separately validated on hardware.
For a device check, confirm that both devices keep the existing Wi-Fi connection,
CarPlay starts, and the iPhone can use online services. For failures, save one diagnostic
report with the attempt time and the iPhone’s actual Wi-Fi connection. An absence of
Bonjour control-service events alone does not prove that CarPlay discovery failed.

## Builds

A source-only debug APK omits runtime accessory authentication. Use the existing
`:mobile:assembleStandaloneDebug` task with the explicit external assets described in
[BUILD.md](BUILD.md), following [SECURITY.md](../SECURITY.md). Authentication inputs and
Android signing material must remain outside Git. The app signing key is separate
from the CarPlay accessory identity.
