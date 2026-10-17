package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
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
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Siri on long press only acts on BYD's voice keys, so Settings offers it where those keys exist. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land")
class SiriLongPressSwitchTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<DiPlayActivity>()

    @Before fun setUp() = WheelZoomSettings.setSiriLongPressOnly(context, false)

    @After fun tearDown() {
        opened.forEach { it.finish() }
        AppAppearanceRuntime.resetForTest()
        WheelZoomSettings.setSiriLongPressOnly(context, false)
    }

    private fun installBydSettingsPackage() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.carsettings"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.carsettings"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
    }

    /** Settings → Vehicle, where the wheel keys are. */
    private fun vehicleSettings(): DiPlayActivity = Robolectric.buildActivity(DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup().get().also {
        opened += it
        ReflectionHelpers.setField(it, "settingsCategory", SettingsCategory.VEHICLE)
        ReflectionHelpers.callInstanceMethod<Unit>(it, "render")
    }

    private fun texts(screen: DiPlayActivity): List<String> =
        descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun searchTitles(screen: DiPlayActivity): List<String> =
        ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(screen, "buildSettingsSearchIndex")
            .map { it.title }

    @Test fun anotherHeadUnitIsNotOfferedASwitchThatWouldDoNothing() {
        val screen = vehicleSettings()
        val title = screen.getString(R.string.wheel_siri_long_press_only)
        assertFalse(texts(screen).toString(), title in texts(screen))
        assertFalse(title in searchTitles(screen))
        // The wheel's Siri key itself stays, for any head unit.
        assertTrue(texts(screen).toString(), screen.getString(R.string.wheel_siri_key) in texts(screen))
    }

    @Test fun aBydHeadUnitHasTheSwitchWithTheWheelKeys() {
        installBydSettingsPackage()
        val screen = vehicleSettings()
        val shown = texts(screen)
        val title = screen.getString(R.string.wheel_siri_long_press_only)
        assertTrue(shown.toString(), title in shown)
        assertEquals(shown.indexOf(screen.getString(R.string.wheel_siri_long_press_only_description)), shown.indexOf(title) + 1)
        assertTrue(title in searchTitles(screen))
    }

    @Test fun aSwitchAlreadyOnStaysWhereItCanBeTurnedOff() {
        WheelZoomSettings.setSiriLongPressOnly(context, true)
        val screen = vehicleSettings()
        assertTrue(texts(screen).toString(), screen.getString(R.string.wheel_siri_long_press_only) in texts(screen))
    }
}
