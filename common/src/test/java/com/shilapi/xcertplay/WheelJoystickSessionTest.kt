package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.time.Duration
import java.net.Socket
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercise the production session supplier across primary-screen and phone lifecycles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelJoystickSessionTest {
    private lateinit var service: WheelKeyService
    private lateinit var controller: CarPlayController
    private lateinit var sink: AndroidMediaSink
    private val phones = mutableListOf<AirPlaySession>()

    @Before fun setUp() {
        service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        service.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
        controller = CarPlayController(service,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {}, {})
        sink = AndroidMediaSink()
        CarPlayBackgroundSession.store(controller, sink, 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {}
        WheelZoomSettings.setEnabled(service, false)
        WheelZoomSettings.setJoystick(service, true)
        WheelZoomSettings.setJoystickAutoOff(service, false)
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.JOYSTICK, WheelKey(KeyEvent.KEYCODE_F4, 0, "?"))
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.NEXT, WheelKey(KeyEvent.KEYCODE_F5, 0, "?"))
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
    }

    @After fun tearDown() {
        service.onDestroy()
        CarPlayBackgroundSession.clear()
        controller.close()
        controller.awaitClosed(1000)
        phones.forEach { it.close() }
        sink.close()
    }

    private fun press(code: Int): Pair<Boolean, Boolean> {
        val method = service.javaClass.getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
        fun key(action: Int) = method.invoke(service, KeyEvent(0, 0, action, code, 0, 0, -1, 0)) as Boolean
        return key(KeyEvent.ACTION_DOWN) to key(KeyEvent.ACTION_UP)
    }

    private fun attachPhone(phone: AirPlaySession?) {
        controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, phone)
    }

    private fun newPhone(withMain: Boolean = true, teardownObserved: (AirPlaySession, Int) -> Unit = { _, _ -> }): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            cluster = AirPlayDisplayConfig(widthPixels = 1600, heightPixels = 600)),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {
            override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int = 1
            override fun onTeardown(session: AirPlaySession, type: Int) = teardownObserved(session, type)
        },
    ).also { phones += it; if (withMain) setup(it, 110L) }

    private fun setup(phone: AirPlaySession, type: Long) {
        phone.javaClass.getDeclaredMethod("handleStreams", List::class.java).apply { isAccessible = true }
            .invoke(phone, listOf(mapOf("type" to type)))
    }

    private fun teardown(phone: AirPlaySession, type: Long) {
        val request = RtspMessage.Request("TEARDOWN", "*", "RTSP/1.0", emptyMap(),
            BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to type)))))
        phone.javaClass.getDeclaredMethod("handleTeardown", RtspMessage.Request::class.java).apply { isAccessible = true }
            .invoke(phone, request)
    }

    private fun key(code: Int, down: Boolean, repeat: Int = 0): Boolean = service.javaClass
        .getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
        .invoke(service, KeyEvent(0, 0, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, repeat, 0, -1, 0)) as Boolean

    private fun pauseKnobExecutor(): PausedExecutor {
        val field = controller.javaClass.getDeclaredField("touchExecutor").apply { isAccessible = true }
        (field.get(controller) as ExecutorService).shutdownNow()
        return PausedExecutor().also { field.set(controller, it) }
    }

    private fun observedPhone(): AirPlaySession = spy(newPhone()).also { phones += it }

    @Test fun aControllerWaitingForAnIphoneMustNotTakeTheCarsMediaKey() {
        assertEquals("A live controller without an active iPhone is not a connected CarPlay session",
            false to false, press(KeyEvent.KEYCODE_F4))
    }

    @Test fun aNewIphoneInsideTheSameControllerMustNotInheritJoystickMode() {
        attachPhone(newPhone())
        assertEquals(true to true, press(KeyEvent.KEYCODE_F4))
        attachPhone(newPhone())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals("Replacing the active phone must restore the keys even when the controller is reused",
            false to false, press(KeyEvent.KEYCODE_F5))
    }

    @Test fun aRecordedPhoneWithoutAMainScreenDoesNotOwnTheWheel() {
        attachPhone(newPhone(withMain = false))
        assertNull(controller.activeAirPlaySessionToken())
        assertEquals(false to false, press(KeyEvent.KEYCODE_F4))
    }

    @Test fun mainScreenTeardownAndResetupRestoreKeysWithoutAnyInterveningPress() {
        val phone = newPhone()
        attachPhone(phone)
        val original = controller.activeAirPlaySessionToken()
        assertNotNull(original)
        assertEquals(true to true, press(KeyEvent.KEYCODE_F4))
        teardown(phone, 110L)
        assertNull(controller.activeAirPlaySessionToken())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        setup(phone, 110L)
        assertNotSame(original, controller.activeAirPlaySessionToken())
        assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
    }

    @Test fun mainScreenTeardownRestoresKeysBeforeMediaResourceShutdown() {
        var called = false
        val phone = newPhone(teardownObserved = { _, type ->
            if (type == 110) {
                called = true
                assertNull(controller.activeAirPlaySessionToken())
                assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
            }
        })
        attachPhone(phone)
        assertEquals(true to true, press(KeyEvent.KEYCODE_F4))
        teardown(phone, 110L)
        assertTrue(called)
    }

    @Test fun replacementMainScreenResetsJoystickButPreservesAHeldKeysRelease() {
        val phone = newPhone()
        attachPhone(phone)
        assertEquals(true to true, press(KeyEvent.KEYCODE_F4))
        assertTrue(key(KeyEvent.KEYCODE_F5, true))
        setup(phone, 110L)
        assertTrue(key(KeyEvent.KEYCODE_F5, true, repeat = 1))
        assertTrue(key(KeyEvent.KEYCODE_F5, false))
        assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
    }

    @Test fun aClusterContentStreamReplacementDoesNotResetTheMainScreenJoystick() {
        val phone = newPhone()
        attachPhone(phone)
        val original = controller.activeAirPlaySessionToken()
        assertEquals(true to true, press(KeyEvent.KEYCODE_F4))
        setup(phone, 111L)
        teardown(phone, 111L)
        setup(phone, 111L)
        assertSame(original, controller.activeAirPlaySessionToken())
        assertEquals(true to true, press(KeyEvent.KEYCODE_F5))
    }

    @Test fun aClosedPhoneCannotKeepWheelKeysEvenBeforeTheControllerPointerIsCleared() {
        val phone = newPhone()
        attachPhone(phone)
        assertEquals(true to true, press(KeyEvent.KEYCODE_F4))
        phone.close() // The fixture listener leaves the stale activeSession pointer deliberately.
        assertNull(controller.activeAirPlaySessionToken())
        assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
    }

    @Test fun aClosedControllerDoesNotOwnTheWheelWhileItsSnapshotIsStillStored() {
        attachPhone(newPhone())
        assertEquals(true to true, press(KeyEvent.KEYCODE_F4))
        controller.close()
        assertNull(controller.activeAirPlaySessionToken())
        assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
    }

    @Test fun queuedMovementAndSelectAreDroppedWhenThePhoneIsReplaced() {
        val queue = pauseKnobExecutor()
        val first = observedPhone()
        val second = observedPhone()
        attachPhone(first)
        val movement = AirPlayKnobState(wheel = 1)
        val select = AirPlayKnobState(select = true)
        assertTrue(controller.sendKnob(movement))
        assertTrue(controller.sendKnob(select, momentary = false))
        attachPhone(second)
        queue.runPending()
        verify(first, never()).sendKnob(movement, true)
        verify(first, never()).sendKnob(select, false)
        verify(second, never()).sendKnob(movement, true)
        verify(second, never()).sendKnob(select, false)
        assertTrue(controller.sendKnob(movement))
        queue.runPending()
        verify(second).sendKnob(movement, true)
    }

    @Test fun queuedMovementAndSelectAreDroppedWhenThePrimaryStreamIsReplaced() {
        val queue = pauseKnobExecutor()
        val phone = observedPhone()
        attachPhone(phone)
        val movement = AirPlayKnobState(wheel = -1)
        val select = AirPlayKnobState(select = true)
        assertTrue(controller.sendKnob(movement))
        assertTrue(controller.sendKnob(select, momentary = false))
        setup(phone, 110L)
        queue.runPending()
        verify(phone, never()).sendKnob(movement, true)
        verify(phone, never()).sendKnob(select, false)
        assertTrue(controller.sendKnob(select, momentary = false))
        queue.runPending()
        verify(phone).sendKnob(select, false)
    }

    @Test fun aClosedPhoneDropsAlreadyQueuedInputAndRejectsNewInput() {
        val queue = pauseKnobExecutor()
        val phone = observedPhone()
        attachPhone(phone)
        val select = AirPlayKnobState(select = true)
        assertTrue(controller.sendKnob(select, momentary = false))
        phone.close()
        queue.runPending()
        verify(phone, never()).sendKnob(select, false)
        assertFalse(controller.sendKnob(select, momentary = false))
    }

    @Test fun controllerCloseDropsAnInputTaskWhichWasAlreadyDequeued() {
        val queue = pauseKnobExecutor()
        val phone = observedPhone()
        attachPhone(phone)
        val movement = AirPlayKnobState(wheel = 1)
        assertTrue(controller.sendKnob(movement))
        val dequeued = queue.takePending()
        controller.close()
        dequeued.forEach { it.run() }
        verify(phone, never()).sendKnob(movement, true)
        assertFalse(controller.sendKnob(movement))
    }

    private class PausedExecutor : AbstractExecutorService() {
        private val tasks = mutableListOf<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            tasks += command
        }
        fun takePending(): List<Runnable> = tasks.toList().also { tasks.clear() }
        fun runPending() = takePending().forEach { it.run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return takePending().toMutableList()
        }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }
}
