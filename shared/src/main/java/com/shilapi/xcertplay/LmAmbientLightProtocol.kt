package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent

/**
 * Leapmotor ambient-light ("氛围灯") CAN protocol, reverse-engineered from the head unit's
 * `VehicleSetting.apk` (`com.leapmotor.vehiclesetting`).
 *
 * The app never talks to the CAN bus directly: it broadcasts an intent on the shared car-control
 * action with `appCAN = "Atmos_ctrl"` and six int extras. The head unit / ICU consumes it; there is
 * no reply channel, so our own preferences stay the source of truth.
 *
 * Evidence: `vehiclecontrol/util/CarControlMessageOts.smali:555-616` (`SendAtmosPhere2Can`).
 */
internal object LmAmbientLightProtocol {
    /** Shared with every other car-CAN control in `VehicleSetting.apk`. */
    const val ACTION = "leapmotor.carapp.CARCANCONTROL"

    /** Routes the broadcast to the ambient-light handler. */
    const val APP_CAN = "Atmos_ctrl"

    /** `lampSW` values. Note "off" is 2, not 0 (see `LampFragment$4`, `Tools.smali:615/625/635`). */
    const val SW_AUTO = 0
    const val SW_ON = 1
    const val SW_OFF = 2

    /** `lampAuto`: paired with [SW_AUTO]. */
    const val AUTO_ON = 1
    const val AUTO_OFF = 0

    const val DEG_MIN = 0
    const val DEG_MAX = 10
    const val DEG_DEFAULT = 5

    /** The module-wide default colour: `R=0x14 G=0xff B=0x14` (green). */
    const val DEFAULT_R = 0x14
    const val DEFAULT_G = 0xff
    const val DEFAULT_B = 0x14

    data class Params(val sw: Int, val auto: Int, val deg: Int, val r: Int, val g: Int, val b: Int)

    fun valid(params: Params): Boolean =
        params.sw in SW_AUTO..SW_OFF &&
            params.auto in AUTO_OFF..AUTO_ON &&
            params.deg in DEG_MIN..DEG_MAX &&
            params.r in 0..255 && params.g in 0..255 && params.b in 0..255

    /** Builds the broadcast the way `SendAtmosPhere2Can` does — same action, extras and names. */
    fun intent(params: Params, packageName: String? = null): Intent {
        require(valid(params)) { "invalid-ambient-params" }
        val intent = Intent(ACTION)
        intent.putExtra("appCAN", APP_CAN)
        intent.putExtra("lampSW", params.sw)
        intent.putExtra("lampAuto", params.auto)
        intent.putExtra("lampDeg", params.deg)
        intent.putExtra("lampR", params.r)
        intent.putExtra("lampG", params.g)
        intent.putExtra("lampB", params.b)
        if (packageName != null) intent.setPackage(packageName)
        return intent
    }

    /** Convenience for the "on/off/auto" switch, matching the enums the settings UI already has. */
    fun switchParams(mode: LmAmbientMode, deg: Int, rgb: Int): Params = when (mode) {
        LmAmbientMode.OFF -> Params(SW_OFF, AUTO_OFF, deg, (rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)
        LmAmbientMode.ON -> Params(SW_ON, AUTO_OFF, deg, (rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)
        LmAmbientMode.AUTO -> Params(SW_AUTO, AUTO_ON, deg, (rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)
    }

    /**
     * The OEM **multi-colour** flow rule (`VehicleControlService$6`, the `musicColorMode == 2`
     * branch): each band drives one colour channel (`lampR=|f0|*2`, `lampG=|f1|*2`, `lampB=|f2|*2`)
     * while the angle keeps the user's stored `fendeng`:
     *
     * ```
     * SendAtmosPhere2Can(ctx, sw, auto, atmosphere.GetDeg(), |f0|*2, |f1|*2, |f2|*2)
     * ```
     *
     * The OEM's `f0/f1/f2` are raw band magnitudes in `0..127`, which is why the same block doubles
     * for the colour channels. [bass]/[mid]/[high] here are the same bands normalised to `0..1`, so
     * the colour formula becomes `v*127*2` — i.e. `v*254`, already inside the protocol's range.
     * Scaling by 255 would saturate every channel as soon as the band passed half scale.
     *
     * [deg] is the user's own `lampDeg` (0..10) and is **constant** here: the OEM multi-colour
     * branch never derives the angle from the spectrum. Only the single-colour branch does
     * (`|f0|*10/128`, falling back to `GetDeg()` when that rounds to 0) — see
     * `LmAmbientMusicFlow.singleColour`.
     *
     * [mode] supplies SW/Auto only, exactly as the OEM engine overrides them from the stored
     * `atmosphere` value; OFF is never produced here because the flow light only runs while on.
     */
    fun flowParams(mode: LmAmbientMode, bass: Double, mid: Double, high: Double, deg: Int): Params {
        fun band(value: Double) = (value.coerceIn(0.0, 1.0) * 127.0 * 2.0).toInt().coerceIn(0, 255)
        val level = deg.coerceIn(DEG_MIN, DEG_MAX)
        val active = if (mode == LmAmbientMode.AUTO) LmAmbientMode.AUTO else LmAmbientMode.ON
        return when (active) {
            LmAmbientMode.AUTO -> Params(SW_AUTO, AUTO_ON, level, band(bass), band(mid), band(high))
            else -> Params(SW_ON, AUTO_OFF, level, band(bass), band(mid), band(high))
        }
    }
}

/** The head unit's own 关/开/自动 three-state switch. */
internal enum class LmAmbientMode { OFF, ON, AUTO }

/** Sends the ambient-light broadcast. Stateless and cheap; the controller does the pacing. */
internal class LmAmbientLightClient(private val context: Context) {
    private val app = context.applicationContext

    /** Returns false when the platform refuses the send (never expected on the head unit). */
    fun send(params: LmAmbientLightProtocol.Params): Boolean = try {
        app.sendBroadcast(LmAmbientLightProtocol.intent(params))
        true
    } catch (error: Throwable) {
        AmbientDiagnostics.warn("leapmotor ambient send failed: ${error.javaClass.simpleName}", error)
        false
    }
}
