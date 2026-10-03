package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.VideoInCar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VideoInCarGateTest {
    @After
    fun reset() {
        VideoInCar.allowed = false
    }

    @Test
    fun videoIsAllowedOnlyWhileTheGearReadsPark() {
        val changes = mutableListOf<Boolean>()
        val gate = VideoInCarGate({ null }) { changes += it }

        gate.update(null) // no ADB: stays off
        gate.update(false)
        gate.update(true)
        gate.update(true)
        gate.update(null) // unreadable while parked: off again
        gate.update(false)

        assertEquals(listOf(true, false), changes)
        assertFalse(VideoInCar.allowed)
    }

    @Test
    fun closingTheGateTurnsVideoOff() {
        val gate = VideoInCarGate({ true }) {}
        gate.update(true)
        gate.close()
        gate.update(true)

        assertFalse(VideoInCar.allowed)
    }
}
