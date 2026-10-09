package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class EchoReferenceTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { i, s -> out[2 * i] = s.toByte(); out[2 * i + 1] = (s shr 8).toByte() }
    }

    @Test fun nothingIsReturnedBeforePlaybackStarts() {
        val reference = EchoReference(sampleRate = 1_000)
        reference.append(pcm(1, 2, 3, 4), 0, 8, 1, pendingFrames = null, nowNs = 0)
        val out = ShortArray(2) { 9 }
        assertFalse(reference.read(out, endNs = 10_000_000))
        assertArrayEquals(shortArrayOf(0, 0), out)
    }

    @Test fun readsTheSamplesThatWerePlayingAtTheRequestedTime() {
        // 1 kHz: one sample per millisecond. Ten samples written, four still queued at t=0,
        // so sample 6 is reaching the speaker at t=0 and sample 8 at t=2 ms.
        val reference = EchoReference(sampleRate = 1_000)
        reference.append(pcm(*IntArray(10) { it * 100 }), 0, 20, 1, pendingFrames = 4, nowNs = 0)
        val out = ShortArray(3)
        assertTrue(reference.read(out, endNs = 2_000_000))
        assertArrayEquals(shortArrayOf(500, 600, 700), out)
    }

    @Test fun samplesPastWhatWasWrittenOrAlreadyOverwrittenAreSilence() {
        val reference = EchoReference(sampleRate = 1_000, capacityMillis = 4)
        reference.append(pcm(*IntArray(6) { (it + 1) * 10 }), 0, 12, 1, pendingFrames = 0, nowNs = 0)
        val out = ShortArray(8)
        assertTrue(reference.read(out, endNs = 2_000_000))
        // Samples 0-5 written, only 2-5 kept; the window covers samples 0-7.
        assertArrayEquals(shortArrayOf(0, 0, 30, 40, 50, 60, 0, 0), out)
    }

    @Test fun stereoIsDownmixedToMono() {
        val reference = EchoReference(sampleRate = 1_000)
        reference.append(pcm(100, 300, -200, -400), 0, 8, 2, pendingFrames = 0, nowNs = 0)
        val out = ShortArray(2)
        assertTrue(reference.read(out, endNs = 0))
        assertArrayEquals(shortArrayOf(200, -300), out)
    }

    @Test fun resetForgetsThePlayedAudio() {
        val reference = EchoReference(sampleRate = 1_000)
        reference.append(pcm(1, 2), 0, 4, 1, pendingFrames = 0, nowNs = 0)
        reference.reset()
        assertFalse(reference.read(ShortArray(2), endNs = 0))
    }
}
