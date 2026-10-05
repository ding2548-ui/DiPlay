package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BydClusterNaviModeTest {
    @Test
    fun readsTheModeFromServiceCallOutput() {
        assertEquals(BydClusterNaviMode.TURN_ON_BY_NAVI, BydClusterNaviMode.parseRead("Result: Parcel(00000000 00000002   '........')"))
        assertEquals(BydClusterNaviMode.FULL, BydClusterNaviMode.parseRead("Result: Parcel(00000000 00000004   '........')"))
        // An exception code in the first word, an unknown value, or no parcel at all.
        assertNull(BydClusterNaviMode.parseRead("Result: Parcel(ffffffff 00000002   '........')"))
        assertNull(BydClusterNaviMode.parseRead("Result: Parcel(00000000 00000009   '........')"))
        assertNull(BydClusterNaviMode.parseRead(null))
    }

    @Test
    fun onlySmallAndFullShowTheMap() {
        assertEquals(
            listOf(BydClusterNaviMode.SMALL, BydClusterNaviMode.FULL),
            BydClusterNaviMode.entries.filter { it.showsMap },
        )
    }

    @Test
    fun readsTheVerifiedId() {
        assertEquals("service call autoservice 5 i32 1007 i32 1086337074", BydClusterNaviMode.READ_COMMAND)
    }
}
