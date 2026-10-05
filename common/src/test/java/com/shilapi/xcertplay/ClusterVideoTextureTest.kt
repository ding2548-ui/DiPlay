package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.graphics.Paint
import android.os.Looper
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterVideoTextureTest {
    @Test fun actualClusterTextureFollowsLivePictureControlsAndStopsListeningOnClose() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = CarPlayPicture.preferences(context)
        CarPlayPicture.showOriginal(false)
        CarPlayPicture.reset(prefs)
        prefs.edit().putInt(CarPlayPicture.CONTRAST, 125).commit()
        val view = ClusterVideoTexture(context) {}
        fun paint(): Paint? = ReflectionHelpers.getField(view, "mLayerPaint")
        try {
            // Saved settings apply before the decoder surface is available.
            assertNotNull(paint()?.colorFilter)
            prefs.edit().putInt(CarPlayPicture.SATURATION, 80).apply()
            shadowOf(Looper.getMainLooper()).idle()
            val adjusted = paint()
            assertNotNull(adjusted?.colorFilter)
            CarPlayPicture.showOriginal(true)
            assertNull(paint()?.colorFilter)
            assertEquals(125, CarPlayPicture.value(prefs, CarPlayPicture.CONTRAST))
            CarPlayPicture.showOriginal(false)
            assertNotNull(paint()?.colorFilter)
            CarPlayPicture.reset(prefs)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(paint()?.colorFilter)
            view.close()
            prefs.edit().putInt(CarPlayPicture.BRIGHTNESS, 10).apply()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(paint()?.colorFilter)
        } finally {
            view.close()
            CarPlayPicture.showOriginal(false)
            CarPlayPicture.reset(prefs)
        }
    }

    @Test fun textureDetachesBeforeReleaseAndCloseIsIdempotent() {
        val callbacks = mutableListOf<Surface?>()
        val view = ClusterVideoTexture(RuntimeEnvironment.getApplication()) { callbacks.add(it) }
        val texture = SurfaceTexture(0)
        try {
            assertFalse(view.isOpaque)
            val listener = view.surfaceTextureListener!!
            listener.onSurfaceTextureAvailable(texture, 1920, 720)
            assertNotNull(callbacks.last())
            assertTrue(listener.onSurfaceTextureDestroyed(texture))
            assertNull(callbacks.last())
            assertEquals(2, callbacks.size)
            view.close(); view.close()
            assertEquals(2, callbacks.size)
        } finally { view.close(); texture.release() }
    }
}
