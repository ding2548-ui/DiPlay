package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CustomResolutionPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clearPreferences() { context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().apply() }
    @Test fun customPercentMigratesOldSettingsAndSurvivesLegacySettingsSave() {
        AirPlayPersistence.saveDisplayScaleTenths(context, 6)
        assertEquals(60, AirPlayPersistence.loadDisplayScalePercent(context))
        AirPlayPersistence.saveDisplayScalePercent(context, 55)
        AirPlayPersistence.saveDisplayScaleTenths(context, 6)
        assertEquals(55, AirPlayPersistence.loadDisplayScalePercent(context))
    }

    @Test fun customPercentKeepsTheExtendedRange() {
        AirPlayPersistence.saveDisplayScalePercent(context, 150)
        assertEquals(150, AirPlayPersistence.loadDisplayScalePercent(context))
        AirPlayPersistence.saveDisplayScalePercent(context, CarPlayDisplayScale.MAX_PERCENT)
        assertEquals(CarPlayDisplayScale.MAX_PERCENT, AirPlayPersistence.loadDisplayScalePercent(context))
        AirPlayPersistence.saveDisplayScalePercent(context, CarPlayDisplayScale.MAX_PERCENT + 60)
        assertEquals(CarPlayDisplayScale.MAX_PERCENT, AirPlayPersistence.loadDisplayScalePercent(context))
        AirPlayPersistence.saveDisplayScalePercent(context, 10)
        assertEquals(30, AirPlayPersistence.loadDisplayScalePercent(context))
    }

    @Test fun directSavedValuesClampAndLegacyKeysRemainBounded() {
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().putInt("display_scale_percent", Int.MAX_VALUE).apply()
        assertEquals(160, AirPlayPersistence.loadDisplayScalePercent(context))
        prefs.edit().putInt("display_scale_percent", Int.MIN_VALUE).apply()
        assertEquals(30, AirPlayPersistence.loadDisplayScalePercent(context))
        prefs.edit().remove("display_scale_percent").putInt("display_scale_tenths", Int.MAX_VALUE).apply()
        assertEquals(100, AirPlayPersistence.loadDisplayScalePercent(context))
        prefs.edit().putInt("display_scale_tenths", Int.MIN_VALUE).apply()
        assertEquals(30, AirPlayPersistence.loadDisplayScalePercent(context))
    }

    @Test fun supersampledPercentSurvivesLegacySettingsSave() {
        AirPlayPersistence.saveDisplayScalePercent(context, 157)
        AirPlayPersistence.saveDisplayScaleTenths(context, 10)
        assertEquals(157, AirPlayPersistence.loadDisplayScalePercent(context))
    }

}
