package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.withVehicleStatusFrom
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydBatteryReadinessTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val reading = BydBatteryReading(25.0, 150, 25.1, false, BydBatteryProtocol.CANFD)
    private val identification = Iap2IdentificationConfig(
        name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
        firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3,
        vehicleStatusEnabled = true,
    )

    @Test
    fun delayedAdbApprovalPrimesTheNextConnectionFromTheSettingsCheck() {
        val unavailable = BydAdbAccess.readStatus(context) { null }
        assertNull(unavailable.batteryPercent)
        assertNull(unavailable.speedKmh)
        assertNull(unavailable.gear)
        assertFalse(identification.withVehicleStatusFrom(BydBatteryStatus).vehicleStatusEnabled)

        // A successful settings check after approval must prime the provider, not just its label.
        val ready = BydAdbAccess.readStatus(context) { command ->
            if (command == "getprop ro.car.protocol") return@readStatus "CANFD"
            val bits = when (command.substringAfterLast(' ')) {
                "1246777400" -> "41c80000"
                "1246765118" -> "00000096"
                "882901008" -> "41c8cccd"
                "876609560" -> "0000000f"
                "-1807745016" -> "42100000"
                "555745336" -> "00000001"
                else -> "00000003"
            }
            "Result: Parcel(00000000 $bits   '........')"
        }
        assertEquals(25.0, ready.batteryPercent!!, 0.001)
        assertEquals(36.0, ready.speedKmh!!, 0.001)
        assertEquals(VehicleGear.PARK, ready.gear)
        assertTrue(identification.withVehicleStatusFrom(BydBatteryStatus).vehicleStatusEnabled)
        assertEquals(150, BydBatteryStatus.snapshot()!!.rangeKm)
        reconnectAfterIdleRefreshesWithoutWaitingForThePeriodicTick()
    }

    private fun reconnectAfterIdleRefreshesWithoutWaitingForThePeriodicTick() {
        BydBatteryStatus.readBattery = { reading }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
        BydBatteryStatus.start(context)
        awaitReading()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
        assertNull(BydBatteryStatus.snapshot())
        assertFalse(identification.withVehicleStatusFrom(BydBatteryStatus).vehicleStatusEnabled)

        BydBatteryStatus.start(context)
        awaitReading()
        assertTrue(identification.withVehicleStatusFrom(BydBatteryStatus).vehicleStatusEnabled)
    }

    @Test
    fun staleReadingBecomesUsableAgainOnlyAfterFreshDataIsPublished() {
        var clock = 0L
        val cache = BydBatteryCache { clock }
        cache.accept(reading)
        clock += 181_000
        assertNull(cache.snapshot(20))
        cache.accept(BydBatteryReading(10.0, 60, 10.0, false, BydBatteryProtocol.CANFD))
        val fresh = cache.snapshot(20)!!
        assertEquals(10.0, fresh.batteryPercent, 0.001)
        assertEquals(100_400L, fresh.maxChargeWh)
    }

    @Test
    fun protocolChangesCannotReuseThePreviousCapacity() {
        val cache = BydBatteryCache { 0 }
        cache.accept(reading)
        assertEquals(100_400L, cache.snapshot(20)!!.maxChargeWh)
        cache.accept(BydBatteryReading(51.0, 36, null, true, BydBatteryProtocol.CAN))
        val can = cache.snapshot(20)!!
        assertEquals(51.0, can.batteryPercent, 0.001)
        assertNull(can.currentChargeWh)
        assertNull(can.maxChargeWh)
        cache.accept(BydBatteryReading(10.0, 60, 5.0, false, BydBatteryProtocol.CANFD))
        assertEquals(50_000L, cache.snapshot(20)!!.maxChargeWh)
    }

    @Test
    fun failedSampleClearsTheReadingAndCapacity() {
        val cache = BydBatteryCache { 0 }
        cache.accept(reading)
        cache.accept(null)
        assertNull(cache.snapshot(20))
        cache.accept(BydBatteryReading(10.0, 60, 5.0, false, BydBatteryProtocol.CANFD))
        assertEquals(50_000L, cache.snapshot(20)!!.maxChargeWh)
    }

    @Test
    fun settingsCheckPublishesCanWithoutEnergyAndClearsUnknownProtocol() {
        val ready = BydAdbAccess.readStatus(context) { command ->
            when (command) {
                "getprop ro.car.protocol" -> "CAN\n"
                "service call autoservice 7 i32 1014 i32 1033543720" -> "Result: Parcel(00000000 424c0000   '........')"
                "service call autoservice 5 i32 1014 i32 1033203771" -> "Result: Parcel(00000000 00000024   '........')"
                "service call autoservice 5 i32 1009 i32 876611608" -> "Result: Parcel(00000000 00000001   '........')"
                else -> null
            }
        }
        assertEquals(BydAdbAccess.State.READY, ready.state)
        assertEquals(51.0, ready.batteryPercent!!, 0.001)
        assertEquals(36, ready.rangeKm)
        assertTrue(identification.withVehicleStatusFrom(BydBatteryStatus).vehicleStatusEnabled)
        assertNull(BydBatteryStatus.snapshot()!!.maxChargeWh)

        val unsupported = BydAdbAccess.readStatus(context) { "UNKNOWN" }
        assertEquals(BydAdbAccess.State.READY, unsupported.state)
        assertNull(unsupported.batteryPercent)
        assertNull(BydBatteryStatus.snapshot())
        assertFalse(identification.withVehicleStatusFrom(BydBatteryStatus).vehicleStatusEnabled)
    }

    private fun awaitReading() {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (BydBatteryStatus.snapshot() == null && System.nanoTime() < deadline) Thread.sleep(5)
        assertNotNull("refresh should complete before the 30-second periodic tick", BydBatteryStatus.snapshot())
    }
}
