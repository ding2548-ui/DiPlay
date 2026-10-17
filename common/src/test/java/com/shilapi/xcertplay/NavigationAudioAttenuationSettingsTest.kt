package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.NavigationAudioGain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29, 33])
class NavigationAudioAttenuationSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun defaultIsUnchangedAndSavedValuesPersistWithinBounds() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(0, AirPlayPersistence.loadNavigationAudioAttenuationDb(context))
        AirPlayPersistence.saveNavigationAudioAttenuationDb(context, -13)
        assertEquals(-13, AirPlayPersistence.loadNavigationAudioAttenuationDb(context))
        AirPlayPersistence.saveNavigationAudioAttenuationDb(context, -100)
        assertEquals(NavigationAudioGain.MIN_DB, AirPlayPersistence.loadNavigationAudioAttenuationDb(context))
        AirPlayPersistence.saveNavigationAudioAttenuationDb(context, 20)
        assertEquals(NavigationAudioGain.MAX_DB, AirPlayPersistence.loadNavigationAudioAttenuationDb(context))
    }
}
