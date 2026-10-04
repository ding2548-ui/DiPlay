package com.shilapi.xcertplay

import android.content.res.Configuration
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeDiagnosticsTest {
    @Test fun unchangedPollingIsBoundedButStillConfirmsTheSignalWasObserved() {
        val diagnostics = ThemeModeDiagnostics()
        val mode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_CAR
        assertNotNull(diagnostics.observe(ThemeModeDiagnostics.Source.CREATE, mode, true, false, 0))
        for (time in 2_000L until 60_000L step 2_000L) {
            assertNull(diagnostics.observe(ThemeModeDiagnostics.Source.POLL, mode, true, false, time))
        }
        val sample = diagnostics.observe(ThemeModeDiagnostics.Source.POLL, mode, true, false, 60_000)!!
        assertTrue(sample.contains("reported=dark applied=dark"))
        assertTrue(sample.contains("pollsSinceSample=30 callbacksSinceSample=0"))
        assertNull(diagnostics.observe(ThemeModeDiagnostics.Source.POLL, mode, true, false, 62_000))
    }

    @Test fun callbackChangesAreRecordedWithoutWaitingForTheHeartbeat() {
        val diagnostics = ThemeModeDiagnostics()
        diagnostics.observe(ThemeModeDiagnostics.Source.CREATE, Configuration.UI_MODE_NIGHT_YES, true, true, 0)
        val sample = diagnostics.observe(
            ThemeModeDiagnostics.Source.CALLBACK, Configuration.UI_MODE_NIGHT_NO, false, true, 1,
        )!!
        assertTrue(sample.contains("source=configuration-callback"))
        assertTrue(sample.contains("nightMask=0x10 reported=light applied=light sessionActive=true"))
        assertTrue(sample.contains("callbacksSinceSample=1"))
    }

    @Test fun undefinedFirmwareSignalIsSeparateFromTheRetainedAppliedMode() {
        val diagnostics = ThemeModeDiagnostics()
        val sample = diagnostics.observe(
            ThemeModeDiagnostics.Source.POLL, Configuration.UI_MODE_TYPE_CAR, true, true, 0,
        )!!
        assertTrue(sample.contains("nightMask=0x0 reported=undefined applied=dark"))
    }

    @Test fun sessionStartAndVisibilityBoundariesRemainVisibleWithAnUnchangedTheme() {
        val diagnostics = ThemeModeDiagnostics()
        val mode = Configuration.UI_MODE_NIGHT_NO
        diagnostics.observe(ThemeModeDiagnostics.Source.CREATE, mode, false, false, 0)
        assertNotNull(diagnostics.observe(ThemeModeDiagnostics.Source.START, mode, false, false, 1))
        assertNotNull(diagnostics.observe(ThemeModeDiagnostics.Source.SESSION_ACTIVE, mode, false, true, 2))
        assertNotNull(diagnostics.observe(ThemeModeDiagnostics.Source.STOP, mode, false, true, 3))
    }
}
