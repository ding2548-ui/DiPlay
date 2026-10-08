package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAppearanceTest {
    private val schedule = CarPlayNightSchedule(startMinute = 18 * 60, endMinute = 6 * 60)

    @After fun resetRuntime() = AppAppearanceRuntime.resetForTest()

    @Test fun stableKeysRoundTripAndUnknownValuesDefaultToDark() {
        assertEquals(AppAppearance.DARK, AppAppearance.fromKey(null))
        assertEquals(AppAppearance.DARK, AppAppearance.fromKey("future-value"))
        AppAppearance.entries.forEach { appearance ->
            assertEquals(appearance, AppAppearance.fromKey(appearance.key))
        }
    }

    @Test fun explicitAppearancesIgnoreCarPlayAndHostState() {
        for (mode in CarPlayNightMode.entries) {
            assertTrue(resolveAppNight(AppAppearance.DARK, mode, schedule, false, 12 * 60, false))
            assertFalse(resolveAppNight(AppAppearance.LIGHT, mode, schedule, true, 23 * 60, true))
        }
    }

    @Test fun autoUsesTheLiveHostResultWhenAvailable() {
        assertTrue(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.DAY, schedule, false, 12 * 60, true))
        assertFalse(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.NIGHT, schedule, true, 23 * 60, false))
    }

    @Test fun autoFallsBackToTheSavedCarPlayPolicyOutsideTheHost() {
        assertFalse(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.DAY, schedule, true, 23 * 60, null))
        assertTrue(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.NIGHT, schedule, false, 12 * 60, null))
        assertTrue(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.SYSTEM, schedule, true, 12 * 60, null))
        assertFalse(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.SYSTEM, schedule, false, 23 * 60, null))
        assertTrue(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.AMBIENT, schedule, true, 12 * 60, null))
        assertFalse(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.AMBIENT, schedule, false, 23 * 60, null))
        assertTrue(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.SCHEDULE, schedule, false, 23 * 60, null))
        assertTrue(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.SCHEDULE, schedule, false, 0, null))
        assertFalse(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.SCHEDULE, schedule, true, 12 * 60, null))
    }

    @Test fun runtimePublishesOnlyChangesAndProtectsTheCurrentOwner() {
        val firstOwner = Any()
        val secondOwner = Any()
        val observed = mutableListOf<Boolean?>()
        val stopObserving = AppAppearanceRuntime.observeHost(observed::add)

        assertEquals(listOf<Boolean?>(null), observed)
        AppAppearanceRuntime.publishHost(firstOwner, true)
        AppAppearanceRuntime.publishHost(firstOwner, true)
        AppAppearanceRuntime.publishHost(secondOwner, false)
        AppAppearanceRuntime.clearHost(firstOwner)
        assertEquals(false, AppAppearanceRuntime.hostNight())
        AppAppearanceRuntime.clearHost(secondOwner)
        assertNull(AppAppearanceRuntime.hostNight())
        assertEquals(listOf(null, true, false, null), observed)

        stopObserving()
        stopObserving()
        AppAppearanceRuntime.publishHost(firstOwner, true)
        assertEquals(listOf(null, true, false, null), observed)
    }

    @Test fun runtimeDoesNotHideObserverFailures() {
        val owner = Any()
        AppAppearanceRuntime.observeHost { night ->
            if (night != null) error("observer failed")
        }

        val failure = runCatching { AppAppearanceRuntime.publishHost(owner, true) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("observer failed", failure?.message)
    }

    @Test fun appearanceRepaintWaitsForEitherWheelKeyLearningPath() {
        assertFalse(shouldDeferAppearanceRender(windowKeyLearning = false, serviceKeyLearning = false))
        assertTrue(shouldDeferAppearanceRender(windowKeyLearning = true, serviceKeyLearning = false))
        assertTrue(shouldDeferAppearanceRender(windowKeyLearning = false, serviceKeyLearning = true))
        assertTrue(shouldDeferAppearanceRender(windowKeyLearning = true, serviceKeyLearning = true))
    }
}
