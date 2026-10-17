package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMicrophoneRequestTest {
    private fun wants(audioType: String, vararg entries: Pair<String, Any?>) =
        CarPlayMediaEngine.wantsMicrophone(audioType, mapOf(*entries))

    @Test fun callsAndSiriAlwaysRecord() {
        assertTrue(wants("telephony"))
        assertTrue(wants("speechrecognition"))
    }

    @Test fun appAudioRecordsOnlyWhenTheIphoneAsksForInput() {
        // A WhatsApp voice note arrives as app audio with an uplink port.
        assertTrue(wants("default", "dataPort" to 50_000))
        assertTrue(wants("compatibility", "dataPort" to 50_000))
        assertTrue(wants("default", "input" to true))
        assertTrue(wants("default", "input" to 1L, "dataPort" to 50_000))
        // Guidance prompts use the same audio type without input.
        assertFalse(wants("default"))
        assertFalse(wants("default", "dataPort" to 0))
        assertFalse(wants("default", "input" to false, "dataPort" to 50_000))
        assertFalse(wants("default", "input" to 0, "dataPort" to 50_000))
    }

    @Test fun mediaAndAlertsNeverRecord() {
        assertFalse(wants("media", "dataPort" to 50_000))
        assertFalse(wants("alert", "input" to true, "dataPort" to 50_000))
    }
}
