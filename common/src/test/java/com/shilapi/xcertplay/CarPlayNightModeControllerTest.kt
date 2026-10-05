package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayNightModeControllerTest {
    private class Light(override val available: Boolean = true, val succeeds: Boolean = true) :
        CarPlayNightModeController.LightSource {
        var receiver: ((Float) -> Unit)? = null
        var starts = 0
        var stops = 0
        override fun start(onLux: (Float) -> Unit): Boolean {
            starts++
            receiver = onLux
            return succeeds
        }
        override fun stop() {
            stops++
            receiver = null
        }
        fun emit(lux: Float) { receiver?.invoke(lux) }
    }

    private class Clock : CarPlayNightModeController.Scheduler {
        var now = 0L
        val tasks = mutableMapOf<Runnable, Long>()
        override fun postDelayed(task: Runnable, delayMillis: Long) { tasks[task] = now + delayMillis }
        override fun remove(task: Runnable) { tasks.remove(task) }
        fun advance(millis: Long) {
            now += millis
            tasks.filterValues { it <= now }.keys.toList().forEach { task ->
                tasks.remove(task)
                task.run()
            }
        }
    }

    private class Fixture(initialNight: Boolean = false, val light: Light = Light()) {
        val clock = Clock()
        val output = mutableListOf<Boolean>()
        val controller = CarPlayNightModeController(light, clock, initialNight, output::add)
        init {
            controller.configure(CarPlayNightMode.AMBIENT, initialNight, AmbientLightThreshold(50), 5)
            controller.resume(initialNight)
        }
    }

    @Test fun stableOnChangeReadingTransitionsOnlyAfterFiveSeconds() {
        val f = Fixture()
        f.light.emit(19f)
        f.clock.advance(4_999)
        assertFalse(f.controller.night)
        f.clock.advance(1)
        assertTrue(f.controller.night)
        assertEquals(listOf(true), f.output)
        f.light.emit(101f)
        f.clock.advance(4_999)
        assertTrue(f.controller.night)
        f.clock.advance(1)
        assertFalse(f.controller.night)
    }

    @Test fun repeatedReadingsDoNotRestartTheTimerOrDuplicateOutput() {
        val f = Fixture()
        f.light.emit(1f)
        f.clock.advance(3_000)
        f.light.emit(10f)
        f.clock.advance(2_000)
        f.light.emit(1f)
        f.clock.advance(10_000)
        assertEquals(listOf(true), f.output)
    }

    @Test fun equalitySelectsDayAndBothSidesUseTheDelay() {
        val f = Fixture(true)
        f.light.emit(50f)
        f.clock.advance(4_999)
        assertTrue(f.controller.night)
        f.clock.advance(1)
        assertFalse(f.controller.night)
        f.light.emit(49f)
        f.clock.advance(4_999)
        assertFalse(f.controller.night)
        f.clock.advance(1)
        assertTrue(f.controller.night)
    }

    @Test fun interruptedOrInvalidReadingRequiresANewFullInterval() {
        for (reset in listOf(50f, 100f, 200f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val f = Fixture()
            f.light.emit(1f)
            f.clock.advance(4_000)
            f.light.emit(reset)
            f.clock.advance(2_000)
            assertFalse(f.controller.night)
            f.light.emit(1f)
            f.clock.advance(4_999)
            assertFalse(f.controller.night)
            f.clock.advance(1)
            assertTrue(f.controller.night)
        }
    }

    @Test fun pauseCancelsPendingAndIgnoresQueuedEventsUntilFreshResume() {
        val f = Fixture()
        f.light.emit(1f)
        val queuedReceiver = f.light.receiver!!
        f.clock.advance(4_000)
        f.controller.pause()
        queuedReceiver(1f)
        f.clock.advance(10_000)
        assertFalse(f.controller.night)
        assertTrue(f.clock.tasks.isEmpty())
        assertEquals(1, f.light.stops)
        f.controller.resume(false)
        f.light.emit(1f)
        f.clock.advance(4_999)
        assertFalse(f.controller.night)
        f.clock.advance(1)
        assertTrue(f.controller.night)
        f.controller.pause()
        f.controller.pause()
        assertEquals(2, f.light.stops)
    }

    @Test fun fixedAndSystemModesOverrideAmbientAndCancelItsTimer() {
        val f = Fixture()
        f.light.emit(1f)
        f.controller.configure(CarPlayNightMode.DAY, true)
        f.clock.advance(6_000)
        assertFalse(f.controller.night)
        f.controller.systemChanged(true)
        assertFalse(f.controller.night)
        f.controller.configure(CarPlayNightMode.NIGHT, false)
        f.controller.systemChanged(false)
        assertTrue(f.controller.night)
        f.controller.configure(CarPlayNightMode.SYSTEM, false)
        assertFalse(f.controller.night)
        f.controller.systemChanged(true)
        assertTrue(f.controller.night)
        assertEquals(1, f.light.starts)
    }

    @Test fun missingOrFailedSensorFallsBackToSystemAndCanRetryOnResume() {
        for (light in listOf(Light(available = false), Light(succeeds = false))) {
            val f = Fixture(light = light)
            f.controller.systemChanged(true)
            assertTrue(f.controller.night)
            f.controller.pause()
            f.controller.resume(false)
            assertFalse(f.controller.night)
            assertTrue(f.clock.tasks.isEmpty())
        }
    }

    @Test fun ambientIgnoresSystemChangesAndKeepsLastStateWhenResumed() {
        val f = Fixture(true)
        f.controller.systemChanged(false)
        f.controller.resume(false)
        assertEquals(1, f.light.starts)
        assertTrue(f.controller.night)
        f.controller.pause()
        f.controller.resume(false)
        f.light.emit(49f)
        f.clock.advance(10_000)
        assertTrue(f.controller.night)
    }

    @Test fun ambientDoesNotRegisterBeforeResumeOrInFixedModes() {
        val light = Light()
        val controller = CarPlayNightModeController(light, Clock(), false) {}
        controller.configure(CarPlayNightMode.AMBIENT, false)
        assertEquals(0, light.starts)
        controller.configure(CarPlayNightMode.NIGHT, false)
        controller.resume(false)
        assertEquals(0, light.starts)
        assertTrue(controller.night)
    }

    @Test fun interruptedBrightReadingAlsoRequiresFiveNewSeconds() {
        val f = Fixture(true)
        f.light.emit(101f)
        f.clock.advance(4_000)
        f.light.emit(49f)
        f.clock.advance(2_000)
        assertTrue(f.controller.night)
        f.light.emit(101f)
        f.clock.advance(4_999)
        assertTrue(f.controller.night)
        f.clock.advance(1)
        assertFalse(f.controller.night)
    }

    @Test fun customThresholdControlsBothDirectionsWithoutADeadBand() {
        val f = Fixture()
        f.controller.configure(CarPlayNightMode.AMBIENT, false, AmbientLightThreshold(200), 5)
        f.light.emit(150f)
        f.clock.advance(5_000)
        assertTrue(f.controller.night)
        f.light.emit(200f)
        f.clock.advance(5_000)
        assertFalse(f.controller.night)
    }

    @Test fun changingThresholdCancelsTheOldPendingTransition() {
        val f = Fixture()
        f.light.emit(10f)
        f.clock.advance(4_000)
        f.controller.configure(CarPlayNightMode.AMBIENT, false, AmbientLightThreshold(5), 5)
        f.clock.advance(2_000)
        assertFalse(f.controller.night)
        f.light.emit(4f)
        f.clock.advance(4_999)
        assertFalse(f.controller.night)
        f.clock.advance(1)
        assertTrue(f.controller.night)
    }

    @Test fun preferenceKeysAreStableAndUnknownValuesFollowSystem() {
        for (mode in CarPlayNightMode.entries) assertEquals(mode, CarPlayNightMode.fromKey(mode.key))
        assertEquals(CarPlayNightMode.SYSTEM, CarPlayNightMode.fromKey(null))
        assertEquals(CarPlayNightMode.SYSTEM, CarPlayNightMode.fromKey("future-mode"))
    }
    @Test fun defaultThresholdAndDelayApplyInBothDirections() {
        val f = Fixture()
        f.controller.configure(CarPlayNightMode.AMBIENT, false)
        f.light.emit(29f)
        f.clock.advance(1_999)
        assertFalse(f.controller.night)
        f.clock.advance(1)
        assertTrue(f.controller.night)
        f.light.emit(30f)
        f.clock.advance(1_999)
        assertTrue(f.controller.night)
        f.clock.advance(1)
        assertFalse(f.controller.night)
    }

    @Test fun customDelayAppliesInBothDirections() {
        val f = Fixture()
        f.controller.configure(CarPlayNightMode.AMBIENT, false, delaySeconds = 1)
        f.light.emit(10f)
        f.clock.advance(999)
        assertFalse(f.controller.night)
        f.clock.advance(1)
        assertTrue(f.controller.night)
        f.light.emit(60f)
        f.clock.advance(1_000)
        assertFalse(f.controller.night)
    }

    @Test fun zeroDelaySwitchesImmediatelyAndReconfigurationCancelsOldTimer() {
        val f = Fixture()
        f.light.emit(10f)
        f.clock.advance(4_000)
        f.controller.configure(CarPlayNightMode.AMBIENT, false, delaySeconds = 0)
        f.clock.advance(1_000)
        assertFalse(f.controller.night)
        f.light.emit(10f)
        assertTrue(f.controller.night)
        assertTrue(f.clock.tasks.isEmpty())
        f.light.emit(50f)
        assertFalse(f.controller.night)
    }

}
