package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AmbientMusicActivityPolicyTest {
    @Test fun onlyMusicStreamsOwnTheLampEvenWhenGuidanceUsesTheMediaBus() {
        assertTrue(AmbientMusicActivityPolicy.acceptsAudio("media", 102))
        assertTrue(AmbientMusicActivityPolicy.acceptsAudio("", 102))
        for (type in listOf("compatibility", "default", "alert", "telephony", "speechRecognition")) {
            assertFalse(type, AmbientMusicActivityPolicy.acceptsAudio(type, 102))
        }
        assertFalse(AmbientMusicActivityPolicy.acceptsAudio("", 101))
    }

    @Test fun masterOffRestoresOemRegardlessOfPlayback() {
        assertEquals(AmbientMusicTarget.RESTORE_OEM,
            AmbientMusicActivityPolicy.target(false, true, false, false))
        assertEquals(AmbientMusicTarget.RESTORE_OEM,
            AmbientMusicActivityPolicy.target(false, true, true, true))
        assertEquals(AmbientMusicTarget.RESTORE_OEM,
            AmbientMusicActivityPolicy.target(false, false, true, false))
    }

    @Test fun theEnableSwitchAloneLightsTheLampWithNoBrightnessGate() {
        // There is no brightness parameter any more: an enabled feature with a live renderer that is
        // playing always follows playback (or holds a static colour), never the lowest level.
        assertEquals(AmbientMusicTarget.FOLLOW_PLAYBACK,
            AmbientMusicActivityPolicy.target(true, true, true, true))
        assertEquals(AmbientMusicTarget.STATIC,
            AmbientMusicActivityPolicy.target(true, false, true, true))
    }

    @Test fun pausedOrMissingRendererHoldsLowestWhilePlayingSilenceStillFollowsEnvelope() {
        assertEquals(AmbientMusicTarget.LOWEST,
            AmbientMusicActivityPolicy.target(true, true, true, false))
        assertEquals(AmbientMusicTarget.LOWEST,
            AmbientMusicActivityPolicy.target(true, true, false, false))
        // RMS is intentionally not part of the activity decision: quiet PCM while playing
        // remains ordinary envelope input and resolves to the envelope's floor level.
        assertEquals(AmbientMusicTarget.FOLLOW_PLAYBACK,
            AmbientMusicActivityPolicy.target(true, true, true, true))
    }

    @Test fun authoritativePhonePauseOverridesAudioTrackStillReportingPlaying() {
        val state = AmbientPhonePlaybackState()
        val owner = Any()
        state.claim(owner)
        assertTrue(state.update(owner, false))
        val effectivePlaying = true && state.current() != false
        assertEquals(AmbientMusicTarget.LOWEST,
            AmbientMusicActivityPolicy.target(true, true, true, effectivePlaying))
        assertTrue(state.update(owner, true))
        assertEquals(AmbientMusicTarget.FOLLOW_PLAYBACK,
            AmbientMusicActivityPolicy.target(true, true, true, true && state.current() != false))
    }

    @Test fun callbacksFromAnOldOwnerCannotChangeCurrentPlaybackState() {
        val state = AmbientPhonePlaybackState()
        val oldOwner = Any()
        val newOwner = Any()
        state.claim(oldOwner)
        assertTrue(state.update(oldOwner, true))
        state.claim(newOwner)
        assertFalse(state.update(oldOwner, false))
        assertNull(state.current())
        assertTrue(state.update(newOwner, false))
        assertFalse(state.release(oldOwner))
        assertEquals(false, state.current())
        assertTrue(state.release(newOwner))
        assertNull(state.current())
    }

    @Test fun rebindingTheSameControllerKeepsItsKnownPauseState() {
        val state = AmbientPhonePlaybackState()
        val owner = Any()
        state.claim(owner)
        assertTrue(state.update(owner, false))
        state.claim(owner)
        assertEquals(false, state.current())
    }

    @Test fun staticColorAlsoRespectsPauseAndOnlyLightsDuringMusicPlayback() {
        assertEquals(AmbientMusicTarget.LOWEST,
            AmbientMusicActivityPolicy.target(true, false, false, false))
        assertEquals(AmbientMusicTarget.STATIC,
            AmbientMusicActivityPolicy.target(true, false, true, true))
    }
}
