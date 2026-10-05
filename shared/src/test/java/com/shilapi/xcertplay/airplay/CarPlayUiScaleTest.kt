package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayUiScaleTest {
    private val display = AirPlayDisplayConfig(1920, 1080, 200, 113, fps = 30)

    @Test fun smallerCanvasMatchesTheLiveVerifiedProfile() {
        val smaller = CarPlayUiScale.apply(display, 75)
        assertEquals(2560, smaller.widthPixels)
        assertEquals(1440, smaller.heightPixels)
        assertEquals(display, smaller.copy(widthPixels = 1920, heightPixels = 1080))
        val small = CarPlayUiScale.apply(display, 85)
        assertEquals(2258, small.widthPixels)
        assertEquals(1270, small.heightPixels)
        val large = CarPlayUiScale.apply(display, 115)
        assertEquals(1670, large.widthPixels)
        assertEquals(940, large.heightPixels)
    }

    @Test fun safeAndViewAreasKeepTheirPositionOnTheTouchSurface() {
        val original = display.copy(safeArea = AirPlayInsets(30, 60, 90, 120),
            viewArea = AirPlayInsets(6, 12, 18, 24))
        val smaller = CarPlayUiScale.apply(original, 75)
        assertEquals(AirPlayInsets(40, 80, 120, 160), smaller.safeArea)
        assertEquals(AirPlayInsets(8, 16, 24, 32), smaller.viewArea)
        assertEquals(original.safeArea!!.left.toDouble() / original.widthPixels,
            smaller.safeArea!!.left.toDouble() / smaller.widthPixels, 0.0001)
    }

    @Test fun reducedResolutionRemainsASeparateBaseForTheCanvas() {
        val reduced = CarPlayDisplayScale.apply(display, 6)
        val smaller = CarPlayUiScale.apply(reduced, 75)
        assertEquals(1536, smaller.widthPixels)
        assertEquals(864, smaller.heightPixels)
        assertEquals(30, smaller.fps)
    }

    @Test fun defaultUnknownAndExcessiveRequestsDoNotAlterTheExistingDisplay() {
        assertSame(display, CarPlayUiScale.apply(display, 100))
        assertSame(display, CarPlayUiScale.apply(display, 0))
        assertSame(display, CarPlayUiScale.apply(display, Int.MAX_VALUE))
        val fourK = display.copy(widthPixels = 3840, heightPixels = 2160)
        assertSame(fourK, CarPlayUiScale.apply(fourK, 75))
        val portrait = display.copy(widthPixels = 2160, heightPixels = 3840)
        assertSame(portrait, CarPlayUiScale.apply(portrait, 75))
        assertEquals(3840, CarPlayUiScale.apply(display.copy(widthPixels = 2880, heightPixels = 1620), 75).widthPixels)
    }

    @Test fun negotiatedDisplayAndAreasUseTheSameCanvas() {
        val main = CarPlayUiScale.apply(display, 75)
        val info = AirPlayInfoPlist.build(AirPlayConfig("Test", "00:11:22:33:44:55", "00:11:22:33:44:55", sourceVersion = "950.7.1", main = main))
        val screen = (info["displays"] as List<*>).first() as Map<*, *>
        val view = (screen["viewAreas"] as List<*>).first() as Map<*, *>
        val safe = view["safeArea"] as Map<*, *>
        for (area in listOf(screen, view, safe)) {
            assertEquals(2560, area["widthPixels"])
            assertEquals(1440, area["heightPixels"])
        }
        assertFalse(screen.containsKey("zoomFactor"))
    }
}
