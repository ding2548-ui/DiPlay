package com.shilapi.xcertplay

import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExternalControllerKeysTest {
    private fun key(code: Int, deviceId: Int) = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, 0, 0, deviceId, 0)

    @Test fun onlyAPhysicalDeviceDrivesTheKnobWhenTheSettingIsOn() {
        assertTrue(CarPlayRemoteKeys.fromExternalController(key(KeyEvent.KEYCODE_DPAD_RIGHT, 7), enabled = true))
        assertFalse(CarPlayRemoteKeys.fromExternalController(key(KeyEvent.KEYCODE_DPAD_RIGHT, 7), enabled = false))
    }

    @Test fun theNavigationBarBackKeepsLeavingCarPlay() {
        assertFalse(CarPlayRemoteKeys.fromExternalController(
            key(KeyEvent.KEYCODE_BACK, KeyCharacterMap.VIRTUAL_KEYBOARD), enabled = true))
    }
}
