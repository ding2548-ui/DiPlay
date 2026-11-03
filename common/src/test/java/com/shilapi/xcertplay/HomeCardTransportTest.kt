package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCardTransportTest {
    @Test fun onlyARunningUsbSessionChangesTheCard() {
        // #742: a USB session showed under "Wireless CarPlay" with the Wi-Fi Direct hint.
        assertTrue(HomeCardTransport.wired(sessionRunning = true, wirelessSelected = false))
        assertFalse(HomeCardTransport.wired(sessionRunning = true, wirelessSelected = true))
        assertFalse(HomeCardTransport.wired(sessionRunning = false, wirelessSelected = false))
        assertFalse(HomeCardTransport.wired(sessionRunning = false, wirelessSelected = true))
    }
}
