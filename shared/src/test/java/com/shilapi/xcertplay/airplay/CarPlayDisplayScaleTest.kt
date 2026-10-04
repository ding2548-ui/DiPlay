package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayDisplayScaleTest {
    @Test
    fun scalesHandshakeDisplayAtSupportedSteps() {
        val native = AirPlayDisplayConfig(widthPixels = 1080, heightPixels = 2160)

        val half = CarPlayDisplayScale.apply(native, 50)

        assertEquals(540, half.widthPixels)
        assertEquals(1080, half.heightPixels)
        assertEquals("50%", CarPlayDisplayScale.label(50))
    }

    @Test
    fun clampsScaleToTheUiRange() {
        assertEquals(25, CarPlayDisplayScale.sanitize(0))
        assertEquals(175, CarPlayDisplayScale.sanitize(200))
    }

    @Test
    fun alignsScaledDisplayDimensionsToEvenPixels() {
        val native = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 978)

        val scaled = CarPlayDisplayScale.apply(native, 70)

        assertEquals(1344, scaled.widthPixels)
        assertEquals(686, scaled.heightPixels)
    }
}
