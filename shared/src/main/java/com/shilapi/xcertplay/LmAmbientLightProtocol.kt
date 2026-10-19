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

    /*
     * 原厂 `musicColorMode == 2`（`VehicleControlService$6`）把频谱幅度直接当颜色
     * （`lampR=|f0|*2, lampG=|f1|*2, lampB=|f2|*2`）。这会让灯带显示用户从未选过的颜色，
     * 看起来就是「随机变色」，所以这里刻意不复刻：两种流光模式都只显示用户选择的颜色，
     * 由 `LmAmbientMusicFlow` 决定亮度怎么动。
     */
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
