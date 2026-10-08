package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelSiriKeyTest {
    @Test fun pressesCloserThanTheGapBelongToOneHold() {
        val siri = WheelSiriKey()
        assertTrue(siri.opens(1_000))
        assertFalse(siri.opens(1_100))
        // The gap counts from the latest press, so a long hold stays one press.
        assertFalse(siri.opens(1_450))
        assertFalse(siri.opens(1_800))
        assertTrue(siri.opens(1_800 + WheelSiriKey.REPEAT_GAP_MILLIS))
    }
}
