package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.LmAmbientLightProtocol
import com.shilapi.xcertplay.LmAmbientMode
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Turns played audio energy into the Leapmotor flow-light broadcast, reusing the envelope the BYD
 * path already fills instead of a second spectrum service.
 *
 * 颜色永远来自用户的选择：SINGLE 固定显示选中色，MULTI 显示色板推进到的当前色。原厂
 * `musicColorMode == 2` 把频谱幅度当颜色（`lampR=|f0|*2`…），会显示用户没选过的颜色、
 * 看起来就是随机变色，所以这里刻意不复刻。
 *
 * 亮度是这里唯一动的量（且只在 SINGLE 下动）：包络提供 200ms 播放窗口，低频段相对自身
 * 衰减峰值归一化后再缓动；不做归一化的话原始 PCM RMS 只有 0.05..0.3，而 `lampDeg` 是
 * 0..10 的档位，灯带会在近黑和过曝之间每帧乱跳。
 */
internal class LmAmbientMusicFlow(private val mode: () -> LmAmbientMode = { LmAmbientMode.ON }) {
    private var bassPeak = 0.05
    private var smoothed: LmAmbientLightProtocol.Params? = null
    private var previousTime: Long? = null

    /** Drops the smoothing state so a re-enabled feature does not fade in from the last session. */
    fun reset() {
        smoothed = null
        previousTime = null
        bassPeak = 0.05
    }

    /**
     * @param energy played window from [AmbientMusicEnvelope]; zero energy yields the idle look.
     * @param idleColor the RGB held while nothing is playing, taken from the user's colour choice.
     * @param flowMode [AmbientFlowMode.SINGLE] 固定 [idleColor] 只让亮度随低频动；
     *   [AmbientFlowMode.MULTI] 显示 [idleColor]（色板当前色）且亮度固定为用户档位。
     *   两者颜色都来自用户选择，绝不发频谱 RGB。
     * @param flowBrightness the user's `lampDeg` 0..10: the constant level in MULTI, the floor of the
     *   sweep in SINGLE.
     */
    fun params(energy: AmbientMusicEnvelope.Energy, nowMillis: Long, idleColor: Int,
        flowMode: AmbientFlowMode = AmbientFlowMode.MULTI,
        flowBrightness: Int = LmAmbientLightProtocol.DEG_DEFAULT): LmAmbientLightProtocol.Params {
        val elapsed = previousTime?.let { (nowMillis - it).coerceAtLeast(0) } ?: 0
        previousTime = nowMillis
        val decay = exp(-elapsed / 4_000.0)
        bassPeak = maxOf(0.05, energy.bassRms, bassPeak * decay)
        if (energy.rms < SILENCE_RMS) {
            val resting = idle(idleColor, flowBrightness)
            // Seed the easing state with what the lamp actually shows now, otherwise the first
            // audible frame after a quiet passage fades in from a stale pre-silence colour.
            smoothed = resting
            return resting
        }
        val bass = normalize(energy.bassRms, bassPeak)
        val resolved = active()
        val target = if (flowMode == AmbientFlowMode.SINGLE)
            singleColour(resolved, bass, idleColor, flowBrightness)
        else multiColour(resolved, idleColor, flowBrightness)
        return ease(target, elapsed)
    }

    /**
     * The switch state every frame of this flow carries.
     *
     * It must be the same on a silent frame and on an audible one: the sink de-duplicates on the full
     * parameter set, so a silent frame that resolved to `AUTO` while an audible frame resolved to
     * `ON` would re-send the broadcast 20 times a second, toggling the car's switch with it.
     *
     * `OFF` is never sent from here — handing the lamps back is [AmbientLampSink.restore]. Anything
     * that is not an explicit `AUTO` becomes `ON`, because that is the state that guarantees the
     * strip is actually lit: in `AUTO` the car decides by ambient light, so a daytime drive would
     * show nothing and read as "the music light does not work".
     */
    private fun active(): LmAmbientMode =
        if (mode() == LmAmbientMode.AUTO) LmAmbientMode.AUTO else LmAmbientMode.ON

