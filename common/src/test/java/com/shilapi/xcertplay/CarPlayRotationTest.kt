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
        assertNull(CarPlayRotation.squareSide(1920, CarPlayRotation.Picture.SMOOTHER, hevc = false, configures = DECLINES))
    }

    @Test fun aLaterHardwareDecoderCannotApproveASquareTheDefaultDecoderCannotUse() {
        decoder("c2.test.default", 1920, 1080)
        decoder("c2.test.other", 2560, 2560)
        assertNull(CarPlayRotation.squareSide(2560, CarPlayRotation.Picture.SHARPER, hevc = false, configures = DECLINES))
    }

    @Test fun aFullScreenWindowTurnsIntoTheSwappedWindow() {
        val (landscape, portrait) = CarPlayRotation.turningAreas(1920, 2560, 1440, 2560, 1440)
        assertEquals(1920 to 1080, landscape)
        assertEquals(1080 to 1920, portrait)
    }

    @Test fun aNavigationBarThatStaysAtTheBottomKeepsBothAreasInTheirWindowsProportions() {
        // A 120 px navigation bar: 2560x1320 in landscape becomes 1440x2440, not 1320x2560, in portrait.
        val fromLandscape = CarPlayRotation.turningAreas(1920, 2560, 1320, 2560, 1440)
        assertEquals(1920 to 990, fromLandscape.first)
        assertEquals(1132 to 1920, fromLandscape.second) // 1920 * 1440 / 2440, even
        // Starting in portrait gives the same two areas.
        assertEquals(fromLandscape, CarPlayRotation.turningAreas(1920, 1440, 2440, 1440, 2560))
    }

    @Test fun anUnknownScreenSizeFallsBackToTheTurnedWindow() {
        val (landscape, portrait) = CarPlayRotation.turningAreas(1920, 2560, 1320, 0, 0)
        assertEquals(1920 to 990, landscape)
        assertEquals(990 to 1920, portrait)
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
        assertNull(CarPlayRotation.squareSide(1920, CarPlayRotation.Picture.SMOOTHER, hevc = true, configures = DECLINES))
        assertEquals(1920, CarPlayRotation.squareSide(1920, CarPlayRotation.Picture.SMOOTHER,
            hevc = true, preferSoftwareHevcDecoder = true, configures = DECLINES))
    }

    @Test fun missingCapabilitiesRetainThePlainCanvas() {
        assertNull(CarPlayRotation.squareSide(1920, CarPlayRotation.Picture.SMOOTHER, hevc = false))
    }

    @Test fun oddScreenSizeChecksTheEvenCanvasThatWillActuallyBeRequested() {
        decoder("c2.test.hardware", 1920, 1920)
        assertEquals(1920, CarPlayRotation.squareSide(1921, CarPlayRotation.Picture.SHARPER, hevc = false))
    }

    @Test fun aSquareTheListedSizesRejectOnlyByOneSideIsPutToTheDefaultDecoder() {
        // Like Qualcomm's AVC decoder on a 2560x1440 head unit: 4096x2176 at most, 2560x2560 in practice.
        decoder("c2.test.default", 4096, 2176)
        decoder("c2.test.other", 4096, 4096)
        val asked = mutableListOf<Pair<String, Int>>()
        assertEquals(2560, CarPlayRotation.squareSide(2560, CarPlayRotation.Picture.SHARPER, hevc = false) { name, _, side ->
            asked += name to side
            true
        })
        assertEquals(listOf("c2.test.default" to 2560), asked)
    }

    @Test fun aDecoderThatDeclinesTheSquareFallsBackToTheLargestListedOne() {
        decoder("c2.test.default", 4096, 2176)
        val asked = mutableListOf<Int>()
        assertEquals(2048, CarPlayRotation.squareSide(2560, CarPlayRotation.Picture.SHARPER, hevc = false) { _, _, side ->
            asked += side
            false
        })
        assertEquals(listOf(2560, 2304), asked)
    }

    @Test fun onlySquaresWithinTheListedFramesPixelsArePutToTheDecoder() {
        decoder("c2.test.default", 1920, 1080)
        val asked = mutableListOf<Int>()
        assertNull(CarPlayRotation.squareSide(2560, CarPlayRotation.Picture.SHARPER, hevc = false) { _, _, side ->
            asked += side
            false
        })
        assertEquals(listOf(1280), asked)
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
        private val DECLINES: (String, String, Int) -> Boolean = { _, _, _ -> false }
        private const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
    }
}
