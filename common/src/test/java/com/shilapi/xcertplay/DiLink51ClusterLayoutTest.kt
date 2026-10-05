package com.shilapi.xcertplay

import com.shilapi.xcertplay.DiLink51ClusterLayout.Theme
import com.shilapi.xcertplay.DiLink51ClusterLayout.Plan
import org.junit.Assert.*
import org.junit.Test

class DiLink51ClusterLayoutTest {
    private val names = listOf(DiLink51ClusterLayout.BASE, DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.SIDE)
    private val firmware = DiLink51ClusterLayout.FINGERPRINT

    @Test fun mapUsesFullLayerWhileBothOtherThemesUseSideLayer() {
        assertEquals(DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.displayName(names, firmware, Theme.MAP))
        for (theme in listOf(Theme.SCENARIO, Theme.SIMPLE)) {
            assertEquals(DiLink51ClusterLayout.SIDE, DiLink51ClusterLayout.displayName(names, firmware, theme))
            assertEquals(Plan(1320, 0, 600, 720, false), DiLink51ClusterLayout.plan(1920, 720, theme))
        }
    }

    @Test fun missingSideLayerCannotCoverScenarioWithAFullMap() {
        assertNull(DiLink51ClusterLayout.displayName(listOf(DiLink51ClusterLayout.BASE, DiLink51ClusterLayout.FULL), firmware, Theme.SCENARIO))
    }

    @Test fun fullMapKeepsSpeedAndGearBandsOutsideTheVideoViewport() {
        val plan = DiLink51ClusterLayout.plan(1920, 720, Theme.MAP)!!
        assertTrue(plan.top >= 144)
        assertTrue(plan.top + plan.height <= 624)
        assertEquals(0, plan.left)
        assertEquals(1920, plan.width)
        assertEquals(0, plan.sourceLeft)
        assertEquals(240, plan.sourceTop)
        assertNull(DiLink51ClusterLayout.streamConfig().safeArea)
    }

    @Test fun oneContinuousStreamSupportsBothViewportsWithoutStretching() {
        val config = DiLink51ClusterLayout.streamConfig()
        assertEquals(1920, config.widthPixels)
        assertEquals(720, config.heightPixels)
        assertEquals(292, config.widthPhysicalMm)
        assertEquals(110, config.heightPhysicalMm)
        assertEquals(0, config.features)
        for (theme in Theme.entries) {
            val plan = DiLink51ClusterLayout.plan(1920, 720, theme)!!
            assertTrue(plan.sourceLeft >= 0)
            assertTrue(plan.sourceTop >= 0)
            assertTrue(plan.sourceLeft + plan.width <= config.widthPixels)
            assertEquals(config.heightPixels, plan.sourceTop + plan.height)
            // Preserve the centered, bottom-anchored car marker in every crop.
            assertTrue(960 in plan.sourceLeft until plan.sourceLeft + plan.width)
            assertTrue(640 in plan.sourceTop until plan.sourceTop + plan.height)
        }
        val side = DiLink51ClusterLayout.plan(1920, 720, Theme.SIMPLE)!!
        assertEquals(660, side.sourceLeft)
        assertEquals(0, side.sourceTop)
    }

    @Test fun diLink5AndUnknownFirmwareRetainThePrDisplaySelection() {
        for (fingerprint in listOf("BYD-AUTO/DiLink5.0/DiLink5.0:12/test", "unknown")) {
            assertFalse(DiLink51ClusterLayout.supported(fingerprint))
            assertEquals(DiLink51ClusterLayout.BASE, DiLink51ClusterLayout.displayName(names, fingerprint, Theme.SIMPLE))
            assertEquals(DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.displayName(names.drop(1), fingerprint, Theme.MAP))
        }
    }

    @Test fun unmeasuredPanelSizesAreNotGivenTheWrongReadoutMask() {
        assertNull(DiLink51ClusterLayout.plan(1280, 480, Theme.MAP))
    }
}
