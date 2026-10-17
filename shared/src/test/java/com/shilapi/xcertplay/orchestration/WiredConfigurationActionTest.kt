package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredConfigurationActionTest {
    private fun choose(found: Boolean, carPlayMode: Boolean, tried: Int) =
        WiredConfigurationAction.choose(found, carPlayMode, tried, maximumTransitions = 2)

    @Test fun carPlayModeIsUsedAsItIs() {
        assertEquals(WiredConfigurationAction.USE, choose(found = true, carPlayMode = true, tried = 0))
        assertEquals(WiredConfigurationAction.USE, choose(found = true, carPlayMode = true, tried = 1))
    }

    @Test fun usbmuxAndNcmWithoutAppleEthernetAskForCarPlayModeFirst() {
        // #634: iOS 26 offered configuration 5 with USBMUX and NCM; AirPlay never opened on it.
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, choose(found = true, carPlayMode = false, tried = 0))
        assertTrue(WiredConfigurationAction.fallbackAfterRejection(found = true, carPlayMode = false))
    }

    @Test fun usbmuxAndNcmWithoutAppleEthernetAreUsedAfterTheModeRequest() {
        assertEquals(
            WiredConfigurationAction.USE_WITHOUT_APPLE_ETHERNET,
            choose(found = true, carPlayMode = false, tried = 1),
        )
    }

    @Test fun noUsableConfigurationRequestsTheTransitionUntilTheLimit() {
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, choose(found = false, carPlayMode = false, tried = 0))
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, choose(found = false, carPlayMode = false, tried = 1))
        assertEquals(WiredConfigurationAction.REJECT, choose(found = false, carPlayMode = false, tried = 2))
        assertFalse(WiredConfigurationAction.fallbackAfterRejection(found = false, carPlayMode = false))
    }

    @Test fun diagnosticNamesKeepTheExistingReportWording() {
        assertEquals("reuse-descriptors", WiredConfigurationAction.USE.diagnostic)
        assertEquals("request-transition", WiredConfigurationAction.REQUEST_TRANSITION.diagnostic)
        assertEquals("reject-missing-configuration", WiredConfigurationAction.REJECT.diagnostic)
    }

    @Test fun theModeASessionDiPlayEndedLeftBehindIsRequestedAgainOnce() {
        // #335: after Save and reconnect the iPhone stayed in CarPlay mode on the same device node and took no
        // new session until it reset USB; a fresh mode request connected within seconds.
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION,
            WiredConfigurationAction.choose(true, true, 0, maximumTransitions = 2, sessionAlreadyStarted = true))
        // The iPhone may stay in the mode after the request: then the mode is used, as before.
        assertEquals(WiredConfigurationAction.USE,
            WiredConfigurationAction.choose(true, true, 1, maximumTransitions = 2, sessionAlreadyStarted = true))
        // A refused request also keeps the mode it offers.
        assertTrue(WiredConfigurationAction.fallbackAfterRejection(found = true, carPlayMode = true, sessionAlreadyStarted = true))
        assertFalse(WiredConfigurationAction.fallbackAfterRejection(found = true, carPlayMode = true))
    }

    @Test fun theMemoryMatchesOnlyTheSameEnumeration() {
        WiredSessionMemory.clear()
        assertFalse(WiredSessionMemory.startedOn("/dev/bus/usb/001/005"))
        WiredSessionMemory.started("/dev/bus/usb/001/005")
        assertTrue(WiredSessionMemory.startedOn("/dev/bus/usb/001/005"))
        assertFalse(WiredSessionMemory.startedOn("/dev/bus/usb/001/006"))
        WiredSessionMemory.clear()
    }
}