    /**
     * One colour, music-driven brightness — the OEM **single-colour** rule
     * (`musicColorMode == 1`: `lampDeg = |f0|*10/128`, falling back to `GetDeg()` when that rounds
     * to 0). The RGB stays exactly what the user picked and the low band sweeps the brightness level
     * from the user's floor up to full, so a quiet passage rests at that floor instead of going
     * dark. This is the Leapmotor counterpart of the BYD "fixed colour" path.
     *
     * `lampDeg` is the 0..10 **brightness** level, not an angle (the angle is the separate
     * `angledeng` headlight-levelling channel, which this protocol does not carry).
     */
    fun singleColour(mode: LmAmbientMode, bass: Double, idleColor: Int,
        flowBrightness: Int = LmAmbientLightProtocol.DEG_DEFAULT): LmAmbientLightProtocol.Params {
        val floor = flowBrightness.coerceIn(LmAmbientLightProtocol.DEG_MIN, LmAmbientLightProtocol.DEG_MAX)
        val level = (bass.coerceIn(0.0, 1.0) * (LmAmbientLightProtocol.DEG_MAX - floor) + floor).toInt()
            .coerceIn(LmAmbientLightProtocol.DEG_MIN, LmAmbientLightProtocol.DEG_MAX)
        return LmAmbientLightProtocol.switchParams(mode, level, idleColor)
    }

    /**
     * 多色流光（用户语义，非原厂复刻）：颜色只来自调用方传入的 [color]（= 选中色板里当前那一色，
     * 由 `AmbientMusicController` 按「颜色变化方式」推进），亮度固定为用户档位。绝不发频谱 RGB——
     * 原厂 `musicColorMode == 2` 的 `lampR=|f0|*2…` 会显示用户从未选过的颜色，看起来就是随机变色。
     */
    fun multiColour(mode: LmAmbientMode, color: Int,
        flowBrightness: Int = LmAmbientLightProtocol.DEG_DEFAULT): LmAmbientLightProtocol.Params {
        val deg = flowBrightness.coerceIn(LmAmbientLightProtocol.DEG_MIN, LmAmbientLightProtocol.DEG_MAX)
        return LmAmbientLightProtocol.switchParams(mode, deg, color)
    }

    /**
     * The resting state: the user's colour at their chosen brightness, only ever sent while enabled.
     * Both flow modes rest the same way — the mode only decides what happens to an audible frame.
     */
    fun idle(idleColor: Int,
        flowBrightness: Int = LmAmbientLightProtocol.DEG_DEFAULT): LmAmbientLightProtocol.Params {
        val deg = flowBrightness.coerceIn(LmAmbientLightProtocol.DEG_MIN, LmAmbientLightProtocol.DEG_MAX)
        return LmAmbientLightProtocol.switchParams(active(), deg, idleColor)
    }

    /**
     * Perceptual easing on each colour channel. The brightness level follows immediately: it is the
     * OEM engine's audio-driven term and a lag there is visible as the light strip sweeping late.
     */
    private fun ease(target: LmAmbientLightProtocol.Params, elapsed: Long): LmAmbientLightProtocol.Params {
        val previous = smoothed
        val eased = if (previous == null || elapsed <= 0) target else target.copy(
            r = easeChannel(previous.r, target.r),
            g = easeChannel(previous.g, target.g),
            b = easeChannel(previous.b, target.b),
        )
        smoothed = eased
        return eased
    }

    private fun easeChannel(from: Int, to: Int): Int {
        // Fast enough to track a beat, slow enough that a 200ms step does not read as a flicker.
        val factor = 0.45
        return (from + (to - from) * factor).toInt().coerceIn(0, 255)
    }

    /** Maps a raw band magnitude onto 0..1 using its own peak, with a square-root response curve. */
    private fun normalize(value: Double, peak: Double): Double {
        val raw = (if (value.isFinite()) value else 0.0).coerceIn(0.0, 1.0)
        if (raw < SILENCE_RMS) return 0.0
        return sqrt((raw / peak).coerceIn(0.0, 1.0))
    }

    private companion object {
        /** Below this the band is treated as silence rather than "very quiet music". */
        const val SILENCE_RMS = 0.008
    }
}
