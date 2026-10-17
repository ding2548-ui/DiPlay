package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AmbientMusicBandAnalyzerTest {
    private fun tone(hertz: Double): ByteArray {
        val bytes = ByteArray(48_000 * 4)
        for (frame in 0 until 48_000) {
            val sample = (sin(2 * PI * hertz * frame / 48_000) * 16_000).toInt()
            for (channel in 0..1) {
                val offset = frame * 4 + channel * 2
                bytes[offset] = sample.toByte(); bytes[offset + 1] = (sample shr 8).toByte()
            }
        }
        return bytes
    }

    @Test fun midBandPrefersItsOwnRangeOverNeighbours() {
        val mid = AmbientMusicBandAnalyzer(48_000, 2, 180.0, 2_000.0)
        val low = tone(80.0); val inBand = tone(700.0); val high = tone(6_000.0)
        val lowEnergy = mid.rms(low, 0, low.size)
        val inBandEnergy = mid.rms(inBand, 0, inBand.size)
        val highEnergy = mid.rms(high, 0, high.size)
        assertTrue(inBandEnergy > lowEnergy * 2)
        assertTrue(inBandEnergy > highEnergy * 2)
    }

    @Test fun trebleBandPrefersHighFrequencies() {
        val high = AmbientMusicBandAnalyzer(48_000, 2, 2_000.0, 8_000.0)
        val low = tone(100.0); val inBand = tone(5_000.0)
        assertTrue(high.rms(inBand, 0, inBand.size) > high.rms(low, 0, low.size) * 4)
    }

    @Test fun invalidSlicesReadNothing() {
        val analyzer = AmbientMusicBandAnalyzer(48_000, 2, 180.0, 2_000.0)
        val pcm = tone(700.0)
        assertEquals(0.0, analyzer.rms(pcm, pcm.size - 1, 4), 0.0)
        assertEquals(0.0, analyzer.rms(pcm, -1, 4), 0.0)
        assertEquals(0.0, analyzer.rms(ByteArray(4096), 0, 4096), 0.0)
    }

    @Test fun envelopeCarriesTheExtraBandsThroughThePlayedWindow() {
        val envelope = AmbientMusicEnvelope()
        envelope.append(0, 100, 0.5, 0.3, 0.2, 0.1)
        val energy = envelope.playedWindow(110, true)
        assertEquals(0.5, energy.rms, 1e-9)
        assertEquals(0.3, energy.bassRms, 1e-9)
        assertEquals(0.2, energy.midRms, 1e-9)
        assertEquals(0.1, energy.highRms, 1e-9)
    }

    @Test fun omittedBandsDefaultToZeroSoTheBydPathIsUnchanged() {
        val envelope = AmbientMusicEnvelope()
        envelope.append(0, 100, 0.5, 0.3)
        val energy = envelope.playedWindow(110, true)
        assertEquals(0.0, energy.midRms, 0.0)
        assertEquals(0.0, energy.highRms, 0.0)
    }
}
