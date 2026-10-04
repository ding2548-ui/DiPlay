package com.shilapi.xcertplay

import android.content.pm.PackageManager
import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AndroidTvInputModeTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun touchscreenKeepsAndroidNavigation() {
        context.resources.configuration.touchscreen = Configuration.TOUCHSCREEN_FINGER
        assertFalse(AndroidTvInputMode.shouldUseKnobAsPrimaryInput(context))
    }

    @Test fun televisionUsesRemoteNavigationEvenWhenItReportsTouch() {
        context.resources.configuration.touchscreen = Configuration.TOUCHSCREEN_FINGER
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        assertTrue(AndroidTvInputMode.shouldUseKnobAsPrimaryInput(context))
    }

    @Test fun nonTouchHeadUnitUsesRemoteNavigation() {
        context.resources.configuration.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH
        assertTrue(AndroidTvInputMode.shouldUseKnobAsPrimaryInput(context))
    }
}
