package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayConnectionDiagnosticLogTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var listener: AirPlaySessionListener
    private val log get() = File(activity.filesDir, "logs/diplay.log").readText()

    @Before fun prepareOldControllerListener() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.javaClass.getDeclaredMethod("initializeSessionLog").apply { isAccessible = true }.invoke(activity)
        activity.javaClass.getDeclaredField("restartGeneration").apply { isAccessible = true }.set(activity, 2)
        listener = activity.javaClass.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 1) as AirPlaySessionListener
    }

    @After fun cleanup() {
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        activity.javaClass.getDeclaredField("sessionLog").apply { isAccessible = true }
            .get(activity).let { (it as Closeable).close() }
        for (field in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (activity.javaClass.getDeclaredField(field).apply { isAccessible = true }
                .get(activity) as ExecutorService).shutdownNow()
        }
    }

    @Test fun oldTeardownEvidenceSurvivesWithoutAcceptingOtherOldControllerLogs() {
        listener.onDebugLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} attempt=1 phase=CONTROL teardown end elapsedMs=117 executorTerminated=true")
        listener.onDebugLog("old controller ordinary state")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        assertTrue(log.contains("teardown end elapsedMs=117"))
        assertFalse(log.contains("old controller ordinary state"))
        assertEquals(2, activity.javaClass.getDeclaredField("restartGeneration").apply { isAccessible = true }.get(activity))
    }

    @Test fun theDiagnosticPrefixDoesNotBypassCredentialRedaction() {
        listener.onDebugLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} attempt=1 token=private-token")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        assertFalse(log.contains("private-token"))
    }
}
