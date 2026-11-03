package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.LmAmbientMode

/**
 * Which interior-lamp transport the controller drives.
 *
 * The two car lines need different transports for the same feature: BYD exposes the lamp through a
 * privileged ADB shell, Leapmotor consumes a broadcast on its shared CAN action. Both cars run the
 * same source tree, so the transport is *probed at runtime* (`LmCarProbe`): a Leapmotor unit selects
 * [LEAPMOTOR_BROADCAST] and never starts the ADB worker, every other unit keeps [BYD_ADB] exactly as
 * before. See [AmbientMusicController.lampChannel].
 */
internal enum class AmbientLampChannel { BYD_ADB, LEAPMOTOR_BROADCAST }

/**
 * How the Leapmotor flow light colours the car while music follows it. The OEM engine drives one
 * band per channel; we keep the same rule for [MULTI] and hold a single user colour for [SINGLE].
 *
 * Public because `AmbientMusicSettings.Values` — which is public — carries one, and a public type
 * must not expose an `internal` one.
 */
enum class AmbientFlowMode { SINGLE, MULTI }

/**
 * The lamp state the Leapmotor protocol understands. The BYD path works in
 * `(colorIndex 1..31, brightness 1..6, area 1..3)`; the Leapmotor path works in
 * `(mode, brightness 0..10, true RGB)`. `tick()` produces a BYD-shaped triple for the first and a
 * [LeapmotorLamp] for the second, and each backend ignores the shape it does not use.
 */
internal data class LeapmotorLamp(val mode: LmAmbientMode, val deg: Int, val rgb: Int)

/** What the controller hands its lamp transport on every tick. */
internal data class AmbientLampFrame(val area: Int, val colorIndex: Int, val brightness: Int,
    val leapmotor: LeapmotorLamp?)

/**
 * One lamp transport. Deliberately small: the controller owns pacing and decision-making, the
 * transport owns talking to the car.
 */
internal interface AmbientLampSink {
    /** Called at most once per tick, already paced by the controller. */
    fun render(frame: AmbientLampFrame)

    /**
     * Hands the lamps back to the car's own control.
     *
     * [lampDeg] is the user's configured brightness on the OEM 0..10 scale, for transports that
     * carry one. A transport must not substitute a protocol default here: that would silently
     * overwrite the setting the car (or the driver) already had.
     */
    fun restore(lampDeg: Int)

    /**
     * True once control is established, so [AmbientMusicController.checkSupport] can answer without
     * re-probing. A broadcast needs no handshake, so the Leapmotor sink is always ready.
     */
    fun isReady(): Boolean = true

    /** Rebuilds the transport after a failed tick. A stateless broadcast has nothing to rebuild. */
    fun recover() {}
}
