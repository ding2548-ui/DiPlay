package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayPictureTest {
    private val prefs get() = CarPlayPicture.preferences(RuntimeEnvironment.getApplication())
    @Before fun reset() { prefs.edit().clear().commit() }

    private fun pixel(red: Float, green: Float, blue: Float, alpha: Float = 255f): FloatArray {
        val input = floatArrayOf(red, green, blue, alpha)
        val matrix = CarPlayPicture.matrix(prefs).array
        return FloatArray(4) { row ->
            matrix[row * 5 + 4] + (0..3).sumOf { col ->
                (matrix[row * 5 + col] * input[col]).toDouble()
            }.toFloat()
        }
    }

    @Test fun defaultsPreservePixelsAndAlpha() {
        val output = pixel(42f, 128f, 219f, 71f)
        floatArrayOf(42f, 128f, 219f, 71f).forEachIndexed { i, value ->
            assertEquals(value, output[i], 0.001f)
        }
    }
    @Test fun zeroSaturationProducesGrayAndPreservesAlpha() {
        prefs.edit().putInt(CarPlayPicture.SATURATION, 0).commit()
        val output = pixel(255f, 0f, 0f, 71f)
        assertEquals(output[0], output[1], 0.001f)
        assertEquals(output[0], output[2], 0.001f)
        assertEquals(71f, output[3], 0.001f)
    }
    @Test fun contrastKeepsMidpointAndBrightnessMovesIt() {
        prefs.edit().putInt(CarPlayPicture.CONTRAST, 200).commit()
        assertEquals(127.5f, pixel(127.5f, 127.5f, 127.5f)[0], 0.001f)
        prefs.edit().putInt(CarPlayPicture.BRIGHTNESS, 10).commit()
        assertEquals(153f, pixel(127.5f, 127.5f, 127.5f)[0], 0.001f)
    }
    @Test fun positiveWarmthRaisesRedAndLowersBlue() {
        prefs.edit().putInt(CarPlayPicture.WARMTH, 100).commit()
        val output = pixel(100f, 100f, 100f)
        assertEquals(120f, output[0], 0.001f)
        assertEquals(100f, output[1], 0.001f)
        assertEquals(80f, output[2], 0.001f)
    }
    @Test fun invalidStoredValuesAreClampedAndResetRestoresDefaults() {
        prefs.edit().putInt(CarPlayPicture.BRIGHTNESS, 999).commit()
        assertEquals(50, CarPlayPicture.value(prefs, CarPlayPicture.BRIGHTNESS))
        prefs.edit().clear().commit()
        assertEquals(0, CarPlayPicture.value(prefs, CarPlayPicture.BRIGHTNESS))
        assertEquals(100, CarPlayPicture.value(prefs, CarPlayPicture.SATURATION))
    }
}
