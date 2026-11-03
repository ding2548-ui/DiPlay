package com.shilapi.xcertplay.airplay

/**
 * The CarPlay size presets. iOS keeps controls at a fixed physical size, so the size
 * is expressed as the physical screen width reported to the iPhone: a wider screen gets smaller controls.
 * The CarPlay menu can set any other length in its own range.
 */
enum class CarPlaySize(val label: String, val widthMillimeters: Int) {
    LARGE("大", 250),
    MEDIUM("中", 300),
    SMALL("小", 350),
    HIDPI_2X("HiDPI 2x", 150),
    HIDPI_3X("HiDPI 3x", 100);

    companion object {
        val DEFAULT = MEDIUM

        /** The preset that sends exactly this width, or null for a length set in the CarPlay menu. */
        fun exact(millimeters: Int): CarPlaySize? = entries.firstOrNull { it.widthMillimeters == millimeters }
    }
}
