package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientLightThresholdTest {
    @Test fun acceptsThresholdsWithinTheSupportedRange() {
        for (lux in listOf(1, 50, 200_000)) assertTrue(AmbientLightThreshold.isValid(lux))
    }

    @Test fun rejectsOutOfRangeThresholdsAndFallsBackToThirtyLux() {
        for (lux in listOf(-1, 0, 200_001, Int.MAX_VALUE)) {
            assertFalse(AmbientLightThreshold.isValid(lux))
            assertEquals(AmbientLightThreshold(30), AmbientLightThreshold.fromStored(lux))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidThresholdCannotReachTheControllerOrPersistence() {
        AmbientLightThreshold(0)
    }
}
