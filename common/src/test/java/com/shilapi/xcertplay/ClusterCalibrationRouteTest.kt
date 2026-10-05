package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import com.shilapi.xcertplay.airplay.SafeAreaRect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [ClusterCalibrationRouteTest.Router::class])
internal class ClusterCalibrationRouteTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val previewHosts = mutableListOf<Activity>()
    private val rect = SafeAreaRect(300, 100, 1500, 620)

    @Before fun reset() {
        ClusterActivityOutput.stopForSettings()
        AirPlayPersistence.saveAdbClusterEnabled(app, true)
        Router.started = CountDownLatch(1)
        Router.token = null
        Router.holdStockMap = true
    }

    @After fun cleanup() { ClusterActivityOutput.stopForSettings(); previewHosts.clear() }

    private fun preview(owner: Any): String {
        val host = Robolectric.buildActivity(Activity::class.java).get().also(previewHosts::add)
        ClusterActivityOutput.beginSafeAreaPreview(owner, rect, host)
        assertTrue("Preview routing did not start", Router.started.await(5, TimeUnit.SECONDS))
        assertFalse("Calibration must not disable the stock map", Router.holdStockMap)
        return Router.token!!
    }

    @Test fun editorLaunchesBeforeCarPlayAndDismissalInvalidatesPendingLaunch() {
        val owner = Any()
        val token = preview(owner)
        assertEquals(-1, ClusterActivityOutput.mainTaskId)
        assertFalse(ClusterActivityOutput.streamActive)
        assertTrue(ClusterActivityOutput.acceptsToken(token))
        ClusterActivityOutput.endSafeAreaPreview(owner)
        assertNull(ClusterActivityOutput.previewRect)
        assertFalse(ClusterActivityOutput.acceptsToken(token))
        val window = Robolectric.buildActivity(AdbClusterActivity::class.java).get()
        assertFalse(ClusterActivityOutput.confirm(window, token, 7))
    }

    @Test fun previewOnlyWindowClosesWhenEditorEnds() {
        val owner = Any()
        val token = preview(owner)
        val window = Robolectric.buildActivity(AdbClusterActivity::class.java).get()
        assertTrue(ClusterActivityOutput.confirm(window, token, 7))
        ClusterActivityOutput.endSafeAreaPreview(owner)
        assertTrue(window.isFinishing)
        assertFalse(ClusterActivityOutput.hasConfirmedRoute())
    }

    @Test fun playbackCanAdoptPendingPreviewAndEditorDismissalKeepsItsWindow() {
        val editor = Any()
        val token = preview(editor)
        val playback = Any()
        ClusterActivityOutput.bind(playback, 42) { }
        assertTrue(ClusterActivityOutput.acceptsToken(token))
        val window = Robolectric.buildActivity(AdbClusterActivity::class.java).get()
        assertTrue(ClusterActivityOutput.confirm(window, token, 7))
        ClusterActivityOutput.setStreamActive(true)
        ClusterActivityOutput.endSafeAreaPreview(editor)
        assertFalse(window.isFinishing)
        assertTrue(ClusterActivityOutput.hasConfirmedRoute())
        assertTrue(ClusterActivityOutput.streamActive)
        assertNull(ClusterActivityOutput.previewRect)
        ClusterActivityOutput.stop(playback)
        assertTrue(window.isFinishing)
    }

    @Test fun staleEditorCannotCloseReplacementPreview() {
        val first = Any()
        val token = preview(first)
        val window = Robolectric.buildActivity(AdbClusterActivity::class.java).get()
        assertTrue(ClusterActivityOutput.confirm(window, token, 7))
        val second = Any()
        val host = Robolectric.buildActivity(Activity::class.java).get().also(previewHosts::add)
        ClusterActivityOutput.beginSafeAreaPreview(second, rect, host)
        ClusterActivityOutput.endSafeAreaPreview(first)
        assertFalse(window.isFinishing)
        assertEquals(rect, ClusterActivityOutput.previewRect)
        ClusterActivityOutput.endSafeAreaPreview(second)
        assertTrue(window.isFinishing)
    }

    @Implements(AdbClusterRouter::class, isInAndroidSdk = false)
    class Router {
        @Implementation fun launch(context: Context, token: String, holdStockMap: Boolean,
            prepare: (Int) -> Boolean): AdbClusterRouter.Result {
            Companion.token = token
            Companion.holdStockMap = holdStockMap
            val accepted = prepare(7)
            started.countDown()
            return AdbClusterRouter.Result(accepted, "test route")
        }
        companion object {
            var started = CountDownLatch(1)
            var token: String? = null
            var holdStockMap = true
        }
    }
}
