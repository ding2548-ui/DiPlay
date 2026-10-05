package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BydParkedStateTest {
    @Test
    fun readsTheGearboxReply() {
        assertEquals(true, BydParkedState.parked("Result: Parcel(00000000 00000001   '........')"))
        assertEquals(false, BydParkedState.parked("Result: Parcel(00000000 00000004   '........')"))
        assertEquals(false, BydParkedState.parked("Result: Parcel(00000000 00000006   '........')"))
        assertEquals(false, BydParkedState.parked("Result: Parcel(00000000 00000002   '........')"))
    }

    @Test
    fun unknownGearIsNotParked() {
        assertNull(BydParkedState.parked(null))
        assertNull(BydParkedState.parked("Result: Parcel(00000000 00000000   '........')"))
        assertNull(BydParkedState.parked("Result: Parcel(ffffffff 00000001   '........')"))
    }
}
