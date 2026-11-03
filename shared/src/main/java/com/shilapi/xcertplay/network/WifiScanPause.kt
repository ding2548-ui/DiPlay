package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.hud.BydAdbShell
import com.shilapi.xcertplay.hud.BydParcel
import java.lang.reflect.Field
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Pauses the head unit's automatic Wi-Fi network search while wireless CarPlay runs.
 *
 * BYD DiLink 3 (Android 10) scans every Wi-Fi band every 10 s whenever the car's own Wi-Fi client is
 * not connected to a network. Each scan takes the single radio off the CarPlay channel for 3-6 s, so
 * the stream stutters unless the car happens to be joined to a hotspot. Through the approved local
 * adb shell, `IWifiManager.enableWifiConnectivityManager(false)` stops those scans (shell holds
 * CONNECTIVITY_INTERNAL). One process-wide worker owns controller leases and restores scans only
 * after the last lease ends. A durable marker precedes every disable; failed restores retry and
 * recover when the app next opens. Nothing happens without adb approval. Same LAN is excluded.
 *
 * The binder transaction number differs between firmware builds, so it is read from the framework
 * itself instead of being hard-coded; when it cannot be read, scans are left alone.
 */
internal class WifiScanPause(
    context: Context,
    private val log: (String) -> Unit,
    private val control: WifiScanPauseControl = ProcessWifiScanPause,
) {
    private val app = context.applicationContext
    private val owner = Any()
    @Volatile private var closed = false

    /** Asynchronously stops the periodic scans; repeated calls are harmless. */
    @Synchronized
    fun pause() {
        if (closed) return
        control.acquire(app, owner, { !closed }, log)
    }

    /** Ends this lease; shared recovery remains alive after this controller has closed. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        control.release(app, owner, log)
    }

    internal companion object {
        internal const val TAG = "DiPlayWifiScan"

        fun eligible(backend: WirelessHotspotBackend, sdk: Int = Build.VERSION.SDK_INT): Boolean =
            sdk == Build.VERSION_CODES.Q && backend != WirelessHotspotBackend.EXISTING_WIFI

        fun restoreIfNeeded(context: Context) = ProcessWifiScanPause.recover(context.applicationContext)

        fun command(code: Int, enabled: Boolean): String {
            require(code > 0)
            return "service call wifi $code i32 ${if (enabled) 1 else 0}"
        }

        /** `void` replies carry only a zero exception word. */
        fun accepted(output: String?): Boolean = BydParcel.words(output).firstOrNull() == 0L

        /**
         * Android 10 blocks direct reflection on this hidden constant but still allows the lookup when
         * it goes through Class.getDeclaredField itself; later releases close that path, so only Q is
         * attempted, which is what DiLink 3 runs.
         */
        fun enableConnectivityManagerTransaction(): Int? {
            if (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q) return null
            return runCatching {
                val stub = Class.forName("android.net.wifi.IWifiManager\$Stub")
                val lookup = Class::class.java.getDeclaredMethod("getDeclaredField", String::class.java)
                val field = lookup.invoke(stub, "TRANSACTION_enableWifiConnectivityManager") as Field
                field.isAccessible = true
                field.getInt(null)
            }.getOrNull()?.takeIf { it > 0 }
        }
    }
}

internal interface WifiScanPauseControl {
    fun acquire(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit)
    fun release(app: Context, owner: Any, log: (String) -> Unit)
}

/** The single writer is shared across full controller rebuilds, including delayed restore retries. */
private object ProcessWifiScanPause : WifiScanPauseControl {
    private const val PREFS = "diplay_wifi_scan_pause"
    private const val JOURNAL = "restore_pending"
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        Thread(it, WifiScanPause.TAG).apply { isDaemon = true }
    }
    private val adb = BydAdbShell(WifiScanPause.TAG)
    private var session: WifiScanPauseSession? = null
    private var retry: ScheduledFuture<*>? = null

    override fun acquire(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) {
        enqueue(app) { state -> log("Wi-Fi connectivity scans paused=${state.acquire(owner, current)}") }
    }

    override fun release(app: Context, owner: Any, log: (String) -> Unit) {
        enqueue(app) { state -> log("Wi-Fi scan pause lease released; cleanup ok=${state.release(owner)}") }
    }

    fun recover(app: Context) {
        enqueue(app) { state ->
            if (state.recoveryPending()) Log.i(WifiScanPause.TAG, "Wi-Fi scan recovery restored=${state.recover()}")
        }
    }

    private fun enqueue(app: Context, action: (WifiScanPauseSession) -> Unit) {
        worker.execute {
            val state = state(app)
            runCatching { action(state) }.onFailure { Log.w(WifiScanPause.TAG, "Wi-Fi scan recovery will retry", it) }
            if (state.recoveryPending()) {
                if (retry == null) retry = worker.schedule({
                    retry = null
                    recover(app)
                }, 30, TimeUnit.SECONDS)
            } else {
                retry?.cancel(false)
                retry = null
                adb.close()
            }
        }
    }

    private fun state(app: Context): WifiScanPauseSession {
        session?.let { return it }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val journal = WifiScanPauseJournal(prefs.contains(JOURNAL)) { pending ->
            val edit = prefs.edit()
            if (pending) edit.putBoolean(JOURNAL, true) else edit.remove(JOURNAL)
            edit.commit()
        }
        return WifiScanPauseSession(
            transactionCode = WifiScanPause::enableConnectivityManagerTransaction,
            setEnabled = { code, enabled -> WifiScanPause.accepted(adb.run(app, WifiScanPause.command(code, enabled))) },
            loadJournal = { journal.pending },
            saveJournal = journal::save,
        ).also { session = it }
    }
}
