package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.TextureView

/** Pixel adjustments for CarPlay video textures, not Android or OEM UI. */
internal object CarPlayPicture {
    const val BRIGHTNESS = "brightness"
    const val CONTRAST = "contrast"
    const val SATURATION = "saturation"
    const val WARMTH = "warmth"
    val keys = listOf(BRIGHTNESS, CONTRAST, SATURATION, WARMTH)
    private val previewListeners = mutableSetOf<() -> Unit>()
    private var originalPreview = false

    /** Transient A/B comparison; never written to preferences or left on after panel closure. */
    fun showOriginal(show: Boolean) {
        originalPreview = show
        previewListeners.toList().forEach { it() }
    }

    class Binding(private val view: TextureView) : java.io.Closeable {
        private var closed = false
        private val prefs = preferences(view.context)
        private val update: () -> Unit = { if (!closed) apply(view, prefs) }
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> handler.post { update() } }
        init {
            prefs.registerOnSharedPreferenceChangeListener(listener)
            previewListeners.add(update)
            update()
        }
        override fun close() {
            closed = true
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            previewListeners.remove(update)
        }
    }

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("carplay_picture", Context.MODE_PRIVATE)

    fun reset(prefs: SharedPreferences) {
        // clear() does not notify listeners on Android 10. Explicit values update live views.
        val editor = prefs.edit()
        keys.forEach { editor.putInt(it, defaultValue(it)) }
        editor.apply()
    }

    fun defaultValue(key: String): Int = if (key == CONTRAST || key == SATURATION) 100 else 0
    fun range(key: String): IntRange = when (key) {
        BRIGHTNESS -> -50..50
        WARMTH -> -100..100
        else -> 0..200
    }
    fun value(prefs: SharedPreferences, key: String): Int =
        prefs.getInt(key, defaultValue(key)).coerceIn(range(key))

    fun matrix(prefs: SharedPreferences): ColorMatrix {
        val contrast = value(prefs, CONTRAST) / 100f
        val brightness = value(prefs, BRIGHTNESS) * 255f / 100f
        val offset = 127.5f * (1f - contrast) + brightness
        val warmth = value(prefs, WARMTH) / 100f * 0.2f
        return ColorMatrix().apply {
            setSaturation(value(prefs, SATURATION) / 100f)
            postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            )))
            postConcat(ColorMatrix().apply { setScale(1f + warmth, 1f, 1f - warmth, 1f) })
        }
    }

    fun apply(view: TextureView, prefs: SharedPreferences) {
        val neutral = originalPreview || keys
            .all { value(prefs, it) == defaultValue(it) }
        view.setLayerPaint(if (neutral) null else Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix(prefs))
        })
    }
}
