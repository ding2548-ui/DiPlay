package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioBufferProgressTest {
    @Test fun musicRebuffersOnlyAfterHardwareAndIncomingQueuesDrain() {
        val buffer = AudioBufferProgress(4)
        buffer.written(4000)
        assertFalse(buffer.shouldRebuffer(true, true, true, true, 999))
        assertFalse(buffer.shouldRebuffer(true, true, true, false, 1000))
        assertFalse(buffer.shouldRebuffer(true, true, false, true, 1000))
        assertTrue(buffer.shouldRebuffer(true, true, true, true, 1000))
        assertFalse(buffer.shouldRebuffer(true, false, true, true, 1000))
        buffer.written(400)
        assertEquals(400, buffer.queuedBytes(1000))
        assertFalse(buffer.shouldRebuffer(true, true, true, true, 1000))
    }

    @Test fun doesNotAddRebufferDelayToCallsOrSpeech() {
        val buffer = AudioBufferProgress(2)
        assertFalse(buffer.shouldRebuffer(false, true, true, true, 0))
    }

    @Test fun unsignedPlaybackHeadWrapKeepsQueuedAudio() {
        val buffer = AudioBufferProgress(2)
        repeat(8) { buffer.written(1_073_741_824) }
        buffer.written(40)
        assertEquals(42, buffer.queuedBytes(-1))
        assertEquals(40, buffer.queuedBytes(0))
        assertEquals(0, buffer.queuedBytes(20))
    }
}
