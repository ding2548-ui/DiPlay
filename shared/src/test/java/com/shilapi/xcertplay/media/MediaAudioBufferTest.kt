package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAudioBufferTest {
    @Test
    fun `music buffers the chosen delay plus headroom`() {
        // 48 kHz stereo 16-bit = 192 000 bytes/s.
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, minBufferBytes = 7_680, mediaMillis = 500)
        assertEquals(96_000, plan.startBytes)
        assertEquals(134_400, plan.trackBufferBytes)
    }

    @Test
    fun `all streams mapped to media receive the music buffer`() {
        val selection = AudioChannelMapper.map(
            "compatibility", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS,
        )
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            48_000, 2, minBufferBytes = 7_680, mediaMillis = 500)
        assertEquals(96_000, plan.startBytes)
    }

    @Test
    fun `calls and prompts keep the low-latency buffer`() {
        val plan = MediaAudioBuffer.plan(false, 16_000, 1, minBufferBytes = 1_280, mediaMillis = 1000)
        assertEquals(4 * 1024, plan.startBytes)
        assertEquals(16 * 1024, plan.trackBufferBytes)
        val guidance = AudioChannelMapper.map("default", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS)
        assertEquals(plan, MediaAudioBuffer.plan(guidance.channel == AudioChannel.MEDIA,
            16_000, 1, minBufferBytes = 1_280, mediaMillis = 1000))
    }

    @Test
    fun `wired presets step by 50 ms up to 200 ms, then by 100 ms up to 1000 ms`() {
        assertEquals(listOf(50, 100, 150, 200, 300, 400, 500, 600, 700, 800, 900, 1000), MediaAudioBuffer.wiredPresets)
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, MediaAudioBuffer.wiredPresets[4])
        assertEquals(MediaAudioBuffer.MOST_STABLE_MILLIS, MediaAudioBuffer.wiredPresets.last())
    }

    @Test
    fun `wireless keeps the classic presets`() {
        assertEquals(listOf(300, 500, 1000), MediaAudioBuffer.wirelessPresets)
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, MediaAudioBuffer.sanitizeWireless(50))
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, MediaAudioBuffer.sanitizeWireless(400))
        assertEquals(50, MediaAudioBuffer.sanitizeWired(50))
    }

    @Test
    fun `delays saved by earlier versions stay selected`() {
        for (millis in listOf(300, 500, 1000)) {
            assertEquals(millis, MediaAudioBuffer.sanitize(millis))
            assertEquals(millis, MediaAudioBuffer.sanitizeWireless(millis))
            assertEquals(millis, MediaAudioBuffer.sanitizeWired(millis))
        }
    }

    @Test
    fun `shortest music buffer still keeps the low-latency floor and headroom`() {
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, minBufferBytes = 7_680, mediaMillis = 50)
        assertEquals(9_600, plan.startBytes)
        assertEquals(48_000, plan.trackBufferBytes)
        // A device minimum above the preset wins over it.
        assertEquals(15_360, MediaAudioBuffer.plan(true, 48_000, 2, minBufferBytes = 15_360, mediaMillis = 50).startBytes)
    }

    @Test
    fun `unknown delay falls back to the default`() {
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, MediaAudioBuffer.sanitize(250))
        assertEquals(57_600, MediaAudioBuffer.plan(true, 48_000, 2, 7_680, mediaMillis = 42).startBytes)
    }

    @Test
    fun `start level stays below a smaller granted buffer`() {
        assertEquals(98_000, MediaAudioBuffer.startBytesFor(192_000, 100_000, 2_048 - 48))
        assertEquals(57_600, MediaAudioBuffer.startBytesFor(57_600, 134_400, 2_048))
        assertEquals(57_600, MediaAudioBuffer.startBytesFor(57_600, 0, 2_048))
    }
}
