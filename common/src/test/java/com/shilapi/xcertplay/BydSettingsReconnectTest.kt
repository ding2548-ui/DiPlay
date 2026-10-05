package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import android.widget.LinearLayout
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.BydVehicleFieldStore
import com.shilapi.xcertplay.hud.BydVehicleProbeOutcome
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Checks the combined hotspot/settings page against the real vehicle preflight callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class BydSettingsReconnectTest {
    private lateinit var activity: DiPlayActivity
    private lateinit var backend: DeferredBackend
    private var reconnects = 0

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        for (name in listOf("diplay_byd_outputs", "diplay_byd_vehicle_fields", "diplay_car_hotspot", "xcertplay_airplay", "diplay")) {
            app.getSharedPreferences(name, 0).edit().clear().commit()
        }
        BydVehicleFieldStore.clearMemoryForTests()
        CarPlayBackgroundSession.clear()
        backend = DeferredBackend()
        BydVehicleSettingsBackendProvider.current = backend
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(activity, "page", "settings")
        ReflectionHelpers.setField(activity, "bydVehicleAdvancedExpanded", true)
        AirPlayPersistence.saveWirelessEnabled(activity, false)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
    }

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
        BydVehicleSettingsBackendProvider.reset()
        BydVehicleFieldStore.clearMemoryForTests()
    }

    @Test fun theProgressRenderDoesNotDiscardAReadableBatteryPreflight() {
        beginBatteryReconnect()
        assertEquals(0, reconnects)
        backend.complete()
        assertEquals(1, reconnects)
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "adbCheckInProgress"))
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
    }

    @Test fun aLateHotspotEligibilityResultDoesNotCancelTheVehiclePreflight() {
        beginBatteryReconnect()
        renderHotspotCard()
        backend.complete()
        assertEquals(1, reconnects)
        assertEquals(1, backend.checks)
    }

    @Test fun anUnrelatedSamePageRenderPreservesThePendingVehicleRead() {
        beginBatteryReconnect()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        backend.complete()
        assertEquals(1, reconnects)
    }

    @Test fun anUnreadableBatteryKeepsTheCurrentConnectionAndThePendingSetting() {
        backend.result = BydAdbAccess.Status(BydAdbAccess.State.READY)
        beginBatteryReconnect()
        backend.complete()
        assertEquals(0, reconnects)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(BydOutputSettings.batteryToIphone(activity))
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "vehicleDataReconnectPending"))
    }

    @Test fun unavailableAdbDoesNotReconnectEvenIfTheHotspotCardIsReady() {
        backend.result = BydAdbAccess.Status(BydAdbAccess.State.ADB_OFF)
        beginBatteryReconnect()
        renderHotspotCard()
        backend.complete()
        assertEquals(0, reconnects)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun hotspotAuthorizationPreventsAnOverlappingVehicleCheckOrProbe() {
        ReflectionHelpers.setField(activity, "adbSwitchChangePending", true)
        checkVehicleState()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "probeVehicleData",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        assertTrue(backend.tasks.isEmpty())
        assertEquals(0, backend.checks)
        assertFalse(BydOutputSettings.legacyVehicleProbe(activity))
    }

    @Test fun destructionInvalidatesThePendingReconnect() {
        beginBatteryReconnect()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onDestroy")
        backend.complete()
        assertEquals(0, reconnects)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun beginBatteryReconnect() {
        BydOutputSettings.setBatteryToIphone(activity, true)
        val stop: ((() -> Unit) -> Unit) = { done -> reconnects++; done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        ReflectionHelpers.setField(activity, "vehicleDataReconnectPending", true)
        checkVehicleState()
        assertEquals(1, backend.tasks.size)
    }
    private fun checkVehicleState() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "checkAdbState",
        ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
    private fun renderHotspotCard() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderBydAdbControls",
        ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, LinearLayout(activity)),
        ReflectionHelpers.ClassParameter.from(LocalAdb.Access::class.java, LocalAdb.Access.READY))

    private class DeferredBackend : BydVehicleSettingsBackend {
        var result = BydAdbAccess.Status(BydAdbAccess.State.READY, batteryPercent = 51.0, rangeKm = 100)
        var checks = 0
        val tasks = mutableListOf<() -> Unit>()
        override fun check(context: Context, mayAsk: Boolean): BydAdbAccess.Status { checks++; return result }
        override fun checkState(context: Context, mayAsk: Boolean) = result.state
        override fun probe(context: Context, persist: Boolean) = BydVehicleProbeOutcome(result.state)
        override fun execute(name: String, block: () -> Unit) { tasks.add(block) }
        fun complete() {
            tasks.removeAt(0).invoke()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
