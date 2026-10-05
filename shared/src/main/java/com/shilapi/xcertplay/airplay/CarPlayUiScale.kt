package com.shilapi.xcertplay.airplay

import kotlin.math.roundToInt

/** Request a larger canvas for smaller CarPlay controls, then fit it to the same touch surface. */
object CarPlayUiScale {
    const val DEFAULT = 100
    val presets = listOf(75, 85, DEFAULT, 115)

    fun sanitize(percent: Int): Int = percent.takeIf { it in presets } ?: DEFAULT

    fun label(percent: Int): String = when (sanitize(percent)) {
        75 -> "Smaller"
        85 -> "Small"
        115 -> "Large"
        else -> "Default"
    }

    fun apply(display: AirPlayDisplayConfig, percent: Int): AirPlayDisplayConfig {
        val scale = sanitize(percent)
        if (scale == DEFAULT) return display
        require(display.widthPixels > 0 && display.heightPixels > 0)
        fun pixels(value: Int): Int = ((value.toLong() * 100 / scale + 1) / 2 * 2).toInt()
        val width = pixels(display.widthPixels)
        val height = pixels(display.heightPixels)
        // Never request an enlarged canvas beyond 4K. The host also checks its actual decoder.
        if (scale < DEFAULT && (maxOf(width, height) > 3840 || minOf(width, height) > 2160)) return display
        fun insets(value: AirPlayInsets?): AirPlayInsets? = value?.let {
            it.copy(
                top = (it.top * height.toDouble() / display.heightPixels).roundToInt(),
                bottom = (it.bottom * height.toDouble() / display.heightPixels).roundToInt(),
                left = (it.left * width.toDouble() / display.widthPixels).roundToInt(),
                right = (it.right * width.toDouble() / display.widthPixels).roundToInt(),
            )
        }
        return display.copy(widthPixels = width, heightPixels = height,
            viewArea = insets(display.viewArea), safeArea = insets(display.safeArea))
    }
}
