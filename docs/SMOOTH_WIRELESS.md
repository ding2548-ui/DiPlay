# Smooth wireless CarPlay

The Wi-Fi channel and the size of the picture can affect how smooth wireless CarPlay feels. The numbers below were measured on a 2024 BYD Tang (DiLink 5.0, 2560×1440 screen) with an iPhone on iOS 27, parked, while scrolling the same Apple Music list. Other cars, firmware and phones may behave differently; interference, scanning and decoder limits can also contribute.

This guide describes 0.2.13 channel-policy and rotation behavior. Wi-Fi Direct is available on Android 9+ with suitable firmware. Android 9 uses the legacy group/channel path and cannot verify the negotiated frequency; see [Android 9 limits](ANDROID9_WIFI_DIRECT.md). Android 10+ retains actual-frequency verification. The contributor measurements below describe their specified Tang setup, not every device.

**Use the built-in car hotspot if your car has one.** Wi-Fi Direct needs the car's Wi-Fi switch on, and while the car is not joined to a network it searches for one, every 10 s in reports from DiLink 4.0 and 5.0 cars. Each search takes the car's single radio off the CarPlay channel; audio packets sent meanwhile are lost, so music and navigation voice stutter on that rhythm whatever channel you choose, and a larger music buffer cannot bring them back. The car hotspot runs with the car's Wi-Fi client off: on the same cars it lost no audio, or almost none. With network ADB already approved, DiPlay pauses this search during Wi-Fi Direct sessions on Android 7.1 and later; see [Compatibility](COMPATIBILITY.md).

## 1. Wi-Fi channel

**Settings → Connection setup → Wi-Fi Direct → Preferred channel**

On this Tang, running Wi-Fi Direct on a different 5 GHz channel from the car's joined Wi-Fi network caused:

- the picture stopping for 0.6–2 s at a time;
- music stuttering.

A 2.4 GHz Wi-Fi Direct channel alongside the 5 GHz network was smooth in this test. The result is consistent with radio channel contention, but does not establish the same radio capabilities or cause on every head unit.

- **Auto** (the default) can reuse a compatible saved configuration. Beside an established 5 GHz station connection, it prioritizes an eligible saved frequency or a supported matching station channel, followed by explicit 2.4 GHz attempts before other 5 GHz attempts. An unpinned saved system-default request is deferred in that case. If explicit attempts are rejected, later 5 GHz or system-default fallbacks remain possible; Auto cannot guarantee a band or eliminate contention. Without a station connection, a compatible saved configuration may still be tried before the usual 5 GHz-first fallback order. See the diagnostic report for the verified frequency on Android 10+, or the explicitly unverified requested channel on Android 9.
- If you choose a channel by hand and the car joins a 5 GHz network, try a supported 2.4 GHz channel (1–11), or go back to Auto. Firmware and local regulatory limits still apply.

## 2. Picture size and screen rotation

**Settings → Display and performance → Turn CarPlay with the screen without reconnecting**

Larger pictures can increase encoding, transmission and decoding work. In this test, DiPlay's received and rendered frame rates were close in all three cases, while the larger square arrived at a lower rate. This suggests testing picture size, but does not by itself identify the iPhone encoder as the bottleneck or rule out network effects and backpressure.

