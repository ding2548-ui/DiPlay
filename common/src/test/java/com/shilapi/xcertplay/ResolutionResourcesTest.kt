package com.shilapi.xcertplay
import android.content.res.Configuration
import com.shilapi.xcertplay.host.R
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ResolutionResourcesTest {
    @Test fun percentSummaryFormatsInEverySupportedLocale() {
        for (tag in listOf("en", "zh-CN", "ar", "ru", "es", "uk", "zh-TW")) {
            val base = RuntimeEnvironment.getApplication()
            val c = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            assertTrue(c.getString(R.string.custom_resolution_summary, 55).endsWith("%"))
            assertFalse(c.getString(R.string.custom_resolution_hint).isBlank())
        }
    }
}
