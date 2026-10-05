package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BydWheelSpeedTest {
    @Test
    fun readsSpeedInMetersPerSecond() {
        // The car's reply while parked on 2026-09-29, then 36.0 km/h.
        assertEquals(0.0, BydWheelSpeed.metersPerSecond("Result: Parcel(00000000 00000000   '........')")!!, 1e-9)
        assertEquals(10.0, BydWheelSpeed.metersPerSecond("Result: Parcel(00000000 42100000   '.......B')")!!, 1e-6)
    }

    @Test
    fun rejectsMissingOrImpossibleSpeed() {
        assertNull(BydWheelSpeed.metersPerSecond(null))
        assertNull(BydWheelSpeed.metersPerSecond("Result: Parcel(ffffffff 00000000   '........')"))
        assertNull(BydWheelSpeed.metersPerSecond("Result: Parcel(00000000 bf800000   '........')")) // -1.0
        assertNull(BydWheelSpeed.metersPerSecond("Result: Parcel(00000000 7fc00000   '........')")) // NaN
    }

    @Test
    fun mapsGears() {
        assertEquals(VehicleGear.PARK, BydWheelSpeed.gear("Result: Parcel(00000000 00000001   '........')"))
        assertEquals(VehicleGear.REVERSE, BydWheelSpeed.gear("Result: Parcel(00000000 00000002   '........')"))
        assertEquals(VehicleGear.NEUTRAL, BydWheelSpeed.gear("Result: Parcel(00000000 00000003   '........')"))
        assertEquals(VehicleGear.DRIVE, BydWheelSpeed.gear("Result: Parcel(00000000 00000004   '........')"))
        assertEquals(VehicleGear.DRIVE, BydWheelSpeed.gear("Result: Parcel(00000000 00000005   '........')"))
        assertEquals(VehicleGear.DRIVE, BydWheelSpeed.gear("Result: Parcel(00000000 00000006   '........')"))
        assertNull(BydWheelSpeed.gear("Result: Parcel(00000000 00000000   '........')"))
    }
}
