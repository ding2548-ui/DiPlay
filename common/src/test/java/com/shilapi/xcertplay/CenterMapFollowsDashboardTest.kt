package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CenterMapFollowsDashboardTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().apply()
    }

    @Test fun defaultKeepsHomeScreenMapInSync() {
        assertTrue(AirPlayPersistence.loadCenterMapFollowsDashboard(context))
    }

    @Test fun separateDisplayCanBeSaved() {
        AirPlayPersistence.saveCenterMapFollowsDashboard(context, false)
        assertFalse(AirPlayPersistence.loadCenterMapFollowsDashboard(context))
        AirPlayPersistence.saveCenterMapFollowsDashboard(context, true)
        assertTrue(AirPlayPersistence.loadCenterMapFollowsDashboard(context))
    }
}
