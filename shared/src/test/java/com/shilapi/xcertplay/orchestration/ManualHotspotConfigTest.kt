package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualHotspotConfigTest {
    @Test
    fun autoBandAcceptsBoth2GhzAnd5GhzChannels() {
        assertTrue(isManualHotspotChannelCompatible(ManualHotspotBand.AUTO, 6))
        assertTrue(isManualHotspotChannelCompatible(ManualHotspotBand.AUTO, 36))
    }

    @Test
    fun explicitBandRejectsChannelsFromTheOtherBand() {
        assertTrue(isManualHotspotChannelCompatible(ManualHotspotBand.GHZ_2_4, 11))
        assertFalse(isManualHotspotChannelCompatible(ManualHotspotBand.GHZ_2_4, 36))
        assertTrue(isManualHotspotChannelCompatible(ManualHotspotBand.GHZ_5, 149))
        assertFalse(isManualHotspotChannelCompatible(ManualHotspotBand.GHZ_5, 11))
    }

    @Test
    fun wiredSessionIgnoresAnUnfinishedManualHotspot() {
        // Manual hotspot chosen for wireless, but its name never entered: USB must still start.
        val config = config(CarPlayTransport.WIRED, ssid = "")

        assertEquals(CarPlayTransport.WIRED, config.transport)
    }

    @Test
    fun wirelessSessionStillRequiresTheManualHotspotName() {
        assertThrows(IllegalArgumentException::class.java) {
            config(CarPlayTransport.WIRELESS, ssid = "")
        }
    }

    @Test
    fun wirelessSessionAcceptsACompleteManualHotspot() {
        val config = config(CarPlayTransport.WIRELESS, ssid = "Car Wi-Fi", passphrase = "12345678")

        assertEquals("Car Wi-Fi", config.manualHotspotSsid)
    }

    private fun config(
        transport: CarPlayTransport,
        ssid: String?,
        passphrase: String? = null,
    ): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.LOCAL,
        identification = Iap2IdentificationConfig(
            name = "test",
            modelIdentifier = "test",
            manufacturer = "test",
            serialNumber = "test",
            firmwareVersion = "1",
            hardwareVersion = "1",
            carPlayUsbInterfaceNumber = 3,
        ),
        transport = transport,
        wirelessHotspotMode = WirelessHotspotMode.MANUAL,
        manualHotspotSsid = ssid,
        manualHotspotPassphrase = passphrase,
    )
}
