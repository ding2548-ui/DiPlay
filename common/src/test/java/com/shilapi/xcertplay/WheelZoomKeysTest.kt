package com.shilapi.xcertplay

import com.shilapi.xcertplay.WheelZoomKeys.Action
import com.shilapi.xcertplay.WheelZoomSettings.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelZoomKeysTest {
    private fun WheelZoomKeys.press(role: Role?, mapShown: Boolean = true, inCall: Boolean = false): Pair<Action, Action> =
        onKey(role, down = true, firstPress = true, mapShown = mapShown, inCall = inCall) to
            onKey(role, down = false, firstPress = true, mapShown = mapShown, inCall = inCall)

    @Test
    fun modeKeyTogglesZoomAndVolumeKeysFollowIt() {
        val keys = WheelZoomKeys()
        assertEquals(Action.PASS to Action.PASS, keys.press(Role.ZOOM_IN))
        assertEquals(Action.MODE_ON to Action.CONSUME, keys.press(Role.MODE))
        assertEquals(Action.ZOOM_IN to Action.CONSUME, keys.press(Role.ZOOM_IN))
        assertEquals(Action.ZOOM_OUT to Action.CONSUME, keys.press(Role.ZOOM_OUT))
        assertEquals(Action.MODE_OFF to Action.CONSUME, keys.press(Role.MODE))
        assertEquals(Action.PASS to Action.PASS, keys.press(Role.ZOOM_OUT))
    }

    @Test
    fun joystickKeysNeverZoom() {
        val keys = WheelZoomKeys()
        keys.press(Role.MODE)
        for (role in listOf(Role.JOYSTICK, Role.PREVIOUS, Role.NEXT, Role.SELECT)) {
            assertEquals(Action.PASS to Action.PASS, keys.press(role))
        }
        assertTrue(keys.zoomMode)
    }

    @Test
    fun otherKeysAlwaysPass() {
        val keys = WheelZoomKeys()
        keys.press(Role.MODE)
        assertEquals(Action.PASS to Action.PASS, keys.press(null))
    }

    @Test
    fun withoutTheDashboardMapTheModeKeyKeepsItsCarActionAndEndsZoom() {
        val keys = WheelZoomKeys()
        keys.press(Role.MODE)
        assertTrue(keys.zoomMode)
        assertEquals(Action.PASS to Action.PASS, keys.press(Role.MODE, mapShown = false))
        assertFalse(keys.zoomMode)
        assertEquals(Action.PASS to Action.PASS, keys.press(Role.ZOOM_IN))
    }

    @Test
    fun aCallKeepsTheVolumeKeysForVolume() {
        val keys = WheelZoomKeys()
        keys.press(Role.MODE)
        assertEquals(Action.PASS to Action.PASS, keys.press(Role.ZOOM_IN, inCall = true))
        assertTrue(keys.zoomMode)
        assertEquals(Action.ZOOM_IN to Action.CONSUME, keys.press(Role.ZOOM_IN))
    }

    @Test
    fun autoRepeatsAreKeptButDoNotZoomAgain() {
        val keys = WheelZoomKeys()
        assertEquals(Action.MODE_ON, keys.onKey(Role.MODE, down = true, firstPress = true, mapShown = true, inCall = false))
        assertEquals(Action.CONSUME, keys.onKey(Role.MODE, down = true, firstPress = false, mapShown = true, inCall = false))
        assertEquals(Action.ZOOM_IN, keys.onKey(Role.ZOOM_IN, down = true, firstPress = true, mapShown = true, inCall = false))
        assertEquals(Action.CONSUME, keys.onKey(Role.ZOOM_IN, down = true, firstPress = false, mapShown = true, inCall = false))
        assertTrue(keys.zoomMode)
    }

    @Test
    fun aPassedVolumeDownKeepsItsReleaseAfterTheCallEnds() {
        val keys = WheelZoomKeys()
        keys.press(Role.MODE)
        assertEquals(Action.PASS, keys.onKey(Role.ZOOM_IN, true, true, true, true))
        assertEquals(Action.PASS, keys.onKey(Role.ZOOM_IN, false, true, true, false))
        assertEquals(Action.ZOOM_IN to Action.CONSUME, keys.press(Role.ZOOM_IN))
    }

    @Test
    fun aConsumedVolumeDownKeepsRepeatsAndReleaseAfterTimeoutOrMapLoss() {
        val keys = WheelZoomKeys()
        keys.press(Role.MODE)
        assertEquals(Action.ZOOM_OUT, keys.onKey(Role.ZOOM_OUT, true, true, true, false))
        keys.timeOut()
        assertEquals(Action.CONSUME, keys.onKey(Role.ZOOM_OUT, true, false, false, true))
        assertEquals(Action.CONSUME, keys.onKey(Role.ZOOM_OUT, false, true, false, true))
        assertEquals(Action.PASS to Action.PASS, keys.press(Role.ZOOM_OUT))
    }

    @Test
    fun anyKeyNoticesMapLossAndNewSessionsStartWithVolume() {
        val keys = WheelZoomKeys()
        keys.updateEligibility("phone-one-stream-one")
        keys.press(Role.MODE)
        assertEquals(Action.PASS to Action.PASS, keys.press(Role.ZOOM_IN, mapShown = false))
        assertFalse(keys.zoomMode)
        keys.updateEligibility("phone-one-stream-two")
        assertEquals(Action.PASS to Action.PASS, keys.press(Role.ZOOM_IN))
        keys.press(Role.MODE)
        assertTrue(keys.updateEligibility(null)) // No key needs to be pressed when a stream ends.
        keys.updateEligibility("phone-two-stream-one")
        assertEquals(Action.PASS to Action.PASS, keys.press(Role.ZOOM_IN))
    }

    @Test
    fun physicalPressStillOwnsReleaseIfTheBindingOrFeatureChanges() {
        val keys = WheelZoomKeys()
        keys.press(Role.MODE)
        val physical = "device-7-volume-down"
        assertEquals(Action.ZOOM_OUT, keys.onKey(Role.ZOOM_OUT, true, true, true, false, physical))
        keys.timeOut()
        assertEquals(Action.CONSUME, keys.onKey(null, false, true, false, false, physical))
        assertEquals(Action.PASS, keys.onKey(null, false, true, false, false, physical))
    }

    @Test
    fun learningDoesNotCaptureARepeatOrReleaseWhoseDownWasAlreadyPassed() {
        val keys = WheelZoomKeys()
        val physical = "unassigned-wheel-key"
        assertEquals(Action.PASS, keys.onKey(null, true, true, false, false, physical))
        var captures = 0
        val capture = { captures++; Action.CONSUME }
        assertEquals(Action.PASS, keys.onKey(null, true, false, false, false, physical, capture))
        assertEquals(Action.PASS, keys.onKey(null, false, true, false, false, physical, capture))
        assertEquals(0, captures)
        assertEquals(Action.CONSUME, keys.onKey(null, true, true, false, false, physical, capture))
        assertEquals(1, captures)
        assertEquals(Action.CONSUME, keys.onKey(null, false, true, false, false, physical))
    }

    @Test
    fun orphanRepeatsAndReleasesKeepTheCarsAction() {
        val keys = WheelZoomKeys()
        keys.press(Role.MODE)
        assertEquals(Action.PASS, keys.onKey(Role.ZOOM_IN, true, false, true, false))
        assertEquals(Action.PASS, keys.onKey(Role.ZOOM_IN, false, true, true, false))
    }

    @Test
    fun timeOutEndsZoomModeOnce() {
        val keys = WheelZoomKeys()
        assertFalse(keys.timeOut())
        keys.press(Role.MODE)
        assertTrue(keys.timeOut())
        assertFalse(keys.zoomMode)
        assertFalse(keys.timeOut())
    }

    @Test
    fun keysSurviveTheirStoredForm() {
        val key = WheelKey(305, 300, "simulate-keys")
        assertEquals(key, WheelKey.decode(key.encode()))
        // Only the first two separators split; a device name may contain one.
        assertEquals(WheelKey(291, 114, "a|b"), WheelKey.decode("291|114|a|b"))
        assertNull(WheelKey.decode(null))
        assertNull(WheelKey.decode("292|115"))
        assertNull(WheelKey.decode("x|115|simulate-keys"))
    }
}
