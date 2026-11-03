package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothPauseOutcomeTest {
    @Test fun eachFailureNamesItsCause() {
        assertEquals("car Bluetooth paused: Android Bluetooth is off until CarPlay ends",
            BluetoothPauseOutcome.describe(true, true, true, "READY"))
        assertEquals("car Bluetooth pause cancelled: CarPlay ended first",
            BluetoothPauseOutcome.describe(false, true, false, "READY"))
        assertEquals("car Bluetooth pause skipped: Android Bluetooth was already off",
            BluetoothPauseOutcome.describe(false, false, true, null))
        assertEquals("car Bluetooth pause skipped: Android Bluetooth state unknown",
            BluetoothPauseOutcome.describe(false, null, true, null))
        assertEquals("car Bluetooth pause failed: ADB access NOT_APPROVED",
            BluetoothPauseOutcome.describe(false, true, true, "NOT_APPROVED"))
        assertEquals("car Bluetooth pause failed: Android Bluetooth stayed on",
            BluetoothPauseOutcome.describe(false, true, true, "READY"))
    }
}
