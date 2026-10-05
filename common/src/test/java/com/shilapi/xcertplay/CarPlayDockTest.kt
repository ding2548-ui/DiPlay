package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayDockTest {
    @Test
    fun onlyFixedEdgesMoveLive() {
        assertTrue(CarPlayDock.movesLive(CarPlayDock.DRIVER_SIDE, CarPlayDock.BOTTOM))
        assertTrue(CarPlayDock.movesLive(CarPlayDock.BOTTOM, CarPlayDock.DRIVER_SIDE))
        assertFalse(CarPlayDock.movesLive(CarPlayDock.AUTOMATIC, CarPlayDock.BOTTOM))
        assertFalse(CarPlayDock.movesLive(CarPlayDock.DRIVER_SIDE, CarPlayDock.AUTOMATIC))
    }
}
