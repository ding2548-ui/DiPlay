package com.shilapi.xcertplay.airplay

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneStartTest {
    private val starts = AtomicInteger()
    private val gate = MicrophoneStart { starts.incrementAndGet() }
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Test fun aVoiceMessageWithoutDownlinkAudioStartsTheMicrophoneOnce() {
        var started: Boolean? = null
        gate.scheduleSilentStart(scheduler, 0) { startNow -> started = startNow() }
        drain()

        assertEquals(true, started)
        assertEquals(1, starts.get())
        gate.onDownlink()
        assertEquals(1, starts.get())
    }

    @Test fun theFirstDownlinkPacketStartsCaptureAndCancelsTheSilentStart() {
        val checks = AtomicInteger()
        gate.scheduleSilentStart(scheduler, 60_000) { startNow ->
            checks.incrementAndGet()
            startNow()
        }

        gate.onDownlink()
        gate.onDownlink()
        drain()

        assertEquals(1, starts.get())
        assertEquals(0, checks.get())
    }

    @Test fun aStreamTornDownBeforeTheDelayNeverStartsLate() {
        gate.scheduleSilentStart(scheduler, 0) { /* TEARDOWN already removed the stream's microphone. */ }
        drain()

        assertEquals(0, starts.get())
    }

    @Test fun callsWaitForTheirDownlinkAndOtherInputStreamsDoNot() {
        assertFalse(MicrophoneStart.startsWithoutDownlink("telephony"))
        assertTrue(MicrophoneStart.startsWithoutDownlink("speechrecognition"))
        assertTrue(MicrophoneStart.startsWithoutDownlink("default"))
    }

    private fun drain() {
        // Delayed tasks still run after shutdown; cancelled ones are dropped.
        scheduler.shutdown()
        assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS))
    }
}
