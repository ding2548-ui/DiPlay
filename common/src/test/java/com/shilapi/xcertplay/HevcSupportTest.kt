package com.shilapi.xcertplay

import com.shilapi.xcertplay.HevcSupport.Decoder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcSupportTest {
    private val software = Decoder("OMX.google.hevc.decoder", hardware = false)
    private val hardware = Decoder("OMX.qcom.video.decoder.hevc", hardware = true)

    @Test fun onlyASoftwareDecoderFallsBackToH264() {
        // #727: a Spreadtrum SC7731E head unit has only Android's software HEVC decoder.
        assertFalse(HevcSupport.usable(requested = true, softwareChosen = false, decoders = listOf(software)))
    }

    @Test fun aHardwareDecoderKeepsHevc() {
        assertTrue(HevcSupport.usable(requested = true, softwareChosen = false, decoders = listOf(hardware, software)))
    }

    @Test fun theDriversSoftwareChoiceKeepsHevc() {
        assertTrue(HevcSupport.usable(requested = true, softwareChosen = true, decoders = listOf(software)))
        assertFalse(HevcSupport.usable(requested = true, softwareChosen = true, decoders = emptyList()))
    }

    @Test fun theSettingOffNeverAsksForHevc() {
        assertFalse(HevcSupport.usable(requested = false, softwareChosen = true, decoders = listOf(hardware)))
    }
}
