package com.shilapi.xcertplay

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DarkModeTest {
    @Test
    fun darkModeIsDetectedWithUnrelatedConfigurationBits() {
        assertEquals(true, nightModeOrNull(Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_CAR))
    }

    @Test
    fun lightModeIsDetectedWithUnrelatedConfigurationBits() {
        assertEquals(false, nightModeOrNull(Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_CAR))
    }

    @Test
    fun undefinedModeIsIgnored() {
        assertNull(nightModeOrNull(Configuration.UI_MODE_NIGHT_UNDEFINED))
        assertNull(nightModeOrNull(Configuration.UI_MODE_TYPE_CAR))
    }
}
