package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.SafeAreaRect
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class SafeAreaEditorCallbackTest {
    @Test fun draggingPreviewCoordinatesPublishesClusterCoordinates() {
        val editor = SafeAreaEditorView(RuntimeEnvironment.getApplication())
        editor.setRect(SafeAreaRect(300, 100, 1500, 620), 1920, 720)
        editor.layout(0, 0, 960, 360)
        var latest: SafeAreaRect? = null
        editor.onRectChanged = { latest = it }
        fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(0, 0, action, x, y, 0)
            editor.onTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 150f, 180f)
        send(MotionEvent.ACTION_MOVE, 100f, 180f)
        assertEquals(SafeAreaRect(200, 100, 1500, 620), latest)
        assertEquals(latest, editor.currentRectForSource())
        editor.interactive = false
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 50f, 180f, 0)
        assertFalse(editor.onTouchEvent(event))
        event.recycle()
        assertEquals(SafeAreaRect(200, 100, 1500, 620), editor.currentRectForSource())
    }
}
