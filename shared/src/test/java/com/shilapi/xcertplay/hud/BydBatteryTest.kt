package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydBatteryTest {
    // Replies from the car on 2026-09-28: 25 %, 150 km, 25.1 kWh left, not charging (BMS state 15).
    private val car = mapOf(
        "1246777400" to "Result: Parcel(00000000 41c80000   '.......A')",
        "1246765118" to "Result: Parcel(00000000 00000096   '........')",
        "882901008" to "Result: Parcel(00000000 41c8cccd   '.......A')",
        "876609560" to "Result: Parcel(00000000 0000000f   '........')",
    )

    private fun shell(replies: Map<String, String>, protocol: String = "CANFD"): (String) -> String? = { command ->
        if (command == "getprop ro.car.protocol") protocol
        else replies.entries.firstOrNull { command.endsWith(" ${it.key}") }?.value
    }

    @Test
    fun readsTheCarsReplies() {
        val reading = BydBattery.read(shell(car))!!

        assertEquals(25.0, reading.percent, 0.001)
        assertEquals(150, reading.rangeKm)
        assertEquals(25.1, reading.remainingKwh!!, 0.001)
        assertEquals(BydBatteryProtocol.CANFD, reading.protocol)
        assertFalse(reading.charging)
    }

    @Test
    fun noAdbAccessGivesNoReading() {
        // BydAdbShell answers null while adbd is off, refuses DiPlay's key or waits for approval.
        assertNull(BydBattery.read { null })
    }

    @Test
    fun aMissingOrRejectedValueGivesNoReading() {
        assertNull(BydBattery.read(shell(car - "1246765118")))
        assertNull(BydBattery.read(shell(car + ("1246777400" to "Result: Parcel(ffffd8e5    '....')"))))
    }

    @Test
    fun theIphoneGetsWattHoursAndAWarningAtTheThreshold() {
        val reading = BydBatteryReading(percent = 25.0, rangeKm = 150, remainingKwh = 25.1, charging = false, protocol = BydBatteryProtocol.CANFD)

        val above = BydBattery.snapshot(reading, lowPercent = 20, fullKwh = null)
        assertFalse(above.rangeWarning)
        assertEquals(25_100L, above.currentChargeWh)
        assertEquals(100_400L, above.maxChargeWh)
        assertEquals(600, above.maxRangeKm)

        assertTrue(BydBattery.snapshot(reading, lowPercent = 25, fullKwh = null).rangeWarning)
    }

    @Test
    fun aLowChargeKeepsTheEarlierFullChargeEstimate() {
        val high = BydBatteryReading(percent = 80.0, rangeKm = 480, remainingKwh = 80.0, charging = false, protocol = BydBatteryProtocol.CANFD)
        val low = BydBatteryReading(percent = 5.0, rangeKm = 30, remainingKwh = 5.6, charging = false, protocol = BydBatteryProtocol.CANFD)

        assertNull(BydBattery.fullKwh(low))
        assertEquals(100_000L, BydBattery.snapshot(low, lowPercent = 20, fullKwh = BydBattery.fullKwh(high)).maxChargeWh)
    }

    @Test
    fun readsVerifiedCanCommandsWithoutProbingUnverifiedEnergy() {
        val replies = mapOf(
            "getprop ro.car.protocol" to "CAN\r\n",
            "service call autoservice 7 i32 1014 i32 1033543720" to "Result: Parcel(00000000 424c0000   '........')",
            "service call autoservice 5 i32 1014 i32 1033203771" to "Result: Parcel(00000000 00000024   '........')",
            "service call autoservice 5 i32 1009 i32 876611608" to "Result: Parcel(00000000 00000001   '........')",
        )
        val commands = mutableListOf<String>()
        val reading = BydBattery.read { command -> commands.add(command); replies[command] }!!

        assertEquals(replies.keys.toList(), commands)
        assertEquals(BydBatteryProtocol.CAN, reading.protocol)
        assertEquals(51.0, reading.percent, 0.001)
        assertEquals(36, reading.rangeKm)
        assertTrue(reading.charging)
        assertNull(reading.remainingKwh)
        assertNull(BydBattery.fullKwh(reading))
        val snapshot = BydBattery.snapshot(reading, 20, null)
        assertNull(snapshot.currentChargeWh)
        assertNull(snapshot.maxChargeWh)
        assertEquals(51.0, snapshot.batteryPercent, 0.001)
        assertEquals(36, snapshot.rangeKm)
    }

    @Test
    fun detectsProtocolBeforeEveryCanfdSample() {
        val commands = mutableListOf<String>()
        val reply = shell(car, " CANFD\n")
        repeat(2) { assertTrue(BydBattery.read { commands.add(it); reply(it) } != null) }
        val expected = listOf(
            "getprop ro.car.protocol",
            "service call autoservice 7 i32 1014 i32 1246777400",
            "service call autoservice 5 i32 1014 i32 1246765118",
            "service call autoservice 7 i32 1005 i32 882901008",
            "service call autoservice 5 i32 1009 i32 876609560",
        )
        assertEquals(expected + expected, commands)
    }

    @Test
    fun missingOrUnknownProtocolNeverFallsBackToCanfd() {
        for (protocol in listOf(null, "", "\r\n", "SOMEIP", "error: closed")) {
            val commands = mutableListOf<String>()
            assertNull(BydBattery.read { command -> commands.add(command); protocol })
            assertEquals(listOf("getprop ro.car.protocol"), commands)
        }
    }

    @Test
    fun invalidCanValuesGiveNoReading() {
        val replies = mapOf(
            "1033543720" to "Result: Parcel(00000000 424c0000   '........')",
            "1033203771" to "Result: Parcel(00000000 00000024   '........')",
            "876611608" to "Result: Parcel(00000000 00000001   '........')",
        )
        assertNull(BydBattery.read(shell(replies - "1033203771", "CAN")))
        for (invalid in listOf("7fc00000", "7f800000", "42ca0000", "bf800000")) {
            assertNull(BydBattery.read(shell(replies + ("1033543720" to "Result: Parcel(00000000 $invalid   '........')"), "CAN")))
        }
    }
}
