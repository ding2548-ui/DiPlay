package com.shilapi.xcertplay.media

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AmbientMusicSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun missingPreferencesKeepControlDisabled() {
        val values = AmbientMusicSettings.load(context)
        assertFalse(values.enabled)
        assertEquals(listOf(1), values.selectedColors)
        assertEquals(0, values.brightness)
        // The flow light defaults to multi-colour at the OEM's default angle.
        assertEquals(AmbientFlowMode.MULTI, values.flowMode)
        assertEquals(AmbientMusicSettings.FLOW_BRIGHTNESS_DEFAULT, values.flowBrightness)
        assertEquals(3, values.area)
    }

    @Test fun theCarsOwnRhythmIsOffUntilSavedOnAndSurvivesASave() {
        assertFalse(AmbientMusicSettings.load(context).carRhythm)
        AmbientMusicSettings.save(context, AmbientMusicSettings.load(context).copy(carRhythm = true))
        assertTrue(AmbientMusicSettings.load(context).carRhythm)
        // Saving the lamp settings keeps it.
        AmbientMusicSettings.save(context, AmbientMusicSettings.load(context).copy(brightness = 4))
        assertTrue(AmbientMusicSettings.load(context).carRhythm)
    }

    @Test fun oldSingleColorSettingsSupplyMissingPaletteAndModes() {
        prefs.edit().putInt("color", 12).putInt("brightness", 4).commit()
        val values = AmbientMusicSettings.load(context)
        assertFalse(values.enabled)
        assertEquals(AmbientColorSource.SELECTED, values.colorSource)
        assertEquals(listOf(12), values.selectedColors)
        assertEquals(AmbientColorMode.BEAT, values.colorMode)
        assertEquals(AmbientColorSpeed.STANDARD, values.speed)
        // A stored BYD brightness is preserved, but it never becomes the Leapmotor angle.
        assertEquals(4, values.brightness)
        assertEquals(AmbientMusicSettings.FLOW_BRIGHTNESS_DEFAULT, values.flowBrightness)
    }

    @Test fun flowModeRoundTripsAndAngleIsClampedToTheProtocolRange() {
        val saved = AmbientMusicSettings.Values(enabled = true, flowMode = AmbientFlowMode.SINGLE, flowBrightness = 7)
        AmbientMusicSettings.save(context, saved)
        val reloaded = AmbientMusicSettings.load(context)
        assertEquals(AmbientFlowMode.SINGLE, reloaded.flowMode)
        assertEquals(7, reloaded.flowBrightness)

        // 0 is a valid level on the OEM lampDeg scale, and out-of-range values are clamped.
        prefs.edit().putString("flowMode", "SINGLE").putInt("flowBrightness", 0).commit()
        assertEquals(0, AmbientMusicSettings.load(context).flowBrightness)
        prefs.edit().putString("flowMode", "SINGLE").putInt("flowBrightness", 99).commit()
        assertEquals(10, AmbientMusicSettings.load(context).flowBrightness)
    }

    @Test fun malformedPreferencesUseSafeDefaultsAndValidBounds() {
        prefs.edit().putString("enabled", "true").putString("music", "false")
            .putInt("color", 90).putInt("brightness", -2).putInt("area", 8)
            .putInt("selectedColors", 8).putString("colorMode", "UNKNOWN")
            .putString("flowMode", "UNKNOWN").putInt("flowBrightness", -5)
            .putString("speed", "UNKNOWN").putString("colorSource", "UNKNOWN").commit()
        val values = AmbientMusicSettings.load(context)
        assertFalse(values.enabled)
        assertTrue(values.music)
        assertEquals(AmbientColorSource.SELECTED, values.colorSource)
        assertEquals(listOf(31), values.selectedColors)
        assertEquals(0, values.brightness)
        assertEquals(AmbientFlowMode.MULTI, values.flowMode)
        assertEquals(0, values.flowBrightness)
        assertEquals(3, values.area)
        assertEquals(AmbientColorMode.BEAT, values.colorMode)
        assertEquals(AmbientColorSpeed.STANDARD, values.speed)
        assertEquals(AmbientIdleBehavior.STEADY, values.idle)
    }

    @Test fun idleChoiceRoundTripsAndUnknownValuesFallBackToTheSteadyGlow() {
        AmbientMusicSettings.save(context, AmbientMusicSettings.Values(idle = AmbientIdleBehavior.CAR))
        assertEquals(AmbientIdleBehavior.CAR, AmbientMusicSettings.load(context).idle)
        prefs.edit().putString("idle", "UNKNOWN").commit()
        assertEquals(AmbientIdleBehavior.STEADY, AmbientMusicSettings.load(context).idle)
    }
}
