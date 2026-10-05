package com.shilapi.xcertplay

/** A single lux threshold separates night from day; the controller debounces both directions. */
data class AmbientLightThreshold(val lux: Int = DEFAULT_LUX) {
    init {
        require(isValid(lux)) { "Lux threshold is outside the supported range" }
    }

    companion object {
        const val DEFAULT_LUX = 30
        const val MIN_LUX = 1
        const val MAX_LUX = 200_000

        fun isValid(lux: Int): Boolean = lux in MIN_LUX..MAX_LUX

        fun fromStored(lux: Int): AmbientLightThreshold =
            if (isValid(lux)) AmbientLightThreshold(lux) else AmbientLightThreshold()
    }
}
