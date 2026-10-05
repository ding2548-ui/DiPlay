package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeScreenMonitorTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun knownCarLaunchersContainDiYouDesktop() {
        assertTrue(HomeScreenMonitor.KNOWN_CAR_LAUNCHERS.contains("com.smg.dydesktop"))
        assertTrue(HomeScreenMonitor.KNOWN_CAR_LAUNCHERS.contains("com.smg.dydesktop.pro"))
        assertTrue(HomeScreenMonitor.KNOWN_CAR_LAUNCHERS.contains("com.dy.launcher"))
        assertTrue(HomeScreenMonitor.KNOWN_CAR_LAUNCHERS.contains("com.king.dyzm"))
        assertTrue(HomeScreenMonitor.KNOWN_CAR_LAUNCHERS.contains("com.dudu.android.launcher"))
    }

    @Test
    fun isHomePackageRecognizesBYDAndThirdPartyLaunchers() {
        val monitor = HomeScreenMonitor(context) {}
        val isHomeMethod = HomeScreenMonitor::class.java.getDeclaredMethod("isHomePackage", String::class.java).apply {
            isAccessible = true
        }

        // Standard BYD launchers
        assertTrue(isHomeMethod.invoke(monitor, "com.android.launcher3") as Boolean)
        assertTrue(isHomeMethod.invoke(monitor, "com.byd.launchermap") as Boolean)
        assertTrue(isHomeMethod.invoke(monitor, "com.byd.naviauto") as Boolean)
        assertTrue(isHomeMethod.invoke(monitor, "com.byd.mycar") as Boolean)

        // Third-party launchers
        assertTrue(isHomeMethod.invoke(monitor, "com.smg.dydesktop") as Boolean)
        assertTrue(isHomeMethod.invoke(monitor, "com.smg.dydesktop.pro") as Boolean)
        assertTrue(isHomeMethod.invoke(monitor, "com.dy.launcher") as Boolean)
        assertTrue(isHomeMethod.invoke(monitor, "com.king.dyzm") as Boolean)
        assertTrue(isHomeMethod.invoke(monitor, "com.dudu.android.launcher") as Boolean)
        assertFalse(isHomeMethod.invoke(monitor, "com.custom.carlauncher") as Boolean)

        // Non-home full-screen apps
        assertFalse(isHomeMethod.invoke(monitor, "com.byd.panoramic") as Boolean)
        assertFalse(isHomeMethod.invoke(monitor, "com.netease.cloudmusic") as Boolean)
        assertFalse(isHomeMethod.invoke(monitor, "com.byd.carsetting") as Boolean)
        assertFalse(isHomeMethod.invoke(monitor, "com.kugou.android") as Boolean)
        assertFalse(isHomeMethod.invoke(monitor, "com.autonavi.amapauto") as Boolean)
        assertFalse(isHomeMethod.invoke(monitor, "com.yecon.carsetting") as Boolean)
        assertFalse(isHomeMethod.invoke(monitor, "com.unrelated.desktop.settings") as Boolean)
    }

    @Test
    fun externalForegroundNotificationDispatchesVisibilityChanges() {
        var observedVisibility: Boolean? = null
        val monitor = HomeScreenMonitor(context) { visible ->
            observedVisibility = visible
        }

        monitor.start()
        assertTrue(monitor.running)

        // Switch to NetEase music -> should hide (false)
        HomeScreenMonitor.notifyForegroundPackage("com.netease.cloudmusic")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(false, observedVisibility)

        // Switch to DiYou Desktop -> should show (true)
        HomeScreenMonitor.notifyForegroundPackage("com.smg.dydesktop")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(true, observedVisibility)

        // Switch to 360 Panoramic Camera -> should hide (false)
        HomeScreenMonitor.notifyForegroundPackage("com.byd.panoramic")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(false, observedVisibility)

        monitor.stop()
        assertFalse(monitor.running)
    }
    @Test fun stoppedMonitorReleasesForegroundListener() {
        val monitor = HomeScreenMonitor(context) {}
        monitor.start()
        monitor.stop()
        val companion = HomeScreenMonitor::class.java.getDeclaredField("foregroundListener").apply { isAccessible = true }
        assertEquals(null, companion.get(null))
    }

    @Test fun unrelatedAccessibilityServiceDoesNotClaimForegroundAccess() {
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.AppOpsManager::class.java))
            .setMode(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
                context.packageName, android.app.AppOpsManager.MODE_IGNORED)
        android.provider.Settings.Secure.putString(context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${context.packageName}/UnrelatedService")
        assertFalse(HomeScreenMonitor.hasAccess(context))
    }
}
