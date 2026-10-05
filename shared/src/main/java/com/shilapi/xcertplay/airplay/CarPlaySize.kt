package com.shilapi.xcertplay.airplay

/**
 * The single user-facing CarPlay size. iOS keeps controls at a fixed physical size, so the size
 * is expressed as the physical screen width reported to the iPhone: a wider screen gets smaller controls.
 */
enum class CarPlaySize(val label: String, val widthMillimeters: Int) {
    LARGE("Large", 250),
    MEDIUM("Medium", 300),
    SMALL("Small", 350);

    companion object {
        val DEFAULT = MEDIUM

        /** Maps any stored width, including values from older builds, to the nearest preset. */
        fun fromWidthMillimeters(millimeters: Int): CarPlaySize =
            entries.minBy { kotlin.math.abs(it.widthMillimeters - millimeters) }
    }
}
