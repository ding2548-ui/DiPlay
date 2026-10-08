package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional, needs ADB over network: the iPhone draws and streams the cluster map for the whole
 * session, but the cluster shows it only in Small and Full screen navi. DiPlay reads the mode the
 * driver picked on the wheel every second and asks the iPhone to stop drawing the map while the
 * cluster hides it (Off, Turn on by navi, where it shows arrows only), and to draw it again when the
 * driver picks Small or Full. Without ADB access, or when the mode cannot be read, the map streams
 * as before.
 */
internal object BydClusterMapPause {
    private const val TAG = "DiPlay-BYD-ClusterMap"
    private const val READ_MILLIS = 1_000L

    private val tickerStarted = AtomicBoolean(false)
    private val shell = BydAdbShell(TAG)
    @Volatile private var context: Context? = null

    /** Whether DiPlay's map window is on the cluster. */
    @Volatile var clusterMapShown = false

    /** The running CarPlay session, told every second whether the iPhone should draw the cluster map. */
    @Volatile var streamControl: ((Boolean) -> Unit)? = null

    /** Reads the mode over adb. Blocking, and only called on the ticker thread; tests replace it. */
    @Volatile internal var readMode: (Context) -> BydClusterNaviMode? = { app ->
        BydClusterNaviMode.parseRead(shell.run(app, BydClusterNaviMode.READ_COMMAND))
    }

    /** Latest wheel-menu navi mode from ADB, or null when it cannot be read. */
    @Volatile var lastNaviMode: BydClusterNaviMode? = null
        private set

    /** Called on the ticker thread whenever the ADB navi mode changes, including to unknown. */
    @Volatile var onNaviMode: ((BydClusterNaviMode?) -> Unit)? = null

    /** Never waits for the ticker: an adb read in flight does not hold up opening or reconnecting. */
    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        if (tickerStarted.compareAndSet(false, true)) {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "diplay-cluster-map").apply { isDaemon = true }
            }.scheduleAtFixedRate(::tick, READ_MILLIS, READ_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun tick() {
        val app = context ?: return
        val control = streamControl
        val pauseMap = control != null && clusterMapShown && BydOutputSettings.clusterStreamPause(app)
        val followNavi = onNaviMode != null
        if (!pauseMap && !followNavi) {
            control?.invoke(true)
            shell.close()
            lastNaviMode = null
            return
        }
        val mode = readMode(app)
        if (mode != lastNaviMode) {
            lastNaviMode = mode
            Log.i(TAG, "cluster mode ${mode?.label ?: "unknown"}")
            onNaviMode?.invoke(mode)
        }
        // Following the layout must not keep a previous pause latched after it is disabled.
        // An unknown mode keeps the map streaming, as without ADB.
        control?.invoke(!pauseMap || mode?.showsMap != false)
    }
}
