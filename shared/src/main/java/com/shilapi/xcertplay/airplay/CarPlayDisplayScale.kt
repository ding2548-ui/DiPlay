package com.shilapi.xcertplay.airplay

/**
 * Display scaling exposes the legacy 0.3x..1.0x tenths steps and the custom resolution setting,
 * whose integer percentages run from 30% up to 160% so a larger stream than the panel can be
 * negotiated and downscaled on the display.
 */
object CarPlayDisplayScale {
    const val MIN_TENTHS = 3
    const val MAX_TENTHS = 10
    const val DEFAULT_TENTHS = MAX_TENTHS

    /** Custom resolution percentages accepted by the settings screen and the in-session menu. */
    const val MIN_PERCENT = 30
    const val MAX_PERCENT = 160

    fun sanitize(tenths: Int): Int = tenths.coerceIn(MIN_TENTHS, MAX_TENTHS)

    fun label(tenths: Int): String {
        val value = sanitize(tenths)
        return "${value / 10}.${value % 10}x"
    }

    fun apply(display: AirPlayDisplayConfig, tenths: Int): AirPlayDisplayConfig {
        val value = sanitize(tenths)
        return display.copy(
            widthPixels = scalePixels(display.widthPixels, value),
            heightPixels = scalePixels(display.heightPixels, value),
        )
    }

    /** Arbitrary integer percentages retain even dimensions required by video decoders. */
    fun applyPercent(display: AirPlayDisplayConfig, percent: Int): AirPlayDisplayConfig {
        val value = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        fun scale(pixels: Int): Int {
            require(pixels > 0) { "pixels must be positive" }
            val scaled = ((pixels.toLong() * value + 50L) / 100L).toInt().coerceAtLeast(1)
            return if (scaled % 2 == 0) scaled else scaled + 1
        }
        return display.copy(widthPixels = scale(display.widthPixels), heightPixels = scale(display.heightPixels))
    }

    private fun scalePixels(pixels: Int, tenths: Int): Int {
        require(pixels > 0) { "pixels must be positive" }
        val scaled = ((pixels.toLong() * tenths + 5L) / 10L).toInt().coerceAtLeast(1)
        return if (scaled % 2 == 0) scaled else scaled + 1
    }
}
