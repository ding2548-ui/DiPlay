package com.shilapi.xcertplay

import com.shilapi.xcertplay.WheelJoystick.Action
import com.shilapi.xcertplay.WheelZoomSettings.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelJoystickTest {
    private fun WheelJoystick.press(role: Role?, session: Boolean = true, inCall: Boolean = false): Action =
        onKey(role, session = session, inCall = inCall)

    @Test
    fun joystickKeyTurnsItOnAndOff() {
        val joystick = WheelJoystick()
        assertEquals(Action.PASS, joystick.press(Role.NEXT))
        assertEquals(Action.ON, joystick.press(Role.JOYSTICK))
        assertTrue(joystick.on)
        assertEquals(Action.OFF, joystick.press(Role.JOYSTICK))
        assertFalse(joystick.on)
        assertEquals(Action.PASS, joystick.press(Role.NEXT))
    }

    @Test
    fun keysMoveSelectAndGoBackWhileOn() {
        val joystick = WheelJoystick()
        joystick.press(Role.JOYSTICK)
        assertEquals(Action.PREVIOUS, joystick.press(Role.PREVIOUS))
        assertEquals(Action.NEXT, joystick.press(Role.NEXT))
        // The volume roller turns the knob as well: CarPlay moves its focus only with a turn.
        assertEquals(Action.PREVIOUS, joystick.press(Role.ZOOM_IN))
        assertEquals(Action.NEXT, joystick.press(Role.ZOOM_OUT))
        assertEquals(Action.SELECT, joystick.press(Role.SELECT))
        assertEquals(Action.BACK, joystick.press(Role.MODE))
        assertEquals(Action.PASS, joystick.press(null))
    }

    @Test
    fun withTheJoystickOffTheModeAndZoomKeysAreLeftToZoom() {
        val joystick = WheelJoystick()
        assertEquals(Action.PASS, joystick.press(Role.MODE))
        assertEquals(Action.PASS, joystick.press(Role.ZOOM_IN))
    }

    @Test
    fun withoutASessionTheJoystickKeyKeepsItsCarActionAndTheJoystickEnds() {
        val joystick = WheelJoystick()
        assertEquals(Action.PASS, joystick.press(Role.JOYSTICK, session = false))
        assertFalse(joystick.on)
        joystick.press(Role.JOYSTICK)
        assertEquals(Action.PASS, joystick.press(Role.NEXT, session = false))
        assertFalse(joystick.on)
    }

    @Test
    fun aCallKeepsEveryKeyForTheCall() {
        val joystick = WheelJoystick()
        joystick.press(Role.JOYSTICK)
        for (role in Role.entries) assertEquals(Action.PASS, joystick.press(role, inCall = true))
        assertTrue(joystick.on)
        assertEquals(Action.SELECT, joystick.press(Role.SELECT))
    }

    @Test
    fun aNewSessionEndsTheJoystick() {
        val joystick = WheelJoystick()
        assertFalse(joystick.updateSession("phone-one"))
        joystick.press(Role.JOYSTICK)
        assertFalse(joystick.updateSession("phone-one"))
        assertTrue(joystick.on)
        assertTrue(joystick.updateSession("phone-two"))
        assertFalse(joystick.on)
        assertFalse(joystick.updateSession(null))
    }

    @Test
    fun endingWorksOnce() {
        val joystick = WheelJoystick()
        assertFalse(joystick.end())
        joystick.press(Role.JOYSTICK)
        assertTrue(joystick.end())
        assertFalse(joystick.on)
        assertFalse(joystick.end())
    }
}
