package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.view.KeyEvent
import android.widget.Button
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelKeyServiceTest {
    private lateinit var service: WheelKeyService
    private var route: Any? = "visible-phone-one-map"
    private var learned = 0

    @Before fun setUp() {
        service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        service.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
        service.mapRoute = { route }
        WheelZoomSettings.setEnabled(service, true)
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.MODE, WheelKey(KeyEvent.KEYCODE_F1, 0, "?"))
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.ZOOM_IN, WheelKey(KeyEvent.KEYCODE_F2, 0, "?"))
        connect()
        service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
    }

    @After fun tearDown() { service.onDestroy() }

    private val knobs = mutableListOf<com.shilapi.xcertplay.airplay.AirPlayKnobState>()
    private var phone: Any? = "phone-one"
    private var routeStarted = false

    private fun joystickSetUp() {
        service.session = { phone }
        service.knob = { knobs.add(it) }
        service.routeActive = { routeStarted }
        WheelZoomSettings.setJoystick(service, true)
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.JOYSTICK, WheelKey(KeyEvent.KEYCODE_F4, 0, "?"))
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.NEXT, WheelKey(KeyEvent.KEYCODE_F5, 0, "?"))
    }

    private fun press(code: Int): Pair<Boolean, Boolean> = key(code, true) to key(code, false)

    private fun connect() {
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
    }

    private fun key(code: Int, down: Boolean, repeat: Int = 0): Boolean = service.javaClass
        .getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }.invoke(service,
            KeyEvent(0, 0, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, repeat, 0, -1, 0),
        ) as Boolean

    private fun zoomOn() {
        assertTrue(key(KeyEvent.KEYCODE_F1, true))
        assertTrue(key(KeyEvent.KEYCODE_F1, false))
    }

    private fun learn(cancelled: () -> Unit = {}): Boolean =
        WheelKeyService.learn(WheelZoomSettings.Role.MODE, cancelled) { _, _ -> learned++ }

    @Test fun disabledSwitchCancelsLearningAndRestoresItsVisiblePrompt() {
        val assign = Button(service).apply { text = "Press a key" }
        assertTrue(learn { assign.text = "Assign" })
        WheelZoomSettings.setEnabled(service, false)
        assertEquals("Assign", assign.text.toString())
        assertFalse(key(KeyEvent.KEYCODE_F3, true))
        assertFalse(key(KeyEvent.KEYCODE_F3, false))
        assertEquals(0, learned)
        assertEquals(KeyEvent.KEYCODE_F1, WheelZoomSettings.key(service, WheelZoomSettings.Role.MODE).code)
    }

    @Test fun cancelAndTimeoutDoNotLeaveTheNextKeyCaptured() {
        var cancelled = 0
        assertTrue(learn { cancelled++ })
        WheelKeyService.cancelLearning()
        assertFalse(key(KeyEvent.KEYCODE_F3, true))
        assertFalse(key(KeyEvent.KEYCODE_F3, false))
        assertTrue(learn { cancelled++ })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WheelKeyService.LEARNING_TIMEOUT_MILLIS))
        assertEquals(2, cancelled)
        assertFalse(key(KeyEvent.KEYCODE_F3, true))
        assertFalse(key(KeyEvent.KEYCODE_F3, false))
        assertEquals(0, learned)
    }

    @Test fun completedLearningOwnsRepeatsAndReleaseEvenAfterCancellationAndDisable() {
        assertTrue(learn())
        assertTrue(key(KeyEvent.KEYCODE_F3, true))
        WheelKeyService.cancelLearning()
        WheelZoomSettings.setEnabled(service, false)
        assertTrue(key(KeyEvent.KEYCODE_F3, true, repeat = 1))
        assertTrue(key(KeyEvent.KEYCODE_F3, false))
        assertFalse(key(KeyEvent.KEYCODE_F3, true))
        assertFalse(key(KeyEvent.KEYCODE_F3, false))
        assertEquals(1, learned)
    }

    @Test fun unbindingCancelsLearningAndRebindingDoesNotReviveIt() {
        var cancelled = 0
        assertTrue(learn { cancelled++ })
        service.onUnbind(Intent())
        assertFalse(WheelKeyService.connected())
        connect()
        assertEquals(1, cancelled)
        assertFalse(key(KeyEvent.KEYCODE_F3, true))
        assertFalse(key(KeyEvent.KEYCODE_F3, false))
        assertEquals(0, learned)
    }

    @Test fun pollingEndsZoomWithoutAKeyAndReconnectKeepsVolume() {
        zoomOn()
        route = null
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        route = "visible-phone-two-map"
        assertFalse(key(KeyEvent.KEYCODE_F2, true))
        assertFalse(key(KeyEvent.KEYCODE_F2, false))
    }

    @Test fun disablingAndReenablingEndsZoomButPreservesAHeldRelease() {
        zoomOn()
        assertTrue(key(KeyEvent.KEYCODE_F2, true))
        WheelZoomSettings.setEnabled(service, false)
        assertTrue(key(KeyEvent.KEYCODE_F2, false))
        WheelZoomSettings.setEnabled(service, true)
        assertFalse(key(KeyEvent.KEYCODE_F2, true))
        assertFalse(key(KeyEvent.KEYCODE_F2, false))
    }

    @Test fun callChangesPreserveCompletePressesAndCancelLearning() {
        zoomOn()
        val audio = service.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        assertFalse(key(KeyEvent.KEYCODE_F2, true))
        audio.mode = AudioManager.MODE_NORMAL
        assertFalse(key(KeyEvent.KEYCODE_F2, false))
        assertTrue(key(KeyEvent.KEYCODE_F2, true))
        audio.mode = AudioManager.MODE_RINGTONE
        assertTrue(key(KeyEvent.KEYCODE_F2, false))
        assertTrue(learn())
        assertFalse(key(KeyEvent.KEYCODE_F3, true))
        assertFalse(key(KeyEvent.KEYCODE_F3, false))
        assertEquals(0, learned)
    }

    @Test fun joystickKeysDriveTheKnobAndTheModeKeyGoesBackOnlyWhileItIsOn() {
        joystickSetUp()
        assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
        assertEquals(true to true, press(KeyEvent.KEYCODE_F4))
        assertEquals(true to true, press(KeyEvent.KEYCODE_F5))
        assertEquals(true to true, press(KeyEvent.KEYCODE_F2))
        assertEquals(true to true, press(KeyEvent.KEYCODE_F1))
        assertEquals(listOf(1, -1), knobs.take(2).map { it.wheel })
        assertTrue(knobs[2].back)
        assertEquals(true to true, press(KeyEvent.KEYCODE_F4))
        // With the joystick off the mode key switches the zoom again.
        zoomOn()
        assertEquals(3, knobs.size)
    }

    @Test fun joystickEndsAfterIdleAndWhenARouteStarts() {
        joystickSetUp()
        press(KeyEvent.KEYCODE_F4)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WheelZoomSettings.JOYSTICK_IDLE_MILLIS))
        assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
        press(KeyEvent.KEYCODE_F4)
        routeStarted = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
        assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
        assertTrue(knobs.isEmpty())
    }

    @Test fun joystickWithoutAutoOffStaysOnButEndsWithTheSettingOrANewPhone() {
        joystickSetUp()
        WheelZoomSettings.setJoystickAutoOff(service, false)
        press(KeyEvent.KEYCODE_F4)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WheelZoomSettings.JOYSTICK_IDLE_MILLIS * 2))
        assertEquals(true to true, press(KeyEvent.KEYCODE_F5))
        phone = "phone-two"
        assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
        press(KeyEvent.KEYCODE_F4)
        WheelZoomSettings.setJoystick(service, false)
        assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
        assertEquals(1, knobs.size)
    }

    @Test fun learningTakesTheNextKeyBeforeTheJoystick() {
        joystickSetUp()
        WheelZoomSettings.setEnabled(service, false)
        assertTrue(learn())
        assertEquals(true to true, press(KeyEvent.KEYCODE_F4))
        assertEquals(1, learned)
        assertEquals(KeyEvent.KEYCODE_F4, WheelZoomSettings.key(service, WheelZoomSettings.Role.MODE).code)
        assertTrue(knobs.isEmpty())
    }
}
