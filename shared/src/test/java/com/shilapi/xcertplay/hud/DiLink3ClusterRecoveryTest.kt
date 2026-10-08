package com.shilapi.xcertplay.hud

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [DiLink3ClusterRecoveryTest.Shell::class])
class DiLink3ClusterRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val output get() = BydDiLink3ClusterOutput
    private val worker get() = ReflectionHelpers.getField<ScheduledExecutorService>(output, "worker")
    private val prefs get() = app.getSharedPreferences("diplay_dilink3_cluster", 0)
    private val stock get() = BydDiLink3ClusterMode.Mode.STOCK.command
    private fun drain() { worker.submit {}.get(5, TimeUnit.SECONDS) }

    @Before fun setup() {
        drain()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        Shell.commands.clear()
        Shell.response = { "Result: Parcel(00000000 00000000 '........')" }
        ReflectionHelpers.setField(output, "session", null)
        ReflectionHelpers.setField(output, "desiredMode", null)
        ReflectionHelpers.setField(output, "context", null)
    }

    @After fun cleanup() {
        drain()
        ReflectionHelpers.setField(output, "session", null)
        ReflectionHelpers.setField(output, "desiredMode", null)
        ReflectionHelpers.setField(output, "context", null)
    }

    @Test fun appOpeningRecoversAnInterruptedClusterEvenWhenNavigationIsDisabled() {
        prefs.edit().putBoolean("restore_stock_mode", true).commit()
        BydOutputSettings.setEnabled(app, false)
        Shell.response = { null }
        BydNavigationOutputs.onAppOpened(app)
        drain()
        assertEquals(listOf(stock), Shell.commands.toList())
        assertTrue(prefs.getBoolean("restore_stock_mode", false))
        Shell.response = { "Result: Parcel(00000000 00000000 '........')" }
        output.restoreIfNeeded(app)
        drain()
        assertEquals(listOf(stock, stock), Shell.commands.toList())
        assertFalse(prefs.contains("restore_stock_mode"))
    }

    @Test fun failedStockRestoreKeepsItsDurableJournalUntilTheWorkerRetries() {
        output.setDesired(app, mapShown = true, guidanceActive = false)
        drain()
        assertTrue(prefs.getBoolean("restore_stock_mode", false))
        Shell.response = { null }
        output.setDesired(app, mapShown = false, guidanceActive = false)
        drain()
        assertTrue(prefs.getBoolean("restore_stock_mode", false))
        Shell.response = { "Result: Parcel(00000000 00000000 '........')" }
        // Exercise the same serial-worker operation used by the periodic retry.
        worker.submit { ReflectionHelpers.callInstanceMethod<Void>(output, "applyLatest") }
            .get(5, TimeUnit.SECONDS)
        assertEquals(listOf(BydDiLink3ClusterMode.Mode.PROJECTION.entryCommand,
            BydDiLink3ClusterMode.Mode.PROJECTION.command, stock, stock), Shell.commands.toList())
        assertFalse(prefs.contains("restore_stock_mode"))
    }

    @Test fun adbRoutePreservesNativeCastingDuringPreparationMapAndCleanup() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putBoolean("adb_cluster_activity_enabled", true).commit()
        output.prepareDisplay(app) { false }
        output.setDesired(app, mapShown = true, guidanceActive = false)
        drain()
        output.setDesired(app, mapShown = false, guidanceActive = true)
        drain()
        output.setDesired(app, mapShown = false, guidanceActive = false)
        drain()
        assertTrue(Shell.commands.isEmpty())
        assertFalse(prefs.contains("restore_stock_mode"))
    }

    @Test fun adbRouteDefersOldRecoveryWithoutDiscardingTheJournal() {
        prefs.edit().putBoolean("restore_stock_mode", true).commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putBoolean("adb_cluster_activity_enabled", true).commit()
        output.restoreIfNeeded(app)
        drain()
        assertTrue(Shell.commands.isEmpty())
        assertTrue(prefs.getBoolean("restore_stock_mode", false))
        app.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putBoolean("adb_cluster_activity_enabled", false).commit()
        output.restoreIfNeeded(app)
        drain()
        assertEquals(listOf(stock), Shell.commands.toList())
        assertFalse(prefs.contains("restore_stock_mode"))
    }

    @Test fun adbRoutePreservesNativeCastingDuringPreparationMapAndCleanup() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putBoolean("adb_cluster_activity_enabled", true).commit()
        output.prepareDisplay(app) { false }
        output.setDesired(app, mapShown = true, guidanceActive = false)
        drain()
        output.setDesired(app, mapShown = false, guidanceActive = true)
        drain()
        output.setDesired(app, mapShown = false, guidanceActive = false)
        drain()
        assertTrue(Shell.commands.isEmpty())
        assertFalse(prefs.contains("restore_stock_mode"))
    }

    @Test fun adbRouteDefersOldRecoveryWithoutDiscardingTheJournal() {
        prefs.edit().putBoolean("restore_stock_mode", true).commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putBoolean("adb_cluster_activity_enabled", true).commit()
        output.restoreIfNeeded(app)
        drain()
        assertTrue(Shell.commands.isEmpty())
        assertTrue(prefs.getBoolean("restore_stock_mode", false))
        app.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putBoolean("adb_cluster_activity_enabled", false).commit()
        output.restoreIfNeeded(app)
        drain()
        assertEquals(listOf(stock), Shell.commands.toList())
        assertFalse(prefs.contains("restore_stock_mode"))
    }

    @Implements(BydAdbShell::class, isInAndroidSdk = false)
    class Shell {
        @Implementation fun run(context: Context, command: String): String? {
            commands.add(command)
            return response(command)
        }
        companion object {
            val commands = CopyOnWriteArrayList<String>()
            var response: (String) -> String? = { null }
        }
    }
}
