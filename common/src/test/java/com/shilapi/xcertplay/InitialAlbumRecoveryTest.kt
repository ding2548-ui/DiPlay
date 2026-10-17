package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test

class InitialAlbumRecoveryTest {
    private fun media(title: String? = "lyric", id: Int? = 129, playing: Boolean = true,
                      app: String? = "music", elapsed: Long = 40_000) =
        CarPlayNowPlaying(title = title, artworkTransferId = id, playing = playing,
            sourceApp = app, elapsedMillis = elapsed)

    private fun begin(recovery: InitialAlbumRecovery, info: CarPlayNowPlaying = media()): Long {
        assertTrue(recovery.observe(info, false))
        return recovery.pendingToken.also { assertTrue(recovery.beginRequest(it, info, false)) }
    }

    @Test fun connectionPausedThenManualPlayChoosesCurrentMediaWithoutSpendingBudget() {
        val recovery = InitialAlbumRecovery()
        repeat(50) {
            assertFalse(recovery.observe(media(title = null, id = 128, playing = false), false))
            assertFalse(recovery.observe(media(title = null, id = 129, playing = false), false))
        }
        // Playback may begin in the middle of the track, minutes after connection.
        val token = begin(recovery, media(title = null, elapsed = 95_000))
        assertTrue(recovery.claimSend(token, media(title = null, elapsed = 95_000), false))
    }

    @Test fun lyricAndProgressUpdatesDoNotCancelOrStartAdditionalTimers() {
        val recovery = InitialAlbumRecovery()
        assertTrue(recovery.observe(media(), false))
        val token = recovery.pendingToken
        repeat(50) {
            assertFalse(recovery.observe(media(title = "line $it", elapsed = 40_000L + it * 500), false))
            assertEquals(token, recovery.pendingToken)
        }
        assertTrue(recovery.beginRequest(token, media(title = "last line"), false))
        assertTrue(recovery.stillNeeded(token, media(title = null), false))
        assertTrue(recovery.claimSend(token, media(title = "another line"), false))
    }

    @Test fun referenceChangeRebindsBeforeSendAndOnlyNewReferenceCanClaim() {
        val recovery = InitialAlbumRecovery()
        val old = begin(recovery)
        assertTrue(recovery.observe(media(id = 130), false))
        val next = recovery.pendingToken
        assertFalse(recovery.claimSend(old, media(), false))
        assertTrue(recovery.beginRequest(next, media(id = 130), false))
        recovery.finished(old)
        assertTrue(recovery.claimSend(next, media(id = 130), false))
        assertFalse(recovery.observe(media(id = 130), false))
        assertEquals(listOf("cancelled reason=referenceChanged"), recovery.takeEvents())
    }

    @Test fun sourceChangeRebindsBeforeSendAndOnlyCurrentAppCanClaim() {
        val recovery = InitialAlbumRecovery()
        val old = begin(recovery)
        assertTrue(recovery.observe(media(app = "other"), false))
        val next = recovery.pendingToken
        assertFalse(recovery.claimSend(old, media(), false))
        assertTrue(recovery.beginRequest(next, media(app = "other"), false))
        assertTrue(recovery.claimSend(next, media(app = "other"), false))
        assertEquals(listOf("cancelled reason=sourceChanged"), recovery.takeEvents())
    }

    @Test fun referenceChangeWhilePausedWaitsForPlaybackBeforeBindingNewMedia() {
        val recovery = InitialAlbumRecovery()
        val old = begin(recovery)
        assertFalse(recovery.observe(media(id = 130, playing = false), false))
        assertFalse(recovery.observe(media(id = 131, playing = false), false))
        assertFalse(recovery.claimSend(old, media(), false))
        val next = begin(recovery, media(id = 132))
        assertTrue(recovery.claimSend(next, media(id = 132), false))
    }

    @Test fun lateSourceFieldForSameReferenceIsAccepted() {
        val recovery = InitialAlbumRecovery()
        val token = begin(recovery, media(app = null))
        assertTrue(recovery.stillNeeded(token, media(), false))
        assertTrue(recovery.claimSend(token, media(), false))
    }

