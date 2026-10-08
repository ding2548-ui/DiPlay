package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CarPlayNightModePersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() { prefs.edit().clear().apply() }

    @Test fun freshInstallAndUnknownValuesFollowSystem() {
        assertEquals(CarPlayNightMode.SYSTEM, AirPlayPersistence.loadCarPlayNightMode(context))
        prefs.edit().putString("carplay_night_mode", "future-mode").apply()
        assertEquals(CarPlayNightMode.SYSTEM, AirPlayPersistence.loadCarPlayNightMode(context))
    }

    @Test fun thresholdDefaultAndCustomValuePersist() {
        assertEquals(AmbientLightThreshold(30), AirPlayPersistence.loadAmbientLightThreshold(context))
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.AMBIENT)
        AirPlayPersistence.saveAmbientLightThreshold(context, AmbientLightThreshold(200))
        assertEquals(AmbientLightThreshold(200), AirPlayPersistence.loadAmbientLightThreshold(context))
        assertEquals(CarPlayNightMode.AMBIENT, AirPlayPersistence.loadCarPlayNightMode(context))
        AirPlayPersistence.saveAmbientLightThreshold(context, AmbientLightThreshold())
        assertEquals(AmbientLightThreshold(), AirPlayPersistence.loadAmbientLightThreshold(context))
    }

    @Test fun invalidStoredThresholdRestoresTheDefault() {
        for (lux in listOf(-1, 0, 200_001)) {
            prefs.edit().putInt("ambient_lux_threshold", lux).apply()
            assertEquals(AmbientLightThreshold(), AirPlayPersistence.loadAmbientLightThreshold(context))
        }
    }

    @Test fun allModesRoundTripWithoutChangingOtherPreferences() {
        AirPlayPersistence.saveFps(context, 60)
        for (mode in CarPlayNightMode.entries) {
            AirPlayPersistence.saveCarPlayNightMode(context, mode)
            assertEquals(mode, AirPlayPersistence.loadCarPlayNightMode(context))
            assertEquals(60, AirPlayPersistence.loadFps(context))
        }
    }
    @Test fun scheduleTimesPersistAndInvalidStoredMinutesUseDefaults() {
        assertEquals(CarPlayNightSchedule(), AirPlayPersistence.loadCarPlayNightSchedule(context))
        val custom = CarPlayNightSchedule(19 * 60 + 30, 5 * 60 + 15)
        AirPlayPersistence.saveCarPlayNightSchedule(context, custom)
        assertEquals(custom, AirPlayPersistence.loadCarPlayNightSchedule(context))
        prefs.edit().putInt("carplay_night_start_minute", -1)
            .putInt("carplay_night_end_minute", 24 * 60).apply()
        assertEquals(CarPlayNightSchedule(), AirPlayPersistence.loadCarPlayNightSchedule(context))
    }
    @Test fun existingSavedDefaultsAreNotOverwritten() {
        prefs.edit().putInt("ambient_delay_seconds", 5).putInt("ambient_lux_threshold", 50).commit()
        assertEquals(5, AirPlayPersistence.loadAmbientDelaySeconds(context))
        assertEquals(AmbientLightThreshold(50), AirPlayPersistence.loadAmbientLightThreshold(context))
    }

    @Test fun transitionDelayPersistsIncludingImmediateSwitching() {
        assertEquals(2, AirPlayPersistence.loadAmbientDelaySeconds(context))
        AirPlayPersistence.saveAmbientDelaySeconds(context, 1)
        assertEquals(1, AirPlayPersistence.loadAmbientDelaySeconds(context))
        AirPlayPersistence.saveAmbientDelaySeconds(context, 0)
        assertEquals(0, AirPlayPersistence.loadAmbientDelaySeconds(context))
    }

}
