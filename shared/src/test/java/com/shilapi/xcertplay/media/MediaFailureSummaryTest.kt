package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFailureSummaryTest {
    @Test fun keepsTheApiFailureLocationWithoutItsMessageOrSourcePath() {
        val error = NoSuchMethodError("private phone name and AudioTrack.getAudioAttributes")
        error.stackTrace = arrayOf(
            StackTraceElement("private.customer.Phone", "secret", "/private/phone/file", 7),
            StackTraceElement("com.shilapi.xcertplay.media.AudioRenderer", "createTrack", "/private/file", 963),
        )
        assertEquals("error=NoSuchMethodError causes=NoSuchMethodError " +
            "at=com.shilapi.xcertplay.media.AudioRenderer.createTrack:963", MediaFailureSummary.describe(error))
    }

    @Test fun limitsCauseDepthAndDoesNotExportMessages() {
        val error = IllegalStateException("private route", RuntimeException("private device",
            IllegalArgumentException("private packet", SecurityException("private identity"))))
        error.stackTrace = emptyArray()
        val summary = MediaFailureSummary.describe(error)
        assertTrue(summary.contains("causes=IllegalStateException/RuntimeException/IllegalArgumentException"))
        assertFalse(summary.contains("SecurityException"))
        assertFalse(summary.contains("private"))
        assertTrue(summary.length < 400)
    }

    @Test fun cyclicCausesAreBoundedAndForeignStackFramesAreOmitted() {
        val first = RuntimeException("private first")
        val second = IllegalStateException("private second", first)
        first.initCause(second)
        first.stackTrace = arrayOf(StackTraceElement("private.customer.Phone", "secret", "private", 7))
        second.stackTrace = emptyArray()
        assertEquals("error=RuntimeException causes=RuntimeException/IllegalStateException at=unavailable",
            MediaFailureSummary.describe(first))
    }
}
