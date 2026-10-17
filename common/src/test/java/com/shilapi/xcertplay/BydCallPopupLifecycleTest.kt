package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.BydCallPopupGuard
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.media.AndroidMediaSink
import android.view.Surface
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.mockito.Mockito.mock
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class BydCallPopupLifecycleTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        // Keep the state/ownership real, but don't launch a real ADB worker from JVM tests.
        ReflectionHelpers.setField(BydCallPopupGuard, "running", true)
        AirPlayPersistence.saveHideBydCallPopup(activity, true)
        ReflectionHelpers.setField(activity, "hostAppearanceResumed", true)
        ReflectionHelpers.setField(activity, "callPopupSessionReady", true)
        CarPlayBackgroundSession.active = true
    }

    @After fun tearDown() {
        BydCallPopupGuard.update(activity, activity, false) { true }
        ReflectionHelpers.setField(BydCallPopupGuard, "running", false)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
            ReflectionHelpers.getField<ExecutorService>(activity, name).shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun onlyOptedInConnectedForegroundOwnerSuppresses() {
        assertTrue(shouldHide())
        for (field in listOf("hostAppearanceResumed", "callPopupSessionReady")) {
            ReflectionHelpers.setField(activity, field, false)
            assertFalse(shouldHide())
            ReflectionHelpers.setField(activity, field, true)
        }
        CarPlayBackgroundSession.active = false
        assertFalse(shouldHide())
        CarPlayBackgroundSession.active = true
        AirPlayPersistence.saveHideBydCallPopup(activity, false)
        assertFalse(shouldHide())
    }

    @Test fun settingsReconnectShutdownAndReplacedOwnerDoNotSuppress() {
        for (field in listOf("menuOpen", "handshakeResetInProgress", "reconnectScheduled")) {
            ReflectionHelpers.setField(activity, field, true)
            assertFalse(shouldHide())
            ReflectionHelpers.setField(activity, field, false)
        }
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        assertFalse(shouldHide())
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(false)
        val replacement = Any()
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", replacement)
        BydCallPopupGuard.update(activity, replacement, true) { true }
        BydCallPopupGuard.update(activity, activity, false) { true }
        assertFalse(shouldHide())
        assertSame(replacement, ReflectionHelpers.getField<Any>(BydCallPopupGuard, "owner"))
        BydCallPopupGuard.update(activity, replacement, false) { true }
    }

    @Test fun blockingFailureRestoresEvenWhenNoSessionEndedCallbackArrives() {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateCallPopupGuard")
        assertSame(activity, ReflectionHelpers.getField<Any>(BydCallPopupGuard, "owner"))
        val report = ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity,
            "createStatusReporter", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0))
        report(CarPlayStatus.Failed("Wi-Fi unavailable", wifiResetRequired = true))
        assertFalse(shouldHide())
        assertNull(ReflectionHelpers.getField<Any?>(BydCallPopupGuard, "owner"))
    }

    @Test fun inSessionPopupSettingCancelsOrSavesWithoutChangingNegotiatedSettings() {
        ReflectionHelpers.setField(activity, "hideBydCallPopup", false)
        val before = ReflectionHelpers.callInstanceMethod<Int>(activity, "menuSettingsSignature")
        ReflectionHelpers.setField(activity, "hideBydCallPopup", true)
        assertEquals(before, ReflectionHelpers.callInstanceMethod<Int>(activity, "menuSettingsSignature"))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "loadConnectionSettings")
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "hideBydCallPopup"))
        ReflectionHelpers.setField(activity, "hideBydCallPopup", false)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "persistMenuSettings")
        assertFalse(AirPlayPersistence.loadHideBydCallPopup(activity))
    }

    @Test fun recreatedHostDoesNotHideFromAStaleActiveFlagAfterFailure() {
        val controller = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 800, 480, activity,
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {}
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateCallPopupGuard")
        val report = ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity,
            "createStatusReporter", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0))
        report(CarPlayStatus.Failed("Wi-Fi unavailable", wifiResetRequired = true))
        assertTrue("Do not change upstream media/session flags", CarPlayBackgroundSession.active)
        val replacement = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            ReflectionHelpers.setField(replacement, "smoothVideo", false)
            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(replacement, "adoptBackgroundSession"))
            ReflectionHelpers.setField(replacement, "hostAppearanceResumed", true)
            assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(replacement, "shouldHideBydCallPopup"))
        } finally {
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
                ReflectionHelpers.getField<ExecutorService>(replacement, name).shutdownNow()
        }
    }

    @Test fun lateOldHostSessionEndRevokesReplacementEligibility() {
        val oldListener = ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(activity,
            "createSessionListener", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0))
        val replacement = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", replacement)
            ReflectionHelpers.setField(replacement, "hostAppearanceResumed", true)
            ReflectionHelpers.setField(replacement, "callPopupSessionReady", true)
            ReflectionHelpers.callInstanceMethod<Unit>(replacement, "updateCallPopupGuard")
            val generation = ReflectionHelpers.getField<Long>(BydCallPopupGuard, "generation")
            oldListener.onSessionEnded(mock(AirPlaySession::class.java))
            assertFalse(CarPlayBackgroundSession.active)
            val current = ReflectionHelpers.callInstanceMethod<Boolean>(BydCallPopupGuard, "current",
                ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType!!, generation))
            assertFalse("The worker must recheck the transferred session before renewing its heartbeat", current)
        } finally {
            BydCallPopupGuard.update(replacement, replacement, false) { true }
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
                ReflectionHelpers.getField<ExecutorService>(replacement, name).shutdownNow()
        }
    }

    private fun shouldHide() = ReflectionHelpers.callInstanceMethod<Boolean>(activity, "shouldHideBydCallPopup")
}
