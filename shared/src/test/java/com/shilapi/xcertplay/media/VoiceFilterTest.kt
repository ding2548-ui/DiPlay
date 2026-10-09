package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class VoiceFilterTest {
    private val rate = 48_000

    private fun tone(hz: Double, channels: Int, seconds: Double = 0.5): ByteArray {
        val frames = (rate * seconds).toInt()
        return ByteArray(frames * channels * 2).also { out ->
            for (i in 0 until frames) {
                val s = (10_000 * sin(2 * PI * hz * i / rate)).toInt()
                for (c in 0 until channels) {
                    val at = (i * channels + c) * 2
                    out[at] = s.toByte(); out[at + 1] = (s shr 8).toByte()
                }
            }
        }
    }

    // RMS over the second half, after the filter has settled.
    private fun rms(pcm: ByteArray, channels: Int, channel: Int): Double {
        val frames = pcm.size / (2 * channels)
        var sum = 0.0
        for (i in frames / 2 until frames) {
            val at = (i * channels + channel) * 2
            val s = ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xff)).toDouble()
            sum += s * s
        }
        return sqrt(sum / (frames - frames / 2))
    }

    private fun gain(hz: Double, channels: Int = 1, channel: Int = 0): Double {
        val pcm = tone(hz, channels)
        val before = rms(pcm, channels, channel)
        VoiceFilter(rate, channels).process(pcm, 0, pcm.size)
        return rms(pcm, channels, channel) / before
    }

    @Test fun deepBassIsCut() = assertTrue(gain(60.0) < 0.05)

    @Test fun theCutoffIsAboutThreeDecibelsDown() = assertEquals(0.707, gain(VoiceFilter.DEFAULT_CUTOFF_HZ), 0.05)

    @Test fun speechPassesUnchanged() {
        assertEquals(1.0, gain(1_000.0), 0.02)
        assertEquals(1.0, gain(3_000.0), 0.02)
    }

    @Test fun bothStereoChannelsAreFilteredIndependently() {
        assertTrue(gain(60.0, channels = 2, channel = 1) < 0.05)
        assertEquals(1.0, gain(1_000.0, channels = 2, channel = 1), 0.02)
    }

    @Test fun onlyTheGivenRangeIsChanged() {
        val pcm = tone(60.0, 1)
        val untouched = pcm.copyOfRange(0, 100)
        VoiceFilter(rate, 1).process(pcm, 100, pcm.size - 100)
        assertArrayEquals(untouched, pcm.copyOfRange(0, 100))
    }
}
