package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydAmapAdapterTest {
    @Test
    fun findsTheDiLink3AdapterWhenTheBydOneIsAbsent() {
        // DiLink 3.0, Android 10 (BYD Han EV, GCC): only com.example.amapservice is installed.
        val adapter = BydAmapAdapter.find { it == "com.example.amapservice" }

        assertEquals(BydAmapAdapter.DILINK3, adapter)
        assertTrue(adapter!!.needsSimpleNavigationMode)
    }

    @Test
    fun prefersTheBydAdapterAndLeavesItsClusterModeAlone() {
        val adapter = BydAmapAdapter.find { true }

        assertEquals(BydAmapAdapter.BYD, adapter)
        assertFalse(adapter!!.needsSimpleNavigationMode)
    }

    @Test
    fun noAdapterMeansNoClusterOutput() {
        assertNull(BydAmapAdapter.find { false })
    }

    @Test
    fun clusterModeUsesClusterDebugCommands() {
        assertEquals("service call AutoContainer 2 i32 1000 i32 39 s16 \"\"", BydDiLink3ClusterMode.Mode.SIMPLE_NAVIGATION.command)
        assertEquals("service call AutoContainer 2 i32 1000 i32 16 s16 \"\"", BydDiLink3ClusterMode.Mode.PROJECTION.entryCommand)
        assertEquals("service call AutoContainer 2 i32 1000 i32 17 s16 \"\"", BydDiLink3ClusterMode.Mode.PROJECTION.command)
        assertEquals("service call AutoContainer 2 i32 1000 i32 18 s16 \"\"", BydDiLink3ClusterMode.Mode.STOCK.command)
    }

    @Test
    fun mapWinsOverGuidanceAndStockIsRestoredOnlyAfterAChange() {
        val mode = BydDiLink3ClusterMode
        assertNull(mode.desired(mapShown = false, guidanceActive = false, requested = null))
        assertEquals(BydDiLink3ClusterMode.Mode.SIMPLE_NAVIGATION, mode.desired(false, true, null))
        assertEquals(BydDiLink3ClusterMode.Mode.PROJECTION, mode.desired(true, true, BydDiLink3ClusterMode.Mode.SIMPLE_NAVIGATION))
        assertEquals(BydDiLink3ClusterMode.Mode.SIMPLE_NAVIGATION, mode.desired(false, true, BydDiLink3ClusterMode.Mode.PROJECTION))
        assertEquals(BydDiLink3ClusterMode.Mode.STOCK, mode.desired(false, false, BydDiLink3ClusterMode.Mode.PROJECTION))
    }

    @Test
    fun diLink3GuidanceTextIsReadableOnTheCluster() {
        val text = BydDiLink3GuidanceText
        assertEquals("250 m", text.distance(250))
        assertEquals("5.4 km", text.distance(5400))
        assertEquals("12 km", text.distance(12_345))
        assertNull(text.distance(-1))
        assertEquals("10 min", text.duration(600))
        assertEquals("1 min", text.duration(1))
        assertEquals("1 h 5 min", text.duration(3900))
        assertNull(text.duration(-1))
        val utc = java.util.TimeZone.getTimeZone("UTC")
        assertEquals("15:55", text.arrival(15L * 3_600_000 + 45 * 60_000, 600, utc, use24Hour = true))
        assertEquals("3:55", text.arrival(15L * 3_600_000 + 45 * 60_000, 600, utc, use24Hour = false))
    }

    @Test
    fun displayIsCreatedAsDashCastDoesAndLeftInTheStockView() {
        assertEquals(
            listOf(16, 35, 18).map { "service call AutoContainer 2 i32 1000 i32 $it s16 \"\"" },
            BydDiLink3ClusterMode.CREATE_DISPLAY,
        )
    }

    @Test
    fun clusterModeAcceptsOnlyAnExceptionFreeReply() {
        assertTrue(BydDiLink3ClusterMode.accepted("Result: Parcel(00000000 00000000   '........')"))
        assertFalse(BydDiLink3ClusterMode.accepted("Result: Parcel(ffffffec 00000000 '........')"))
        assertFalse(BydDiLink3ClusterMode.accepted("service: Service AutoContainer does not exist"))
        assertFalse(BydDiLink3ClusterMode.accepted(null))
    }
}
