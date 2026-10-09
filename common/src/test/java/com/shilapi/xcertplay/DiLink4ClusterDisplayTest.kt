package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import org.junit.Assert.*
import org.junit.Test

class DiLink4ClusterDisplayTest {
    @Test fun fullPanelStreamKeepsContentAndReusesDilink5SafeArea() {
        for (content in CarPlayClusterDisplay.Content.entries) {
            val config = DiLink4ClusterDisplay.streamConfig(content)
            assertEquals(1920, config.widthPixels)
            assertEquals(720, config.heightPixels)
            assertEquals(content.url, config.initialUrl)
            assertEquals(CarPlayClusterDisplay.config(1920, 720, scalePercent = 100, content = content).safeArea, config.safeArea)
            assertNotNull(config.safeArea)
            assertEquals(true, config.safeAreaDrawOutside)
        }
    }

    @Test fun savedMarkerOffsetsMoveSafeAreaWithoutChangingResolution() {
        val centered = DiLink4ClusterDisplay.streamConfig(CarPlayClusterDisplay.Content.MAP)
        val shifted = DiLink4ClusterDisplay.streamConfig(CarPlayClusterDisplay.Content.MAP, 1, -1)
        assertEquals(1920, shifted.widthPixels)
        assertEquals(720, shifted.heightPixels)
        assertNotEquals(centered.safeArea, shifted.safeArea)
        assertEquals(CarPlayClusterDisplay.config(1920, 720, 100, 1, -1).safeArea, shifted.safeArea)
    }

    @Test fun customBoxOverridesMarkerOffsetsWithoutChangingStreamSize() {
        val rect = com.shilapi.xcertplay.airplay.SafeAreaRect(300, 100, 1500, 620)
        val config = DiLink4ClusterDisplay.streamConfig(CarPlayClusterDisplay.Content.INSTRUMENTS, 4, 3, rect)
        assertEquals(1920, config.widthPixels)
        assertEquals(720, config.heightPixels)
        assertEquals(com.shilapi.xcertplay.airplay.AirPlayInsets(100, 100, 300, 420), config.safeArea)
        assertEquals(CarPlayClusterDisplay.Content.INSTRUMENTS.url, config.initialUrl)
        assertEquals(true, config.safeAreaDrawOutside)
        assertEquals(config.safeArea,
            DiLink4ClusterDisplay.streamConfig(CarPlayClusterDisplay.Content.INSTRUMENTS, -4, -3, rect).safeArea)
    }

    @Test fun editorDefaultMatchesTheCurrentMarkerSafeArea() {
        val rect = DiLink4ClusterDisplay.defaultSafeAreaRect(1, -1)
        val automatic = DiLink4ClusterDisplay.streamConfig(CarPlayClusterDisplay.Content.MAP, 1, -1)
        val edited = DiLink4ClusterDisplay.streamConfig(CarPlayClusterDisplay.Content.MAP, 1, -1, rect)
        // Conversion can align an odd dimension by one pixel, but must preserve the placement.
        assertTrue(kotlin.math.abs(automatic.safeArea!!.left - edited.safeArea!!.left) <= 1)
        assertTrue(kotlin.math.abs(automatic.safeArea!!.top - edited.safeArea!!.top) <= 1)
    }

    @Test fun similarDisplayNamesAreNotAccepted() {
        assertFalse(DiLink4ClusterDisplay.matches("shared_${DiLink4ClusterDisplay.NAME}_0", 1920, 720))
        assertFalse(DiLink4ClusterDisplay.matches("Passenger display", 1920, 720))
        assertFalse(DiLink4ClusterDisplay.accepts("Passenger display", 1280, 480))
    }

    @Test fun observedProjectionGeometriesAreAcceptedWithoutReusingTheMeasuredProfile() {
        assertTrue(DiLink4ClusterDisplay.accepts(DiLink4ClusterDisplay.NAME, 1920, 720))
        assertTrue(DiLink4ClusterDisplay.accepts(DiLink4ClusterDisplay.NAME, 1280, 480))
        assertFalse(DiLink4ClusterDisplay.matches(DiLink4ClusterDisplay.NAME, 1280, 480))
        assertFalse(DiLink4ClusterDisplay.accepts(DiLink4ClusterDisplay.NAME, 1280, 720))
        assertFalse(DiLink4ClusterDisplay.accepts(DiLink4ClusterDisplay.NAME, 1920, 1080))
        assertFalse(DiLink4ClusterDisplay.accepts(DiLink4ClusterDisplay.NAME, 640, 240))
        assertFalse(DiLink4ClusterDisplay.accepts(DiLink4ClusterDisplay.NAME, 960, 360))
        assertFalse(DiLink4ClusterDisplay.accepts(DiLink4ClusterDisplay.NAME, 2560, 960))
        assertFalse(DiLink4ClusterDisplay.accepts(DiLink4ClusterDisplay.NAME, 0, 0))
        assertFalse(DiLink4ClusterDisplay.accepts(DiLink4ClusterDisplay.NAME, -1280, -480))
        assertFalse(DiLink4ClusterDisplay.accepts(DiLink4ClusterDisplay.NAME, Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
