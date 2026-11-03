package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class BydHudProtocolTest {
    @Test fun onlyStartAcceptsAlreadyStarted() {
        assertTrue(BydHudProtocol.serviceStarted(0))
        assertTrue(BydHudProtocol.serviceStarted(13))
        assertTrue(BydHudProtocol.eventAccepted(0))
        assertFalse(BydHudProtocol.eventAccepted(13))
    }
    @Test fun positiveGatewayErrorsAreNotSuccess() {
        for (code in listOf(-1, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 15)) {
            assertFalse("start=$code", BydHudProtocol.serviceStarted(code))
            assertFalse("event=$code", BydHudProtocol.eventAccepted(code))
        }
    }

    @Test fun aRefusedGatewayBindBacksOffToAMinute() {
        assertEquals(0L, BydHudProtocol.bindRetryMillis(0))
        assertEquals(300L, BydHudProtocol.bindRetryMillis(1))
        assertEquals(600L, BydHudProtocol.bindRetryMillis(2))
        assertEquals(38_400L, BydHudProtocol.bindRetryMillis(8))
        assertEquals(60_000L, BydHudProtocol.bindRetryMillis(9))
        assertEquals(60_000L, BydHudProtocol.bindRetryMillis(1_000))
    }
}
