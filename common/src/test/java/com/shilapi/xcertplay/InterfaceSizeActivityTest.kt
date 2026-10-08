package com.shilapi.xcertplay

import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-sw1333dp-w2666dp-h1225dp-land-mdpi", manifest = Config.NONE)
class InterfaceSizeActivityTest {
    @After
    fun resetInterfaceSize() {
        InterfaceSize.save(RuntimeEnvironment.getApplication(), InterfaceSize.AUTO)
    }

    @Test
    @Config(sdk = [28], qualifiers = "en-sw800dp-w1280dp-h800dp-land-mdpi")
    fun fixedScalingKeepsFreshWindowDimensionsAndTheSelectedSettingsCategory() {
        val app = RuntimeEnvironment.getApplication()
        CarPlayBackgroundSession.clear()
        AppLocale.save(app, AppLocale.SYSTEM)
        InterfaceSize.save(app, 150)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup()
        try {
            ReflectionHelpers.setField(activity, "page", "settings")
            ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.DISPLAY)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            assertEquals(240, activity.resources.configuration.densityDpi)
            assertEquals(853, activity.resources.configuration.screenWidthDp)
            assertEquals(160, app.resources.configuration.densityDpi)
            assertEquals(1280, app.resources.configuration.screenWidthDp)

            // ActivityThread merges the context override into the fresh system configuration. A
            // dimension override here would freeze the original 853x533dp window after rotation.
            val installed = ReflectionHelpers.getField<Configuration>(activity, "mOverrideConfiguration")
            fun reported(width: Int, height: Int) = Configuration().apply {
                densityDpi = 160
                screenWidthDp = width
                screenHeightDp = height
                smallestScreenWidthDp = minOf(width, height)
                setLocales(activity.resources.configuration.locales)
                updateFrom(installed)
            }
            assertEquals(0, installed.screenWidthDp)
            assertEquals(0, installed.screenHeightDp)

            activity.onConfigurationChanged(reported(800, 1280))
            assertEquals(240, activity.resources.configuration.densityDpi)
            assertEquals(533, activity.resources.configuration.screenWidthDp)
            assertEquals(853, activity.resources.configuration.screenHeightDp)

            val split = reported(480, 800)
            activity.onMultiWindowModeChanged(true, split)
            activity.onConfigurationChanged(split)
            assertEquals(240, activity.resources.configuration.densityDpi)
            assertEquals(320, activity.resources.configuration.screenWidthDp)
            assertEquals(533, activity.resources.configuration.screenHeightDp)
            assertEquals(SettingsCategory.DISPLAY,
                ReflectionHelpers.getField<SettingsCategory>(activity, "settingsCategory"))
            assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
            assertEquals(1, texts(activity.findViewById(android.R.id.content)).count {
                it.text.startsWith(activity.getString(R.string.settings_interface_size) + " · ")
            })
            val index = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(
                activity, "buildSettingsSearchIndex")
            assertTrue(index.any {
                it.title == activity.getString(R.string.settings_interface_size) &&
                    it.category == SettingsCategory.DISPLAY
            })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(sdk = [28], qualifiers = "en-sw800dp-w1280dp-h800dp-land-mdpi")
    @LooperMode(LooperMode.Mode.PAUSED)
    fun aRealSystemDensityChangeRecreatesAtTheNewScaledDensityAndKeepsSettings() {
        val app = RuntimeEnvironment.getApplication()
        CarPlayBackgroundSession.clear()
        AppLocale.save(app, AppLocale.SYSTEM)
        InterfaceSize.save(app, 150)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup()
        try {
            ReflectionHelpers.setField(activity, "page", "settings")
            ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.DISPLAY)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val installed = ReflectionHelpers.getField<Configuration>(activity, "mOverrideConfiguration")
            assertEquals(240, activity.resources.configuration.densityDpi)
            assertEquals(160, app.resources.configuration.densityDpi)

            RuntimeEnvironment.setQualifiers("en-sw400dp-w640dp-h400dp-land-xhdpi")
            assertEquals(320, app.resources.configuration.densityDpi)
            val reported = Configuration(app.resources.configuration).apply { updateFrom(installed) }
            assertEquals(240, reported.densityDpi)
            activity.onConfigurationChanged(reported)
            assertTrue(ReflectionHelpers.getField<Boolean>(activity, "interfaceRecreateRequested"))
            shadowOf(Looper.getMainLooper()).idle()

            val resized = controller.get()
            assertNotSame(activity, resized)
            assertEquals(480, resized.resources.configuration.densityDpi)
            assertEquals(427, resized.resources.configuration.screenWidthDp)
            assertEquals(267, resized.resources.configuration.screenHeightDp)
            assertEquals(320, app.resources.configuration.densityDpi)
            assertEquals("settings", ReflectionHelpers.getField<String>(resized, "page"))
            assertEquals(SettingsCategory.DISPLAY,
                ReflectionHelpers.getField<SettingsCategory>(resized, "settingsCategory"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun texts(root: View): List<TextView> =
        (if (root is TextView) listOf(root) else emptyList()) +
            (if (root is ViewGroup) (0 until root.childCount).flatMap { texts(root.getChildAt(it)) } else emptyList())

    @Test
    fun scaledSettingsKeepTheChosenLanguageBeforeAndroid13() {
        CarPlayBackgroundSession.clear()
        AppLocale.save(RuntimeEnvironment.getApplication(), AppLocale.ARABIC)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup()
        try {
            val configuration = activity.resources.configuration
            assertEquals(720, configuration.smallestScreenWidthDp)
            assertEquals("ar", configuration.locales[0].language)
        } finally {
            controller.pause().stop().destroy()
            AppLocale.save(RuntimeEnvironment.getApplication(), AppLocale.SYSTEM)
        }
    }

    @Test
    fun theChosenLanguageIsWrittenBackWhenTheSystemLanguageReturns() {
        val app = RuntimeEnvironment.getApplication()
        AppLocale.save(app, AppLocale.SPANISH)
        try {
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(
                android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) },
                app.resources.displayMetrics,
            )
            assertEquals(true, AppLocale.enforce(app))
            assertEquals("es", app.resources.configuration.locales[0].language)
            assertEquals(false, AppLocale.enforce(app))
            AppLocale.save(app, AppLocale.SYSTEM)
            assertEquals(true, AppLocale.enforce(app))
            assertEquals(
                android.content.res.Resources.getSystem().configuration.locales[0],
                app.resources.configuration.locales[0],
            )
        } finally {
            AppLocale.save(app, AppLocale.SYSTEM)
        }
    }
}
