package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiP2pLegacyChannelsTest {
    @Test fun fiveGhzChannelsMapToTheFrequencyTheFrameworkAccepts() {
        assertEquals(36, WifiP2pLegacyChannels.operatingChannelFor(5180))
        assertEquals(48, WifiP2pLegacyChannels.operatingChannelFor(5240))
        assertEquals(149, WifiP2pLegacyChannels.operatingChannelFor(5745))
        assertEquals(157, WifiP2pLegacyChannels.operatingChannelFor(5785))
        assertEquals(165, WifiP2pLegacyChannels.operatingChannelFor(5825))
    }

    @Test fun twoGhzChannelsStayInsideTheRangeTheFrameworkAccepts() {
        assertEquals(1, WifiP2pLegacyChannels.operatingChannelFor(2412))
        assertEquals(6, WifiP2pLegacyChannels.operatingChannelFor(2437))
        assertEquals(11, WifiP2pLegacyChannels.operatingChannelFor(2462))
    }

    @Test fun radarChannelsAreRefusedBecauseTheRadioHasToLeaveThem() {
        assertNull(WifiP2pLegacyChannels.operatingChannelFor(5260))
        assertNull(WifiP2pLegacyChannels.operatingChannelFor(5320))
        assertNull(WifiP2pLegacyChannels.operatingChannelFor(5500))
        assertNull(WifiP2pLegacyChannels.operatingChannelFor(5700))
    }

    @Test fun frequenciesOutsideWifiDirectAreRefused() {
        assertNull(WifiP2pLegacyChannels.operatingChannelFor(2484))
        assertNull(WifiP2pLegacyChannels.operatingChannelFor(5885))
        assertNull(WifiP2pLegacyChannels.operatingChannelFor(5955))
        assertNull(WifiP2pLegacyChannels.operatingChannelFor(1000))
        assertNull(WifiP2pLegacyChannels.operatingChannelFor(0))
    }

    @Test fun bandLabelsMatchTheChannelTheDiagnosticsReport() {
        assertEquals("5 GHz", WifiP2pLegacyChannels.bandLabelFor(36))
        assertEquals("5 GHz", WifiP2pLegacyChannels.bandLabelFor(165))
        assertEquals("2.4 GHz", WifiP2pLegacyChannels.bandLabelFor(1))
        assertEquals("2.4 GHz", WifiP2pLegacyChannels.bandLabelFor(11))
    }

    @Test fun theListenChannelStaysInTheTwoGhzRangeTheFrameworkAccepts() {
        assertEquals(6, WifiP2pLegacyChannels.LISTEN_CHANNEL)
    }
}