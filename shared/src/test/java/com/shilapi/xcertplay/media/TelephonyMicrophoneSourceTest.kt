package com.shilapi.xcertplay.media

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

class TelephonyMicrophoneSourceTest {
    private fun source(speakerphone: Boolean, carPhoneState: Boolean, geely: Boolean = false) =
        telephonyMicrophoneSource(speakerphone, carPhoneState, geely)

    @Test fun theCarsPhoneStateUsesVoiceRecognition() {
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, source(speakerphone = false, carPhoneState = true))
    }

    @Test fun thePhoneStateNeverUsesThePlainMicrophoneThatItMutes() {
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, source(speakerphone = true, carPhoneState = true))
    }

    @Test fun withoutThePhoneStateACallKeepsItsOldSources() {
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, source(speakerphone = false, carPhoneState = false))
        assertEquals(MediaRecorder.AudioSource.MIC, source(speakerphone = true, carPhoneState = false))
    }

    @Test fun geelyHeadUnitsKeepTheirOldSourcesBecauseVoiceRecognitionIsSilentThere() {
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, source(speakerphone = false, carPhoneState = true, geely = true))
        assertEquals(MediaRecorder.AudioSource.MIC, source(speakerphone = true, carPhoneState = true, geely = true))
    }
}
