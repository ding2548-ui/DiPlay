# CarPlay view areas: dock position, split screen and a turning screen

A CarPlay accessory may declare several **view areas** inside the main screen's video stream and tell the iPhone which one to draw in. DiPlay uses this for three optional settings, all without reconnecting CarPlay:

- **CarPlay dock** (Settings → Display and performance, and the in-session menu): Automatic (default), Driver's side or Bottom.
- **CarPlay in the head unit's split screen** (Settings → Display and performance, off by default): when the head unit shows DiPlay in half of the screen, CarPlay redraws for that window instead of shrinking with black bars, and returns to full screen.
- **Turn CarPlay with the screen without reconnecting** (Settings → Display and performance, off by default): for head units whose screen turns, CarPlay redraws in the other orientation instead of reconnecting.

## How it works

- `/info` lists the main display's `viewAreas`: rectangles of the stream (`widthPixels`, `heightPixels`, `originXPixels`, `originYPixels`) with their safe areas. With more than one, DiPlay adds `viewAreaTransitionControl = true`. Apple's own widescreen profile in Xcode's CarPlay Simulator declares two (the whole 1920×720 and the right 1280×720).
- A view area may carry `viewAreaStatusBarEdge`, which places CarPlay's dock. CarPlay Simulator's StatusBarEdge enum has automatic, bottom and driver. On a Tang, `1` put the dock at the bottom and `2` on the driver's side.
- The car switches areas on the event channel with `updateViewArea {uuid, viewAreaIndex, animationDurationMillis, adjacentViewAreas}`. The key names sit next to `AirPlayReceiverSessionViewAreaUpdate` in CarPlaySDK's strings. The iPhone acted on it only with the animation duration and the adjacent areas.
- The iPhone keeps streaming the whole frame. DiPlay lays out the canvas so that the area in use fills its window, and touches follow the same rectangle.

DiPlay declares:

| Settings | Areas |
|---|---|
| Automatic dock, no split screen | the whole screen as one area (as before) |
| Fixed dock | the whole screen once per edge (driver's side, bottom) |
| Split screen | the above, plus an area the size of DiPlay's split-screen window, once per dock edge |

Moving the dock between the fixed edges switches to the same kind of area with the other edge. Entering or leaving the split screen switches between the whole-screen and split-screen areas with the same edge. Other window changes (a turn without the turning-screen setting, camera windows, floating windows of another shape) keep their existing handling.

The split-screen window is remembered per screen orientation as a fraction of the full window. BYD shows its status and navigation bars in split screen, so DiPlay's window is smaller than half the screen (on a Tang 1270×1208 of 2560×1440, or 1440×1154 of 1440×2560 when split top and bottom). The first time, DiPlay uses half the screen and learns the real size; from the next connection the area matches exactly. A session that starts while DiPlay is already in split screen sizes its canvas to that window and keeps one area.

With any second area declared, CarPlay with an Automatic dock puts the dock at the bottom; choose Driver's side to keep it there.

## Turning screen

The stream's size is fixed for a session, so a screen that turns from 2560×1440 to 1440×2560 normally means a new connection. With the setting on, DiPlay asks for a square canvas instead, with the screen's two orientations as view areas anchored at its top-left corner:

- a landscape area across the top (for example 2560×1440 of 2560×2560);
- a portrait area down the left (1440×2560).

When the screen turns, DiPlay moves CarPlay to the other area and lays out the canvas so it fills the window, without reconnecting. Split-screen areas for each orientation and the dock edges combine with it.

- **Pixel density:** the square keeps the plain canvas's density, so CarPlay's scale (and the "CarPlay size" setting) is the same in both orientations.
- **Safe area:** the custom safe area does not apply to the square.
- **Cost:** a square is heavier to encode and decode. "Sharper" asks for the screen's long side (as large as the selected decoder takes). "Smoother" caps it at 1920: on a Tang, 2560 ran at about 30–40 frames per second, 1920 at up to 60, with a slightly softer picture. An unsupported default hardware decoder keeps the plain canvas; another decoder's capability cannot silently force software decoding. An explicitly selected software HEVC decoder is checked instead when that existing option is enabled.
- **Starting in split screen:** a session that starts there keeps the plain canvas.

## Tested

2024 BYD Tang (DiLink 5.0, 2560×1440 centre screen) with an iPhone on iOS 27, wireless CarPlay:

- **Dock:** Automatic → Bottom reconnected. Bottom ↔ Driver's side moved at once, from Settings and from the in-session menu.
- **Split screen:** with BYD's split screen (DiPlay left, BYD media right, and top/bottom on the portrait screen), CarPlay filled DiPlay's half at its proportions and returned to full screen, without reconnecting. Touches worked in both.
- **Turning screen:** a lab build of the same design turned CarPlay with BYD's turning screen in both directions without reconnecting, also while in split screen, at 2560 and 1920.
