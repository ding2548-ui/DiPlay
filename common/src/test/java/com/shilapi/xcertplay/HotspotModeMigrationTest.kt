package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HotspotModeMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Test fun oldLocalSelectionMigratesWithoutLosingCarHotspotDetails() {
        prefs.edit().putString("wireless_hotspot_mode", "LOCAL_ONLY_HOTSPOT").apply()
        AirPlayPersistence.saveManualHotspotSsid(context, "Test car")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "test-password")
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("MANUAL", prefs.getString("wireless_hotspot_mode", null))
        assertEquals("Test car", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("test-password", AirPlayPersistence.loadManualHotspotPassphrase(context))
    }

    @Test fun freshInstallUsesBuiltInHotspot() {
        prefs.edit().clear().apply()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test fun existingWifiDirectSelectionIsPreserved() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test @Config(sdk = [28]) fun olderAndroidDoesNotFallBackToRemovedLocalMode() {
        prefs.edit().putString("wireless_hotspot_mode", "WIFI_P2P").apply()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
    }
}
