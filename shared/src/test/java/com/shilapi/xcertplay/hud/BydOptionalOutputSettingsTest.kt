package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydOptionalOutputSettingsTest {
    @Test fun optionalOutputsDefaultOffAndKeepExplicitPreviousSelections() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertFalse(BydOutputSettings.hudSong(app))
        assertEquals(BydOemClusterHold.OFF, BydOutputSettings.oemClusterHold(app))
        prefs.edit().putBoolean("oem_cluster_freeze", true).commit()
        assertEquals(BydOemClusterHold.PACKAGE, BydOutputSettings.oemClusterHold(app))
        BydOutputSettings.setOemClusterHold(app, BydOemClusterHold.COMPONENT)
        assertEquals(BydOemClusterHold.COMPONENT, BydOutputSettings.oemClusterHold(app))
    }

    @Test fun installedStockReceiverDoesNotEnableUnverifiedDilink4Hud() {
        val app = RuntimeEnvironment.getApplication()
        val knownApp = object : ContextWrapper(app) {
            override fun getPackageName(): String = "com.shihab.diplay"
        }
        ShadowBuild.setFingerprint("BYD/DiLink4:10/unverified")
        val info = PackageInfo().apply {
            packageName = "com.byd.clusterdebug"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.clusterdebug"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        }
        shadowOf(app.packageManager).installPackage(info)
        assertFalse(BydStandaloneHudOutput.available(knownApp))
        assertTrue(BydStandaloneHudOutput.diagnostics(knownApp).contains("standaloneHudAvailable=false"))
    }

}
