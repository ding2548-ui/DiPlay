package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

/**
 * What Home says when DiPlay could not turn the car hotspot on at start-up for lack of the
 * "Modify system settings" access, and what happens once the driver allows it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land",
    shadows = [HomeHotspotAccessTest.WritePermission::class, HomeHotspotAccessTest.HotspotState::class,
        HomeHotspotAccessTest.PlatformStart::class])
class HomeHotspotAccessTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<DiPlayActivity>>()

    @Before fun setUp() {
        clearSettings()
        WritePermission.allowed = false
        PlatformStart.calls.set(0)
        HotspotState.on = false
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(context, "Car 5G")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "12345678")
        CarHotspotSettings.setEnabled(context, true)
    }

    @After fun tearDown() {
        controllers.forEach { runCatching { it.get().finish() } }
        AppAppearanceRuntime.resetForTest()
        CarHotspotSettings.setEnabled(context, false)
        WritePermission.allowed = false
        HotspotState.on = false
        clearSettings()
    }

    private fun clearSettings() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun openHome(): ActivityController<DiPlayActivity> = Robolectric.buildActivity(DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "home")).setup().also { controllers += it }

    /** Waits until the start-up attempt's result has reached Home. An attempt that began cancelled never delivers one. */
    private fun awaitResult(home: DiPlayActivity, expected: CarHotspotTethering.Result) {
        repeat(250) {
            shadowOf(Looper.getMainLooper()).idle()
            if (result(home) == expected) return
            Thread.sleep(20)
        }
        throw AssertionError("Home has ${result(home)}, not $expected")
    }

    private fun result(home: DiPlayActivity): CarHotspotTethering.Result? =
        ReflectionHelpers.getField(home, "hotspotStartupResult")

    private fun texts(screen: DiPlayActivity): List<String> =
        descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun tap(screen: DiPlayActivity, text: Int) = descendants(screen.window.decorView)
        .filterIsInstance<TextView>().single { it.text == screen.getString(text) }.performClick()

    @Test fun offBydUnitsHomeAsksForTheSystemAccessAndOpensItsScreen() {
        val home = openHome().get()
        awaitResult(home, CarHotspotTethering.Result.PERMISSION_REQUIRED)
        val shown = texts(home)
        assertTrue(shown.toString(), home.getString(R.string.settings_write_settings_missing) in shown)
        assertTrue(shown.toString(), home.getString(R.string.settings_write_settings_allow) in shown)
        // The ADB sentence and the car settings button belong to BYD units.
        assertFalse(shown.toString(), home.getString(R.string.hotspot_control_missing) in shown)
        assertFalse(shown.toString(), home.getString(R.string.open_car_hotspot_settings) in shown)

        tap(home, R.string.settings_write_settings_allow)
        val opened = shadowOf(home).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_WRITE_SETTINGS, opened.action)
        assertEquals("package:${home.packageName}", opened.dataString)
    }

    @Test fun onABydUnitHomeKeepsTheAdbRoute() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.carsettings"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.carsettings"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
        val home = openHome().get()
        awaitResult(home, CarHotspotTethering.Result.PERMISSION_REQUIRED)
        val shown = texts(home)
        assertTrue(shown.toString(), home.getString(R.string.hotspot_control_missing) in shown)
        assertTrue(shown.toString(), home.getString(R.string.open_car_hotspot_settings) in shown)
        assertFalse(shown.toString(), home.getString(R.string.settings_write_settings_allow) in shown)
    }

    @Test fun onceTheAccessIsAllowedTheHotspotIsStartedOnReturn() {
        val controller = openHome()
        val home = controller.get()
        awaitResult(home, CarHotspotTethering.Result.PERMISSION_REQUIRED)
        // Android's screen, opened from Home or from Settings, covers DiPlay, which stops it; the driver
        // allows the access there.
        controller.pause().stop()
        WritePermission.allowed = true
        controller.restart().start().resume()

        // A new attempt, not one cancelled by DiPlay having been stopped, and the platform was asked once.
        awaitResult(home, CarHotspotTethering.Result.READY)
        assertEquals(1, PlatformStart.calls.get())
        val shown = texts(home)
        assertFalse(shown.toString(), home.getString(R.string.settings_write_settings_missing) in shown)
        assertFalse(shown.toString(), home.getString(R.string.settings_write_settings_allow) in shown)
    }

    @Test fun returningWithoutTheAccessKeepsAskingForIt() {
        val controller = openHome()
        val home = controller.get()
        awaitResult(home, CarHotspotTethering.Result.PERMISSION_REQUIRED)
        controller.pause().stop().restart().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CarHotspotTethering.Result.PERMISSION_REQUIRED, result(home))
        assertTrue(texts(home).toString(), home.getString(R.string.settings_write_settings_missing) in texts(home))
        assertEquals(0, PlatformStart.calls.get())
    }

    /** The platform's tethering call, which turns the car hotspot on. */
    @Implements(className = "com.shilapi.xcertplay.network.PlatformTetheringStart", isInAndroidSdk = false)
    class PlatformStart {
        @Implementation fun start(context: Context, receiver: ResultReceiver, log: (String) -> Unit) {
            calls.incrementAndGet()
            HotspotState.on = true
        }

        companion object {
            val calls = AtomicInteger()
        }
    }

    /** Whether the car hotspot is on, as Android's Wi-Fi state reports it. */
    @Implements(className = "com.shilapi.xcertplay.network.CarHotspotStatus", isInAndroidSdk = false)
    class HotspotState {
        @Implementation fun isEnabled(context: Context): Boolean? = on

        companion object {
            @Volatile var on = false
        }
    }

    @Implements(Settings.System::class)
    class WritePermission {
        companion object {
            @Volatile var allowed = false
            @JvmStatic @Implementation fun canWrite(context: Context): Boolean = allowed
        }
    }
}
