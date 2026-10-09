package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class SidePanelLifecycleTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var handler: Handler
    private lateinit var refresh: Runnable
    private lateinit var panel: View

    @Before fun setup() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        handler = get("mainHandler") as Handler
        refresh = get("sidePanelTick") as Runnable
        panel = LinearLayout(activity).also { set("sidePanel", it) }
        set("sidePanelShown", true)
        handler.postDelayed(refresh, 5_000L)
        assertTrue(handler.hasCallbacks(refresh))
    }

    @After fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
    }

    @Test fun destroyingAHostWithThePanelOpenCancelsItsRepeatedRefresh() {
        activity.javaClass.getDeclaredMethod("onDestroy").apply { isAccessible = true }.invoke(activity)
        assertPanelStopped()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertPanelStopped()
    }

    @Test fun shutdownCancelsTheRefreshBeforeTheActivityIsDestroyed() {
        activity.javaClass.getDeclaredMethod("shutdown", Boolean::class.javaPrimitiveType,
            String::class.java, Function0::class.java).apply { isAccessible = true }
            .invoke(activity, false, "side panel cleanup test", {})
        assertPanelStopped()
        assertTrue((get("teardownExecutor") as ExecutorService).awaitTermination(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertPanelStopped()
    }

    private fun assertPanelStopped() {
        assertEquals(false, get("sidePanelShown"))
        assertEquals(View.GONE, panel.visibility)
        assertFalse("A destroyed/stopped host must not keep refreshing its old views", handler.hasCallbacks(refresh))
    }

    private fun get(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun set(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
