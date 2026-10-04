package com.shilapi.xcertplay

import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.AirPlayHid
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayRemoteKeysTest {
    @Test
    fun unrelatedKeyIsNotConsumedWithoutController() {
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_INFO)
        assertFalse(CarPlayRemoteKeys.dispatch(event, null))
    }

    @Test fun dpadDirectionsSendOnlySignedWheelMovementAndNeverCoordinateChanges() {
        val directions = listOf(
            KeyEvent.KEYCODE_DPAD_UP to -1,
            KeyEvent.KEYCODE_DPAD_LEFT to -1,
            KeyEvent.KEYCODE_DPAD_DOWN to 1,
            KeyEvent.KEYCODE_DPAD_RIGHT to 1,
        )
        for ((key, expected) in directions) {
            val states = mutableListOf<Pair<AirPlayKnobState, Boolean>>()
            val sender: (AirPlayKnobState, Boolean) -> Boolean = { state, momentary ->
                states += state to momentary
                true
            }
            assertTrue(CarPlayRemoteKeys.dispatchToKnob(KeyEvent(KeyEvent.ACTION_DOWN, key), sender))
            assertTrue(CarPlayRemoteKeys.dispatchToKnob(KeyEvent(KeyEvent.ACTION_UP, key), sender))
            assertEquals(listOf(AirPlayKnobState(wheel = expected) to true), states)
            assertArrayEquals(byteArrayOf(0, 0, 0, expected.toByte()), AirPlayHid.knobReport(states.single().first))
        }
    }

    @Test fun aHeldDirectionIsBoundedAndReleasesWithoutAnExtraWheelStep() {
        val states = mutableListOf<AirPlayKnobState>()
        val sender: (AirPlayKnobState, Boolean) -> Boolean = { state, _ -> states += state; true }
        for (repeat in 0..3) {
            assertTrue(CarPlayRemoteKeys.dispatchToKnob(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, repeat), sender))
        }
        assertTrue(CarPlayRemoteKeys.dispatchToKnob(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT), sender))
        assertEquals(listOf(AirPlayKnobState(wheel = 1), AirPlayKnobState(wheel = 1)), states)
    }

    @Test fun selectAndBackPreservePressAndReleaseWithoutRepeatingHeldPresses() {
        for ((key, state) in listOf(
            KeyEvent.KEYCODE_DPAD_CENTER to AirPlayKnobState(select = true),
            KeyEvent.KEYCODE_BACK to AirPlayKnobState(back = true),
        )) {
            val states = mutableListOf<Pair<AirPlayKnobState, Boolean>>()
            val sender: (AirPlayKnobState, Boolean) -> Boolean = { sent, momentary ->
                states += sent to momentary
                true
            }
            assertTrue(CarPlayRemoteKeys.dispatchToKnob(KeyEvent(KeyEvent.ACTION_DOWN, key), sender))
            assertTrue(CarPlayRemoteKeys.dispatchToKnob(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key, 1), sender))
            assertTrue(CarPlayRemoteKeys.dispatchToKnob(KeyEvent(KeyEvent.ACTION_UP, key), sender))
            assertEquals(listOf(state to false, AirPlayKnobState() to false), states)
            assertEquals(if (state.select) 1 else 4, AirPlayHid.knobReport(state)[0].toInt())
            assertArrayEquals(byteArrayOf(0, 0, 0, 0), AirPlayHid.knobReport(states.last().first))
        }
    }

    @Test fun unsuccessfulNavigationSendsRemainAvailableToAndroid() {
        val failed: (AirPlayKnobState, Boolean) -> Boolean = { _, _ -> false }
        assertFalse(CarPlayRemoteKeys.dispatchToKnob(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT), failed))
        assertFalse(CarPlayRemoteKeys.dispatchToKnob(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER), failed))
        assertFalse(CarPlayRemoteKeys.dispatchToKnob(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), failed))
    }
}
