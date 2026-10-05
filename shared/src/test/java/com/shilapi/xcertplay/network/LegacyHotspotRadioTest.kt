package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class LegacyHotspotRadioTest {
    @Test fun decodesDriverFrequencyWithoutUsingStationChannel() {
        assertEquals(5745, LegacyHotspotRadio.decodeFrequency(574500000, 1, null))
        assertEquals(5180, LegacyHotspotRadio.decodeFrequency(518, 7, null))
        assertEquals(2412, LegacyHotspotRadio.decodeFrequency(2412, 6, null))
    }
    @Test fun channelOnlyNeedsAnExplicitMatchingBand() {
        assertEquals(5745, LegacyHotspotRadio.decodeFrequency(149, 0, "5 GHz"))
        assertEquals(2484, LegacyHotspotRadio.decodeFrequency(14, 0, "2.4 GHz"))
        assertNull(LegacyHotspotRadio.decodeFrequency(149, 0, null))
        assertNull(LegacyHotspotRadio.decodeFrequency(149, 0, "2.4 GHz"))
        assertNull(LegacyHotspotRadio.decodeFrequency(6, 0, "5 GHz"))
    }
    @Test fun rejectsAutomaticMalformedAndOverflowValues() {
        for ((m,e) in listOf(0 to 0, -1 to 0, 5180 to -1, Int.MAX_VALUE to 9, 574500001 to 1, 5180 to 10))
            assertNull(LegacyHotspotRadio.decodeFrequency(m,e,null))
    }
    @Test fun channelChangesAndMissingReadsRestartSettling() {
        val tracker = LegacyHotspotRadio.Settled()
        assertNull(tracker.observe(5180, 0))
        assertNull(tracker.observe(5745, 1500))
        assertNull(tracker.observe(5745, 3000))
        assertEquals(5745, tracker.observe(5745, 3500))
        assertNull(tracker.observe(null, 4000))
        assertNull(tracker.observe(5745, 4500))
        assertEquals(5745, tracker.observe(5745, 6500))
    }
}