| Setting | Picture | Pixels per frame | Frames per second while scrolling |
|---|---|---|---|
| Rotation **off** | 2560×1440, the screen's own size | 3.7 million | 45–57 |
| Rotation on, **Smoother (1920)** | 1920×1920 square | 3.7 million | 49–57 |
| Rotation on, **Sharper (the screen's size)** | 2560×2560 square | 6.6 million | 32–44 |

With rotation on, CarPlay gets a square picture that holds both a landscape and a portrait screen. Turning the screen then redraws CarPlay without reconnecting. A 1920 square has exactly as many pixels as a 2560×1440 screen and had a similar frame rate here; equal pixel counts do not guarantee equal performance. It is scaled up on this screen, so text can look softer. A 2560 square has about 1.8 times as many pixels and had a lower frame rate in this test. The actual square size also depends on the selected decoder's capabilities.

**What to choose:**

- **Your screen never turns, or you rarely turn it:** try leaving rotation **off**. That felt smoothest on this Tang and avoids scaling a smaller square up to its screen. If you do turn the screen, CarPlay reconnects at the new size, which took about 10 s on the Tang.
- **You turn the screen often:** turn rotation on and keep **Smoother (1920)**, the default.
- **Sharper (the screen's size):** on a screen wider than 1920 pixels it can request a larger supported square and may reduce smoothness. At 1920 pixels or below, both options have the same size limit.

## 3. Smooth video (experimental)

**Settings → Display and performance → Smooth video (experimental)**, off by default.

By default DiPlay shows each frame as soon as the decoder releases it. On the Tang, the decoder's output timing depended on later input:

- **The decoder held frames.** `c2.qti.avc.decoder` released a frame only after about two more had been queued. With the setting off (runs A1 and A2 below, about 56 fps), queue-to-output time (`decode p50/p90`) was 48–56 ms (median) and 69–77 ms (p90) in most 5 s windows, and on a still screen the last frame came out only with the next one.
- **The iPhone's stream does not ask for that.** Its SPS signals `max_num_reorder_frames` 0, and the frames had no B slices.
- **No decoder setting changed it.** These were tried:
  - `KEY_LOW_LATENCY` (the decoder does not advertise it);
  - `vendor.qti-ext-dec-picture-order.enable`;
  - `vendor.qti-ext-dec-timestamp-reorder.value` 0;
  - Constrained High flags in the SPS.

The iPhone stamps each frame with its own time in the screen header. On the Tang at 60 fps these times fell on a 1/60 s grid, and frames the iPhone skipped left gaps of whole multiples. With this setting, DiPlay:

- renders the main screen to a `SurfaceView`;
- maps that time onto the head unit's clock, adding the link's base delay: a low percentile of recent arrivals (frame time to arrival). For the first 30 frames the base follows that percentile at once. After that it moves at most 2 ms per second, unless it rises by more than 0.5 s or falls by more than 100 ms, when it jumps to the new value;
- releases each frame with `releaseOutputBuffer(index, timestampNs)` at that local time plus a display delay.

The display delay adjusts itself. For each frame it can time, DiPlay notes how long after its local time the decoder released it. Frames released only after a pause in the iPhone's frames are not counted: the last three frames before a gap of more than 120 ms between consecutive iPhone frame times, since the decoder holds about two, and frames queued before a still screen of more than 0.5 s. Every 15 counted frames, the delay's goal is set to the 90th percentile of the last 120 counted frames plus a 20 ms margin, kept between 30 and 200 ms, aiming for about nine in ten frames ready in time. The margin is a refresh plus 4 ms because SurfaceFlinger takes a buffer about one refresh before the vsync it is shown at (measured below). It rises by at most 1 ms per frame and falls by at most 0.5 ms per frame, so a change spreads over many frames instead of shifting every later frame at once. It starts at three frame intervals of the frame-rate setting plus 40 ms: 90 ms at 60 fps, 140 ms at 30 fps. Frames that still leave the decoder after their time are shown at once and counted as `late`, and the stats line shows the current `delay`.

**Measured on my Tang with a fixed 90 ms delay**, with an earlier version of this change that set up its own `SurfaceView` before the current one existed, and before the delay adjusted itself (USB, 2560×1440 at 60 fps, alternating off/on captures of 41–53 s while scrolling on and off, `rx` about 56 fps while scrolling). Intervals come from `dumpsys SurfaceFlinger --latency` for the video layer (the app window when off, the `SurfaceView` when on), counting only seconds with at least 40 presented frames:

| Run | Smooth video | Seconds counted | Next frame 1 refresh later | 2 refreshes later | 3 or more | Presented fps |
|---|---|---|---|---|---|---|
| A1 | off | 9 | 63.6% | 32.5% | 3.9% | 42.4 |
| B1 | on | 29 | 86.0% | 12.4% | 1.5% | 51.2 |
| A2 | off | 31 | 63.8% | 31.4% | 4.8% | 42.1 |
| B2 | on | 37 | 83.4% | 14.2% | 2.5% | 50.0 |

- With the setting on, more of the received frames reached the screen and more of them came one refresh apart. This is consistent with the decoder's bunched output being spread back onto the iPhone's grid; it was not measured separately how much the `SurfaceView` alone contributes. An earlier USB run with a `SurfaceView` and no pacing, counted the same way (8 seconds), gave 63.4% at one refresh, close to the off runs.
- With the fixed 90 ms, in the 5 s windows of the on runs where `rx` was above 53 fps, `late` was 36–75 (about 13–26% of the frames received), so a share of frames still left the decoder after their time.
- The main screen sometimes arrived at a steady 30 fps for over a minute while the setting was 60 fps. Over USB right after run B2 (no touches), `rx` was 29–34 fps and `late` was 49–112 per 5 s (about 31–75% of the frames received). In a later wireless session (car hotspot, while I switched between CarPlay apps, the map among them), `late` was 98–128 per 5 s (about 62–85%). So for those stretches most frames were released as soon as they left the decoder, as with the setting off; what reached the screen was not captured then. This is why the delay now adjusts itself.
- When DiPlay goes to the background, the main-screen decoder moves to an offscreen surface and keeps its state, and on return DiPlay asks the iPhone for a new keyframe. In the car the picture came back at once, with a short blink.

**Measured on my Tang with the adjusting delay and a 4 ms margin**, the first version of the adjusting delay (car hotspot, 2560×1440 at 60 fps, one session of about 3 minutes: lists, the map, lists again, background and back; not an A/B run):

- `SurfaceView` layer in `dumpsys SurfaceFlinger --latency`, seconds with at least 40 presented frames (108 s, nearly all lists): the next frame came 1 refresh later for 88.7% of frames, 2 refreshes later for 9.1%, and 3 or more for 1.7%; 51.2 presented fps.
- Per 5 s window of the main screen:

| Content | Windows | `rx` | `delay` | `late`, share of frames received |
|---|---|---|---|---|
| Lists | 21 | 43–60 fps | 76–116 ms, one window 190 ms | median 8%, 18 windows 3–12%, the others 15%, 20% and 35% |
| Map | 9 | 25–41 fps | 97–155 ms | median 16%, 10–34% |

- On the map the iPhone sent frames in bursts, with gaps of up to about 0.5 s (`maxGap` 468–488 ms in every window), so the share at one refresh does not describe it.
- The 190 ms window followed a still screen of about a second while I switched apps; it is also the window with 35% `late`.
- This build still counted frames held over a pause in the iPhone's frames as `late`. The build in this change leaves them out, so its `late` reads lower for the same picture.
- This session used the car hotspot and the fixed-delay table used USB, on a different run, so the two are not a like-for-like comparison.

**Margin, measured on my Tang** (car hotspot, 2560×1440 at 60 fps; one connection per run, about 1–1.5 minutes of scrolling each, plus the map in the first five; runs in the order 4, 12, 20, 4, 20 ms, then two more at 20 ms without the map). For each run, every frame the app released was matched to SurfaceFlinger's record by its timestamp, so frames SurfaceFlinger dropped are counted too. Seconds with at least 40 presented frames only; two separate analyses of the same data agreed within about 1 percentage point on these figures:

| Margin | Runs | Next frame 1 refresh later | `late` | Missed their refresh: late, presented a refresh late, or dropped | Delay, median |
|---|---|---|---|---|---|
| 4 ms | 2 | 88.7–89.2% | 6.8–7.4% | 15–18% | 86–92 ms |
| 12 ms | 1 | 92.6% | 4.0–4.2% | 8–9% | 98–99 ms |
| 20 ms | 4 | 90.5–94.5% | 1.9–3.3% | 5–8% | 108–113 ms |

- Every frame released on time but less than about 15.75 ms before the vsync after its target missed that vsync; at 16–16.5 ms before it, about a quarter to a third did; from about 17 ms on, 1–6%. That is consistent with SurfaceFlinger taking the buffer one refresh ahead, and it is why a 4 ms margin left so many frames short.
- The 12 ms margin has only one short run, so it is not clear whether it differs from 20 ms on the screen; 20 ms missed fewer frames in every run.
- The two analyses split the missed frames between "presented late" and "dropped" differently, so only their sum is given.

**Cost and limits:**
- Each frame is held until its display time, up to about the delay after it arrives, so touches respond later. The added touch-to-screen delay was not measured.
- Picture adjustments do not apply, because the video no longer passes through the view they are applied to.
- These runs were made on one car, parked, with one decoder. Other decoders were not tried.

## 4. Checking it yourself

While CarPlay runs, DiPlay logs a `DiPlay-VideoStats` line every 5 s:

```
video stats rx=56.2fps shown=56.4fps maxGap=73ms kbps=28899 ...
```

- `rx` counts frames received by DiPlay; `shown` counts decoder outputs released for rendering to its surface. These are windowed counters, not proof of every frame the phone sent or every physical display refresh.
- If `shown` keeps up with `rx` but `rx` is low while you scroll, compare picture settings and the Wi-Fi link. The counters alone cannot distinguish phone encoding, transport delays or backpressure, and do not rule out every head-unit problem.
- `decode p50/p90` is the time from queueing a frame into the decoder to dequeueing its output. Frames queued before an input gap of more than 0.5 s (a still screen) are left out of both `decode p50/p90` and `late`. With smooth video on, `late` counts frames shown at once because they left the decoder after their display time, or had no usable display time; frames held over a pause in the iPhone's frames are left out too, since no delay could have hidden them. `delay` is the display delay at the last paced frame.
- `maxGap` is the largest interval between received frames shorter than 2 s; longer intervals are excluded. A static screen may also produce gaps because the iPhone need not send new frames. A single large value is not evidence of a stall by itself.
