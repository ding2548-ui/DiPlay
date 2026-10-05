package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class LocalOnlyHotspotRadioInfoTest {
    private val mac = "00:11:22:33:44:55"

    @Test fun waitsForTheDriverChannelSwitchToSettleBeforeAdvertising() {
        val requested = LocalOnlyHotspotRadioInfo.Radio(mac, 5200)
        val actual = LocalOnlyHotspotRadioInfo.Radio(mac, 5745)
        assertNull(LocalOnlyHotspotRadioInfo.settledRadio(listOf(requested), mac, 500))
        assertNull(LocalOnlyHotspotRadioInfo.settledRadio(listOf(actual), mac, 1_999))
        assertEquals(actual, LocalOnlyHotspotRadioInfo.settledRadio(listOf(actual), mac, 2_000))
    }

    @Test fun usesObservedApChannelRatherThanAutomaticConfigurationOrOtherRadio() {
        val ap = LocalOnlyHotspotRadioInfo.Radio(mac, 2437)
        val other = LocalOnlyHotspotRadioInfo.Radio("00:11:22:33:44:66", 5180)
        val observed = LocalOnlyHotspotRadioInfo.matchingRadio(listOf(other, ap), mac)
        assertEquals(6, wifiFrequencyMhzToChannel(observed!!.frequencyMHz))
        assertNull(LocalOnlyHotspotRadioInfo.matchingRadio(listOf(other), mac))
    }

    @Test fun neverUsesAnUnknownOrAmbiguousLiveChannel() {
        assertNull(LocalOnlyHotspotRadioInfo.matchingRadio(listOf(LocalOnlyHotspotRadioInfo.Radio(mac, 0)), mac))
        val ap = LocalOnlyHotspotRadioInfo.Radio(mac, 5180)
        assertNull(LocalOnlyHotspotRadioInfo.matchingRadio(listOf(ap), null))
        assertNull(LocalOnlyHotspotRadioInfo.matchingRadio(listOf(ap, ap.copy(frequencyMHz = 5200)), mac))
        assertEquals(36, wifiFrequencyMhzToChannel(LocalOnlyHotspotRadioInfo.matchingRadio(listOf(ap), mac)!!.frequencyMHz))
    }
}
