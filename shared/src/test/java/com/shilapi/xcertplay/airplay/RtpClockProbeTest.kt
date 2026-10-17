package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtpClockProbeTest {
    @Test fun measuresTheClockOnceAfterTheWindow() {
        val probe = RtpClockProbe(windowNs = 1_000_000_000L)
        assertNull(probe.observe(1_000, 0L))
        assertNull(probe.observe(1_000 + 8_000, 500_000_000L))
        assertEquals(16_000, probe.observe(1_000 + 16_000, 1_000_000_000L))
        assertNull(probe.observe(1_000 + 32_000, 2_000_000_000L))
    }

    @Test fun countsAcrossTheUnsignedTimestampWrap() {
        val probe = RtpClockProbe(windowNs = 1_000_000_000L)
        probe.observe(-24_000, 0L)
        assertEquals(48_000, probe.observe(24_000, 1_000_000_000L))
    }
}
