package com.shilapi.xcertplay.airplay

/**
 * Display scaling uses whole percents so the UI can expose the 16-step 25%..100% ladder
 * (the same option count as AutoKit's resolution list).
 */
object CarPlayDisplayScale {
    const val MIN_PERCENT = 25
    const val MAX_PERCENT = 100
    const val DEFAULT_PERCENT = MAX_PERCENT

    fun sanitize(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun label(percent: Int): String = "${sanitize(percent)}%"

    fun apply(display: AirPlayDisplayConfig, percent: Int): AirPlayDisplayConfig {
        val value = sanitize(percent)
        return display.copy(
            widthPixels = scalePixels(display.widthPixels, value),
            heightPixels = scalePixels(display.heightPixels, value),
        )
    }

    private fun scalePixels(pixels: Int, percent: Int): Int {
        require(pixels > 0) { "pixels must be positive" }
        val scaled = ((pixels.toLong() * percent + 50L) / 100L).toInt().coerceAtLeast(1)
        return if (scaled % 2 == 0) scaled else scaled + 1
    }
}
