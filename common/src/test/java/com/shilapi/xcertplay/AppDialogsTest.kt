package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppDialogsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        AppAppearanceRuntime.resetForTest()
    }

    @Test fun dialogThemesUseTheResolvedAppAppearance() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.DARK)
        assertDialogColors(context.appDialogContext(), DiPlayPalette.DARK)

        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        assertDialogColors(context.appDialogContext(), DiPlayPalette.LIGHT)
    }

    @Test fun anExistingDialogContextKeepsItsAppearanceAfterThePreferenceChanges() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        val existingContext = context.appDialogBuilder().context

        AirPlayPersistence.saveAppAppearance(context, AppAppearance.DARK)

        assertDialogColors(existingContext, DiPlayPalette.LIGHT)
        assertDialogColors(context.appDialogBuilder().context, DiPlayPalette.DARK)
    }

    @Test fun dialogUsesTheOwnersCurrentAppearanceInsteadOfResolvingItAgain() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.DARK)
        val lightOwner = object : ContextWrapper(context), AppAppearanceOwner {
            override val currentAppNight = false
        }

        assertDialogColors(lightOwner.appDialogContext(), DiPlayPalette.LIGHT)
    }

    private fun assertDialogColors(themedContext: android.content.Context, palette: DiPlayPalette) {
        val attributes = themedContext.theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorAccent, android.R.attr.windowBackground),
        )
        try {
            assertEquals(palette.accent, attributes.getColor(0, 0))
            assertEquals(palette.surface, (attributes.getDrawable(1) as ColorDrawable).color)
        } finally {
            attributes.recycle()
        }
    }
}
