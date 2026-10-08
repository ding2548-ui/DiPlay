# Side panel next to CarPlay (experimental)

CarPlay can share the head unit's main screen with DiPlay: the iPhone draws CarPlay in two thirds of the screen while DiPlay shows its own panel in the rest. On a landscape screen CarPlay stays on the driver's side and the panel is the passenger's third (the right in a left-hand-drive car, the left with **Right-hand drive** on); on a portrait screen the panel is a band at the bottom. The car switches between this and full-screen CarPlay at any time without reconnecting. It is off by default (**Settings → Display and performance → Side panel next to CarPlay**); once on, the in-session menu has a **Side panel** button, and the panel has a **Full screen** button.

## How it works

The panel builds on the view areas described in [VIEW_AREAS.md](VIEW_AREAS.md). With the setting on, the main display also declares, for each screen orientation and dock edge, an area with two thirds of the screen: the left of a landscape screen, the top of a portrait one. The **Side panel** button moves CarPlay there with `updateViewArea`; DiPlay lays out the canvas by the whole screen and an ordinary Android view covers the strip of the stream CarPlay leaves black, mapped through the video's layout, so it stays on that strip when the video is letterboxed. Touches on the panel stay in DiPlay; touches elsewhere go to the iPhone as before. A window change (a turn, the head unit's split screen) ends the panel and picks the matching area as usual. Closing DiPlay or ending the session hides the panel and stops its refresh.

CarPlay has no notion of the panel's contents; the iPhone only knows the car asked it to use a smaller area.

Panel placement uses physical video coordinates, so switching the UI to a right-to-left language does not move it over CarPlay. When a new host Activity adopts a live session with the panel open, it restores the panel and its refresh callback without changing the view area.

## Tested

2024 BYD Tang (DiLink 5.0, 2560×1440 rotating centre screen) with an iPhone on iOS 27, over wireless CarPlay (lab build of the same design): CarPlay moved to the left two thirds (landscape) or the top two thirds (portrait) and back within a fraction of a second, without reconnecting; touches in CarPlay's area landed correctly; the panel showed a clock and the car's battery and range. With an automatic dock, CarPlay puts its dock at the bottom once several areas are declared; the dock setting places it.

## What it could become

The panel is an Android view, so it can show anything DiPlay can read or do: vehicle data over the head unit's adb (climate, tyre pressure, doors), controls for the car (for example the climate fids documented for BYD's autoservice), widgets, shortcuts to BYD apps, or the map from another source. With a few more pieces (a wheel key or an automatic rule when parked to show the panel, a layout per screen size, a choice of panel side), DiPlay could act as a launcher around CarPlay rather than a full-screen projection only.

## Other findings from the same research

Tried in the same lab build on the same car, using parameter definitions from Apple's iAP2 message spec in Xcode's CarPlay Simulator (`iap2messages-internal.i2mspecarchive`, read as data):

- **Vehicle status extras:** declaring OutsideTemperature, InsideTemperature, WiperStatus, BarometricPressure, Alerts and PassengerSeatStatus in the VehicleStatus component is accepted; the iPhone then subscribes to outside temperature, wipers, pressure, alerts and passenger seat (not inside temperature). Live values from the car (outside temperature, wipers, hazard lights) were sent and accepted, but nothing visible changed in CarPlay or Apple Maps.
- **Road object detection** (identification param 33, for a car's camera to report signs, lanes and objects): the iPhone rejects the identification (`IdentificationRejected` for 0x0021), presumably reserved for approved vehicles.
- **App discovery** (`StartAppDiscoveryUpdates` for all CarPlay apps with icons): the iPhone answers `AppDiscoveryUpdate` with `CarPlayAppListAvailable = 0` (Unknown) and no list.
