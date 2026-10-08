package com.shilapi.xcertplay

import android.graphics.Paint
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Button
import android.widget.Switch
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class LivePictureTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private class RecordingTexture : TextureView(RuntimeEnvironment.getApplication()) {
        var applied: Paint? = null
        override fun setLayerPaint(paint: Paint?) { applied = paint }
    }
    @Before fun reset() {
        CarPlayPicture.showOriginal(false)
        CarPlayPicture.preferences(context).edit().clear().commit()
    }
    @Test fun changesReachBothTexturesAndOriginalComparisonDoesNotOverwriteSettings() {
        val main = RecordingTexture()
        val cluster = RecordingTexture()
        val mainBinding = CarPlayPicture.Binding(main)
        val clusterBinding = CarPlayPicture.Binding(cluster)
        try {
            assertNull(main.applied)
            assertNull(cluster.applied)
            CarPlayPicture.preferences(context).edit().putInt(CarPlayPicture.CONTRAST, 125).apply()
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(main.applied?.colorFilter)
            assertNotNull(cluster.applied?.colorFilter)
            CarPlayPicture.showOriginal(true)
            assertNull(main.applied)
            assertNull(cluster.applied)
            assertEquals(125, CarPlayPicture.value(CarPlayPicture.preferences(context), CarPlayPicture.CONTRAST))
            CarPlayPicture.showOriginal(false)
            assertNotNull(main.applied)
            assertNotNull(cluster.applied)
            mainBinding.close()
            val old = main.applied
            CarPlayPicture.reset(CarPlayPicture.preferences(context))
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(old, main.applied)
            assertNull(cluster.applied)
        } finally {
            mainBinding.close(); clusterBinding.close(); CarPlayPicture.showOriginal(false)
        }
    }
    @Test fun panelSliderSavesImmediatelyAndResetClearsOriginalComparison() {
        val panel = CarPlayPicturePanel(context) {}
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()
        val views = descendants(panel)
        val contrast = views.filterIsInstance<SeekBar>()[1]
        val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 125f) }
        assertTrue(contrast.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
        assertEquals(125, CarPlayPicture.value(CarPlayPicture.preferences(context), CarPlayPicture.CONTRAST))
        val original = views.filterIsInstance<Switch>().single()
        original.isChecked = true
        assertFalse(contrast.isEnabled)
        views.filterIsInstance<Button>().first { it.text == context.getString(R.string.picture_reset) }.performClick()
        assertFalse(original.isChecked)
        assertTrue(contrast.isEnabled)
        assertEquals(100, CarPlayPicture.value(CarPlayPicture.preferences(context), CarPlayPicture.CONTRAST))
        assertEquals(100, contrast.progress)
    }

    @Test fun compatibleSurfaceOutputExplainsUnavailableControlsAndKeepsPreferences() {
        CarPlayPicture.preferences(context).edit().putInt(CarPlayPicture.CONTRAST, 125).commit()
        var closed = false
        val panel = CarPlayPicturePanel(context, adjustmentsAvailable = false) { closed = true }
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()
        val views = descendants(panel)
        assertTrue(views.filterIsInstance<android.widget.TextView>().any {
            it.text == context.getString(R.string.picture_surface_output_unavailable)
        })
        assertTrue(views.filterIsInstance<SeekBar>().all { !it.isEnabled })
        assertFalse(views.filterIsInstance<Switch>().single().isEnabled)
        val reset = views.filterIsInstance<Button>().single { it.text == context.getString(R.string.picture_reset) }
        assertFalse(reset.isEnabled)
        reset.performClick()
        assertEquals(125, CarPlayPicture.value(CarPlayPicture.preferences(context), CarPlayPicture.CONTRAST))
        views.filterIsInstance<Button>().single { it.text == context.getString(R.string.picture_done) }.performClick()
        assertTrue(closed)
    }

    @Test fun picturePanelUsesAndCanRepaintToTheLightOverlayPalette() {
        val panel = CarPlayPicturePanel(context, initialPalette = DiPlayPalette.LIGHT) {}
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()

        assertEquals(
            DiPlayPalette.LIGHT.overlayBackground,
            (panel.background as android.graphics.drawable.ColorDrawable).color,
        )
        assertTrue(descendants(panel).filterIsInstance<android.widget.TextView>()
            .filterNot { it is Button }
            .all { it.currentTextColor == DiPlayPalette.LIGHT.overlayPrimaryText })

        panel.applyPalette(DiPlayPalette.DARK)
        assertEquals(
            DiPlayPalette.DARK.overlayBackground,
            (panel.background as android.graphics.drawable.ColorDrawable).color,
        )
    }
}
