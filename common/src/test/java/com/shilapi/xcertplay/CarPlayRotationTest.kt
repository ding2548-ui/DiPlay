package com.shilapi.xcertplay

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayRotationTest {
    @Before fun setup() { ShadowMediaCodecList.reset() }
    @After fun cleanup() { ShadowMediaCodecList.reset() }

    @Test fun unrelatedSoftwareCannotApproveASquareUnsupportedByTheDefaultHardware() {
        val hardware = decoder("c2.test.hardware", 1920, 1080)
        val software = decoder("c2.android.avc.decoder", 4096, 4096, hardware = false)
        assertFalse(hardware.getCapabilitiesForType(AVC).videoCapabilities!!.isSizeSupported(1280, 1280))
        assertTrue(software.getCapabilitiesForType(AVC).videoCapabilities!!.isSizeSupported(1920, 1920))
        assertNull(CarPlayRotation.squareSide(1920, CarPlayRotation.Picture.SMOOTHER, hevc = false))
    }

    @Test fun aLaterHardwareDecoderCannotApproveASquareTheDefaultDecoderCannotUse() {
        decoder("c2.test.default", 1920, 1080)
        decoder("c2.test.other", 2560, 2560)
        assertNull(CarPlayRotation.squareSide(2560, CarPlayRotation.Picture.SHARPER, hevc = false))
    }

    @Test fun smootherCapsTheSquareWhileSharperUsesTheSupportedScreenSize() {
        decoder("c2.test.hardware", 2560, 2560)
        assertEquals(1920, CarPlayRotation.squareSide(2560, CarPlayRotation.Picture.SMOOTHER, hevc = false))
        assertEquals(2560, CarPlayRotation.squareSide(2560, CarPlayRotation.Picture.SHARPER, hevc = false))
    }

    @Test fun unsupportedLargeSquaresUseASmallerHardwareSupportedCandidate() {
        decoder("c2.test.hardware", 1600, 1600)
        assertEquals(1600, CarPlayRotation.squareSide(2560, CarPlayRotation.Picture.SMOOTHER, hevc = false))
    }

    @Test fun aSoftwareDefaultKeepsThePlainCanvasWithoutAnExplicitPreference() {
        decoder("c2.android.avc.decoder", 4096, 4096, hardware = false)
        assertNull(CarPlayRotation.squareSide(1920, CarPlayRotation.Picture.SMOOTHER, hevc = false))
    }

    @Test fun explicitSoftwareHevcPreferenceChecksTheSoftwareDecoderActuallySelected() {
        decoder("c2.test.hevc.hardware", 1920, 1080, mime = HEVC)
        decoder("c2.android.hevc.decoder", 2560, 2560, hardware = false, mime = HEVC)
        assertNull(CarPlayRotation.squareSide(1920, CarPlayRotation.Picture.SMOOTHER, hevc = true))
        assertEquals(1920, CarPlayRotation.squareSide(1920, CarPlayRotation.Picture.SMOOTHER,
            hevc = true, preferSoftwareHevcDecoder = true))
    }

    @Test fun missingCapabilitiesRetainThePlainCanvas() {
        assertNull(CarPlayRotation.squareSide(1920, CarPlayRotation.Picture.SMOOTHER, hevc = false))
    }

    @Test fun oddScreenSizeChecksTheEvenCanvasThatWillActuallyBeRequested() {
        decoder("c2.test.hardware", 1920, 1920)
        assertEquals(1920, CarPlayRotation.squareSide(1921, CarPlayRotation.Picture.SHARPER, hevc = false))
    }

    private fun decoder(name: String, maxWidth: Int, maxHeight: Int, hardware: Boolean = true,
        mime: String = AVC): MediaCodecInfo {
        val format = MediaFormat.createVideoFormat(mime, maxWidth, maxHeight).apply {
            setString("size-range", "64x64-${maxWidth}x${maxHeight}")
            setString("alignment", "2x2")
            setString("frame-rate-range", "1-60")
        }
        val level = MediaCodecInfo.CodecProfileLevel().apply {
            profile = if (mime == HEVC) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                else MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            this.level = if (mime == HEVC) MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51
                else MediaCodecInfo.CodecProfileLevel.AVCLevel52
        }
        val capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format).setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
            .setProfileLevels(arrayOf(level)).build()
        return MediaCodecInfoBuilder.newBuilder().setName(name).setIsHardwareAccelerated(hardware)
            .setIsSoftwareOnly(!hardware).setCapabilities(capabilities).build().also(ShadowMediaCodecList::addCodec)
    }

    companion object {
        private const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
    }
}
