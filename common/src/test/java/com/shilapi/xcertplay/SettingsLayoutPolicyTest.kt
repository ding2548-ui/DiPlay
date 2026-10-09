package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLayoutPolicyTest {
    private fun expanded(widthPx: Int, heightPx: Int, dpi: Int, fontScale: Float = 1f) =
        SettingsLayoutPolicy.isExpanded(widthPx * 160 / dpi, heightPx * 160 / dpi, fontScale)

    @Test fun commonHeadUnitsGetTheRailWhereItFits() {
        assertTrue(expanded(1024, 600, 160))
        assertTrue(expanded(1280, 720, 160))
        assertTrue(expanded(1280, 720, 240))
        assertTrue(expanded(1280, 800, 240))
        assertTrue(expanded(1920, 720, 240))
        assertTrue(expanded(2000, 1200, 320))
        // 1024x600 with a status bar and a bottom bar still leaves room.
        assertTrue(SettingsLayoutPolicy.isExpanded(1024, 520, 1f))
    }

    @Test fun smallHeadUnitsKeepTheSingleColumn() {
        assertFalse(expanded(800, 480, 160))
        assertFalse(expanded(800, 480, 240))
        assertFalse(expanded(1024, 600, 240))
        assertFalse(SettingsLayoutPolicy.isExpanded(839, 720, 1f))
        assertFalse(SettingsLayoutPolicy.isExpanded(1280, 399, 1f))
    }

    @Test fun overviewColumnsNeedRoomBesideTheRail() {
        assertFalse(SettingsLayoutPolicy.overviewHasTwoColumns(853, 1f))
        assertTrue(SettingsLayoutPolicy.overviewHasTwoColumns(1024, 1f))
        assertFalse(SettingsLayoutPolicy.overviewHasTwoColumns(1280, 1.3f))
    }

    @Test fun largeTextNeedsProportionallyMoreWidthForTheRail() {
        assertFalse(SettingsLayoutPolicy.isExpanded(1000, 720, 1.3f))
        assertTrue(SettingsLayoutPolicy.isExpanded(1280, 720, 1.3f))
        assertTrue(SettingsLayoutPolicy.isExpanded(1706, 960, 1.3f))
    }

    @Test fun railGrowsWithTextUpToALimit() {
        assertEquals(240, SettingsLayoutPolicy.railWidthDp(1f))
        assertEquals(312, SettingsLayoutPolicy.railWidthDp(1.3f))
        assertEquals(360, SettingsLayoutPolicy.railWidthDp(2f))
    }

    @Test fun everySettingsSectionHasExactlyOneDestination() {
        assertEquals(SettingsCategory.entries.toSet(), SettingsInformationArchitecture.sectionsByCategory.keys)
        val assignments = SettingsInformationArchitecture.sectionsByCategory.values.flatten()
        assertEquals(SettingsSection.entries.toSet(), assignments.toSet())
        assertEquals(assignments.toSet().size, assignments.size)
    }
}
