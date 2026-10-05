package com.shilapi.xcertplay

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "sw720dp-w1280dp-h720dp-hdpi")
class CenterMapOverlayTest {
    private lateinit var context: Context
    private lateinit var card: View
    private var taps = 0
    private var time = 0L
    private val params get() = card.layoutParams as WindowManager.LayoutParams

    @Before fun showCard() {
        context = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        context.getSharedPreferences("diplay_center_map", Context.MODE_PRIVATE).edit()
            .clear().putInt("width", 640).putInt("x", 500).putInt("y", 300).commit()
        assertTrue(CenterMapOverlay.show(context, 8.0 / 3.0, {}, { taps++ }))
        card = CenterMapOverlay.javaClass.getDeclaredField("root").apply { isAccessible = true }
            .get(CenterMapOverlay) as View
        card.layout(0, 0, params.width, params.height)
    }

    @After fun hideCard() { CenterMapOverlay.hide() }

    @Test fun smallPinchResizesBelowTheHeadUnitMinimumSpanAndPersistsWithoutOpeningCarPlay() {
        assertTrue("Regression uses a span too small for the platform detector",
            ViewConfiguration.get(context).scaledMinimumScalingSpan > 60)
        touch(MotionEvent.ACTION_DOWN, 0 to 300f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0 to 300f, 1 to 330f)
        touch(MotionEvent.ACTION_MOVE, 0 to 300f, 1 to 330f)
        touch(MotionEvent.ACTION_MOVE, 0 to 285f, 1 to 345f)
        assertEquals(840, params.width)
        assertEquals(315, params.height)
        assertEquals(820, params.x + params.width / 2)
        assertEquals(420, params.y + params.height / 2)
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0 to 285f, 1 to 345f)
        touch(MotionEvent.ACTION_UP, 0 to 285f)
        assertEquals(0, taps)
        assertEquals(840, context.getSharedPreferences("diplay_center_map", Context.MODE_PRIVATE).getInt("width", 0))
    }

    @Test fun pinchRespectsSizeLimitsAndKeepsCardOnScreen() {
        touch(MotionEvent.ACTION_DOWN, 0 to 200f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0 to 200f, 1 to 320f)
        touch(MotionEvent.ACTION_MOVE, 0 to 200f, 1 to 320f)
        touch(MotionEvent.ACTION_MOVE, 0 to 245f, 1 to 275f)
        val screen = context.resources.displayMetrics
        assertEquals((screen.widthPixels * 0.25).toInt(), params.width)
        touch(MotionEvent.ACTION_MOVE, 0 to 0f, 1 to 1200f)
        assertEquals(minOf(screen.widthPixels, (screen.heightPixels * 8.0 / 3.0).toInt()), params.width)
        assertTrue(params.x >= 0 && params.x + params.width <= screen.widthPixels)
        assertTrue(params.y >= 0 && params.y + params.height <= screen.heightPixels)
    }

    @Test fun remainingFingerAfterPinchCannotDragOrTap() {
        touch(MotionEvent.ACTION_DOWN, 7 to 300f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 7 to 300f, 11 to 330f)
        touch(MotionEvent.ACTION_MOVE, 7 to 300f, 11 to 330f)
        touch(MotionEvent.ACTION_MOVE, 7 to 285f, 11 to 345f)
        touch(MotionEvent.ACTION_POINTER_UP, 7 to 285f, 11 to 345f)
        val x = params.x
        touch(MotionEvent.ACTION_MOVE, 11 to 500f)
        touch(MotionEvent.ACTION_UP, 11 to 500f)
        assertEquals(x, params.x)
        assertEquals(840, params.width)
        assertEquals(0, taps)
    }

    @Test fun tapAndSingleFingerDragStillWork() {
        touch(MotionEvent.ACTION_DOWN, 0 to 100f)
        touch(MotionEvent.ACTION_UP, 0 to 100f)
        assertEquals(1, taps)
        touch(MotionEvent.ACTION_DOWN, 0 to 100f)
        touch(MotionEvent.ACTION_MOVE, 0 to 180f)
        touch(MotionEvent.ACTION_UP, 0 to 180f)
        assertEquals(580, params.x)
        assertEquals(640, params.width)
        assertEquals(1, taps)
    }

    @Test fun extraFingerDoesNotChangeScaleAndReplacingAFingerStartsFromTheCurrentSize() {
        touch(MotionEvent.ACTION_DOWN, 7 to 300f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 7 to 300f, 11 to 330f)
        touch(MotionEvent.ACTION_MOVE, 7 to 300f, 11 to 330f)
        touch(MotionEvent.ACTION_MOVE, 7 to 285f, 11 to 345f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 7 to 285f, 11 to 345f, 2 to 500f)
        touch(MotionEvent.ACTION_MOVE, 7 to 285f, 11 to 345f, 2 to 900f)
        assertEquals(840, params.width)
        touch(MotionEvent.ACTION_POINTER_UP, 7 to 285f, 11 to 345f, 2 to 900f)
        assertEquals(840, params.width)
        touch(MotionEvent.ACTION_MOVE, 11 to 345f, 2 to 900f)
        touch(MotionEvent.ACTION_MOVE, 11 to 345f, 2 to 761.25f)
        assertEquals(630, params.width)
        assertEquals(0, taps)
    }

    @Test fun fingerJitterDoesNotResizeOrOpenCarPlay() {
        val jitter = ViewConfiguration.get(context).scaledTouchSlop / 2f
        touch(MotionEvent.ACTION_DOWN, 0 to 300f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0 to 300f, 1 to 330f)
        touch(MotionEvent.ACTION_MOVE, 0 to 300f, 1 to 330f)
        touch(MotionEvent.ACTION_MOVE, 0 to 300f, 1 to 330f + jitter)
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0 to 300f, 1 to 330f + jitter)
        touch(MotionEvent.ACTION_UP, 0 to 300f)
        assertEquals(640, params.width)
        assertEquals(0, taps)
    }

    @Test fun placingSecondFingerWaitsForStableCoordinatesBeforeResizing() {
        touch(MotionEvent.ACTION_DOWN, 0 to 200f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0 to 200f, 1 to 500f)
        assertEquals(640, params.width)
        // The first MOVE can correct the initial contact coordinates on the head unit.
        touch(MotionEvent.ACTION_MOVE, 0 to 285f, 1 to 345f)
        assertEquals("Settling the second contact must not shrink the card", 640, params.width)
        touch(MotionEvent.ACTION_MOVE, 0 to 270f, 1 to 360f)
        assertTrue("Subsequent deliberate spreading must enlarge it", params.width > 640)
    }

    @Test fun spreadingImmediatelyGrowsFromMinimumWithoutLiftingFingers() {
        touch(MotionEvent.ACTION_DOWN, 0 to 200f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0 to 200f, 1 to 400f)
        touch(MotionEvent.ACTION_MOVE, 0 to 200f, 1 to 400f)
        touch(MotionEvent.ACTION_MOVE, 0 to 275f, 1 to 325f)
        touch(MotionEvent.ACTION_MOVE, 0 to 295f, 1 to 305f)
        val minimum = (context.resources.displayMetrics.widthPixels * 0.25).toInt()
        assertEquals(minimum, params.width)
        touch(MotionEvent.ACTION_MOVE, 0 to 290f, 1 to 310f)
        assertTrue("Reversing at the minimum must immediately increase the size", params.width > minimum)
    }

    @Test fun squeezingImmediatelyShrinksFromMaximumWithoutLiftingFingers() {
        touch(MotionEvent.ACTION_DOWN, 0 to 200f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0 to 200f, 1 to 240f)
        touch(MotionEvent.ACTION_MOVE, 0 to 200f, 1 to 240f)
        touch(MotionEvent.ACTION_MOVE, 0 to 0f, 1 to 1000f)
        touch(MotionEvent.ACTION_MOVE, 0 to 0f, 1 to 1200f)
        val maximum = params.width
        touch(MotionEvent.ACTION_MOVE, 0 to 50f, 1 to 1150f)
        assertTrue("Reversing at the maximum must immediately decrease the size", params.width < maximum)
    }

    @Test fun aSavedMinimumSizeCanBeEnlargedWithAFreshSmallPinch() {
        touch(MotionEvent.ACTION_DOWN, 0 to 200f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0 to 200f, 1 to 400f)
        touch(MotionEvent.ACTION_MOVE, 0 to 200f, 1 to 400f)
        touch(MotionEvent.ACTION_MOVE, 0 to 275f, 1 to 325f)
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0 to 275f, 1 to 325f)
        touch(MotionEvent.ACTION_UP, 0 to 275f)
        val minimum = params.width
        CenterMapOverlay.hide()
        assertTrue(CenterMapOverlay.show(context, 8.0 / 3.0, {}, { taps++ }))
        card = CenterMapOverlay.javaClass.getDeclaredField("root").apply { isAccessible = true }
            .get(CenterMapOverlay) as View
        card.layout(0, 0, params.width, params.height)
        assertEquals(minimum, params.width)
        touch(MotionEvent.ACTION_DOWN, 0 to 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0 to 100f, 1 to 150f)
        touch(MotionEvent.ACTION_MOVE, 0 to 100f, 1 to 150f)
        assertEquals(minimum, params.width)
        val spread = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        touch(MotionEvent.ACTION_MOVE, 0 to 100f - spread, 1 to 150f + spread)
        assertTrue("A reopened minimum-sized card must still enlarge", params.width > minimum)
        assertEquals(0, taps)
    }

    @Test fun showWith16By9AspectProportionsHeightProperly() {
        CenterMapOverlay.hide()
        context.getSharedPreferences("diplay_center_map", Context.MODE_PRIVATE).edit()
            .clear().putInt("width", 720).commit()
        assertTrue(CenterMapOverlay.show(context, 16.0 / 9.0, {}, {}))
        card = CenterMapOverlay.javaClass.getDeclaredField("root").apply { isAccessible = true }
            .get(CenterMapOverlay) as View
        assertEquals(720, params.width)
        assertEquals(405, params.height)
    }

    @Test fun changedAspectPersistsAdaptedWidthAcrossSubsequentShows() {
        CenterMapOverlay.hide()
        val prefs = context.getSharedPreferences("diplay_center_map", Context.MODE_PRIVATE)
        prefs.edit().putInt("width", 1000)
            .putLong("aspect", java.lang.Double.doubleToRawLongBits(8.0 / 3)).commit()
        assertTrue(CenterMapOverlay.show(context, 16.0 / 9, {}, {}))
        val width = prefs.getInt("width", 0)
        assertTrue(width != 1000)
        CenterMapOverlay.hide()
        assertTrue(CenterMapOverlay.show(context, 16.0 / 9, {}, {}))
        card = CenterMapOverlay.javaClass.getDeclaredField("root").apply { isAccessible = true }.get(CenterMapOverlay) as View
        assertEquals(width, params.width)
    }

    private fun touch(action: Int, vararg pointers: Pair<Int, Float>) {
        time += 16
        val properties = pointers.map { (id, _) -> MotionEvent.PointerProperties().apply {
            this.id = id
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = pointers.map { (_, x) -> MotionEvent.PointerCoords().apply {
            this.x = x
            y = 100f
            pressure = 1f
            size = 1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(0, time, action, pointers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue(card.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
}
