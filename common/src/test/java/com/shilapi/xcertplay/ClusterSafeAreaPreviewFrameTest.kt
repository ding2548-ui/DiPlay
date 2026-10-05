package com.shilapi.xcertplay

import android.widget.LinearLayout
import android.view.View
import com.shilapi.xcertplay.airplay.SafeAreaRect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterSafeAreaPreviewFrameTest {
    @Test fun weightedEditorGetsVisibleSpaceInWideAndShallowDialog() {
        val context = RuntimeEnvironment.getApplication()
        for ((width, height) in listOf(1500 to 500, 960 to 300)) {
            val editor = SafeAreaEditorView(context)
            val rect = SafeAreaRect(0, 0, 1920, 720)
            editor.setRect(rect, 1920, 720)
            val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            root.addView(View(context), LinearLayout.LayoutParams(-1, 80))
            root.addView(ClusterSafeAreaPreviewFrame(context, editor), LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(View(context), LinearLayout.LayoutParams(-1, 56))
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            assertTrue(editor.width > 0)
            assertTrue(editor.height > 0)
            assertTrue(editor.width <= width)
            assertTrue(editor.height <= height - 136)
            assertEquals(1920f / 720f, editor.width.toFloat() / editor.height, 0.02f)
            assertEquals(rect, editor.currentRectForSource())
        }
    }
}
