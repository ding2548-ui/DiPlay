package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class MediaCodecStartupTest {
    @Test fun configureFailureReleasesExactlyOnceAndNeverStarts() {
        val candidate = Any()
        val failure = IllegalArgumentException("configure rejected")
        var releases = 0
        try {
            MediaCodecStartup.create(
                create = { candidate },
                configure = { assertSame(candidate, it); throw failure },
                start = { fail("A rejected codec must not start") },
                release = { assertSame(candidate, it); releases++ },
            )
            fail("The original failure must propagate")
        } catch (actual: IllegalArgumentException) {
            assertSame(failure, actual)
        }
        assertEquals(1, releases)
    }

    @Test fun startFailureIsPreservedEvenIfReleaseAlsoFails() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("start rejected")
        try {
            MediaCodecStartup.create(
                create = { "codec" },
                configure = { events += "configure:$it" },
                start = { events += "start:$it"; throw failure },
                release = { events += "release:$it"; throw RuntimeException("release rejected") },
            )
            fail("The original failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(listOf("configure:codec", "start:codec", "release:codec"), events)
    }

    @Test fun linkageErrorsStillPropagateAfterReleasingTheCandidate() {
        val failure = NoSuchMethodError("platform API unavailable")
        var releases = 0
        try {
            MediaCodecStartup.create(
                create = { Any() }, configure = { throw failure },
                start = { fail("A rejected codec must not start") }, release = { releases++ },
            )
            fail("The platform error must propagate")
        } catch (actual: NoSuchMethodError) {
            assertSame(failure, actual)
        }
        assertEquals(1, releases)
    }

    @Test fun aWorkingCodecRemainsOwnedByTheRenderer() {
        val candidate = Any()
        assertSame(candidate, MediaCodecStartup.create(
            create = { candidate }, configure = {}, start = {},
            release = { fail("A started codec must remain alive") },
        ))
    }

    @Test fun creationFailureHasNoCandidateToRelease() {
        val failure = IllegalStateException("creation rejected")
        try {
            MediaCodecStartup.create<Any>(
                create = { throw failure }, configure = { fail("No candidate") },
                start = { fail("No candidate") }, release = { fail("No candidate") },
            )
            fail("The original failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
    }
}