    @Test fun missingReferenceWaitsRatherThanGuessingFromTitle() {
        val recovery = InitialAlbumRecovery()
        repeat(50) { assertFalse(recovery.observe(media(id = null, title = "line $it"), false)) }
        val token = begin(recovery)
        assertTrue(recovery.claimSend(token, media(), false))
    }

    @Test fun matchedColorCompletesCurrentReferenceIncludingInitiallyCompleteMedia() {
        val recovery = InitialAlbumRecovery()
        val token = begin(recovery)
        assertFalse(recovery.observe(media(), true))
        assertFalse(recovery.claimSend(token, media(), false))
        assertFalse(recovery.observe(media(), false))
        assertEquals(listOf("cancelled reason=colorReady"), recovery.takeEvents())
        val ready = InitialAlbumRecovery()
        assertFalse(ready.observe(media(), true))
        assertFalse(ready.observe(media(), false))
    }

    @Test fun pauseCancelsTimerAndResumeCanScheduleWithoutOldTimerSending() {
        val recovery = InitialAlbumRecovery()
        assertTrue(recovery.observe(media(), false))
        val old = recovery.pendingToken
        assertFalse(recovery.observe(media(playing = false), false))
        assertFalse(recovery.timerPending)
        assertTrue(recovery.observe(media(), false))
        val resumed = recovery.pendingToken
        assertFalse(recovery.beginRequest(old, media(), false))
        assertTrue(recovery.beginRequest(resumed, media(), false))
        assertTrue(recovery.claimSend(resumed, media(), false))
        assertEquals(listOf("cancelled reason=paused"), recovery.takeEvents())
    }

    @Test fun pauseDuringWorkerPreflightLeavesBudgetAndOldCompletionCannotClearNewWorker() {
        val recovery = InitialAlbumRecovery()
        val old = begin(recovery)
        assertFalse(recovery.stillNeeded(old, media(playing = false), false))
        val resumed = begin(recovery)
        recovery.finished(old)
        assertFalse(recovery.claimSend(old, media(), false))
        assertTrue(recovery.claimSend(resumed, media(), false))
    }

    @Test fun preflightSkipReleasesWorkerOnlyAndWaitsForNextMetadataUpdate() {
        val recovery = InitialAlbumRecovery()
        val token = begin(recovery)
        recovery.finished(token) // Closed/inactive/channel unavailable: no channel.send happened.
        assertFalse(recovery.timerPending)
        assertFalse(recovery.claimSend(token, media(), false))
        val retry = begin(recovery)
        assertTrue(recovery.claimSend(retry, media(), false))
    }

    @Test fun actualSendAttemptSpendsBudgetEvenWhenSendFailsOrPlaybackResumes() {
        val recovery = InitialAlbumRecovery()
        val token = begin(recovery)
        assertTrue(recovery.claimSend(token, media(), false))
        assertFalse(recovery.claimSend(token, media(), false))
        recovery.finished(token) // Successful or failed send; never keep resubscribing.
        assertFalse(recovery.observe(media(playing = false), false))
        repeat(50) { assertFalse(recovery.observe(media(title = "line $it"), false)) }
        assertTrue(recovery.observe(media(id = 130), false))
        val next = recovery.pendingToken
        assertTrue(recovery.beginRequest(next, media(id = 130), false))
        assertTrue(recovery.claimSend(next, media(id = 130), false))
    }

    @Test fun disabledSettingCancelsWithoutSpendingAttemptAndCanResume() {
        val recovery = InitialAlbumRecovery()
        assertFalse(recovery.observe(media(), false) { false })
        val token = begin(recovery)
        assertFalse(recovery.stillNeeded(token, media(), false) { false })
        val resumed = begin(recovery)
        assertTrue(recovery.claimSend(resumed, media(), false))
        assertEquals(listOf("cancelled reason=settingDisabled"), recovery.takeEvents())
    }

    @Test fun newOwnerHasFreshBudgetButOldOwnerCannotSendAgain() {
        val old = InitialAlbumRecovery()
        val token = begin(old)
        assertTrue(old.claimSend(token, media(), false))
        val next = InitialAlbumRecovery()
        val fresh = begin(next)
        assertTrue(next.claimSend(fresh, media(), false))
        assertFalse(old.claimSend(token, media(), false))
    }
}
