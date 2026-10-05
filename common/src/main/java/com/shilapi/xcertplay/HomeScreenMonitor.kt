package com.shilapi.xcertplay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Whether a home screen is in front (BYD's normal home, map home or MyCar, or whichever
 * launcher is the default home, such as a third-party car launcher), from the newest resumed
 * activity in the owner's Usage Access events or Accessibility events.
 * Overlays and panels are not activities, so they leave the answer as it is.
 */
internal class HomeScreenMonitor(context: Context, private val onChange: (Boolean) -> Unit) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var executor: ScheduledExecutorService? = null
    // Poller thread only.
    private var since = 0L
    private var newestTime = 0L
    private var newestPackage: String? = null
    @Volatile private var reported: Boolean? = null
    @Volatile private var homePackages = HOME_PACKAGES + KNOWN_CAR_LAUNCHERS
    private val listener: (String) -> Unit = ::handleForegroundPackage
    @Volatile private var active = false

    val running: Boolean get() = active || executor != null

    /** Main thread. */
    fun start() {
        if (running) return
        active = true
        reported = null
        homePackages = queryHomePackages(context)

        // Register for external foreground updates (e.g. from AccessibilityService)
        foregroundListener = listener

        // UsageStatsManager poller fallback
        if (DiLink51ClusterMonitor.hasAccess(context)) {
            since = System.currentTimeMillis() - FIRST_LOOK_BACK_MILLIS
            newestTime = 0L
            newestPackage = null
            executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-home-monitor").apply { isDaemon = true } }
                .also { it.scheduleWithFixedDelay(::poll, 0, POLL_MILLIS, TimeUnit.MILLISECONDS) }
        }
    }

    /** Main thread. */
    fun stop() {
        active = false
        if (foregroundListener === listener) {
            foregroundListener = null
        }
        executor?.shutdownNow()
        executor = null
        main.removeCallbacksAndMessages(null)
    }

    private fun handleForegroundPackage(pkg: String) {
        if (!running) return
        val visible = isHomePackage(pkg)
        if (visible != reported) {
            reported = visible
            main.post { if (running) onChange(visible) }
        }
    }

    private fun isHomePackage(pkg: String): Boolean = pkg in homePackages

    private fun poll() {
        val now = System.currentTimeMillis()
        val events = runCatching { context.getSystemService(UsageStatsManager::class.java).queryEvents(since, now) }
            .getOrNull() ?: return
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // MOVE_TO_FOREGROUND is ACTIVITY_RESUMED (API 29) under its older name.
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.timeStamp >= newestTime) {
                val pkg = event.packageName
                newestTime = event.timeStamp
                newestPackage = pkg
            }
        }
        // Overlap, because events can arrive a little late.
        since = (now - OVERLAP_MILLIS).coerceAtLeast(since)
        val currentPkg = newestPackage ?: ""
        handleForegroundPackage(currentPkg)
    }

    companion object {
        private const val POLL_MILLIS = 500L
        private const val OVERLAP_MILLIS = 2_000L
        private const val FIRST_LOOK_BACK_MILLIS = 10 * 60_000L

        @Volatile private var foregroundListener: ((String) -> Unit)? = null

        /** Notifies of a foreground package change from an accessibility or system service. */
        fun notifyForegroundPackage(pkg: String) {
            foregroundListener?.invoke(pkg)
        }

        // BYD's home list (Launcher3 HomeHelper): MyCar, the normal home, and the map home.
        val HOME_PACKAGES = setOf("com.android.launcher3", "com.byd.launchermap", "com.byd.naviauto", "com.byd.mycar")

        // Known third-party car launchers
        val KNOWN_CAR_LAUNCHERS = setOf(
            "com.smg.dydesktop",          // 迪友桌面 (常见主流包名)
            "com.smg.dydesktop.pro",      // 迪友桌面 Pro
            "com.dy.launcher",            // 迪友桌面 (部分渠道)
            "com.king.dyzm",              // 迪友桌面
            "com.king.diyou",
            "com.byd.diyou",
            "com.dudu.android.launcher",  // 嘟嘟桌面
            "com.dudu.android.launcher.mini",
            "com.tencent.autolauncher",   // 腾讯车联
            "com.mx.launcher",            // 喵驾桌面
        )

        // Accessibility alone is not a foreground source: this PR has no service dispatching events.
        fun hasAccess(context: Context): Boolean = DiLink51ClusterMonitor.hasAccess(context)

        /** Query all launcher packages declared on the system. */
        fun queryHomePackages(context: Context): Set<String> {
            val set = (HOME_PACKAGES + KNOWN_CAR_LAUNCHERS).toMutableSet()
            defaultHome(context)?.let { set.add(it) }
            runCatching {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val list = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                for (info in list) {
                    val pkg = info.activityInfo?.packageName
                    if (!pkg.isNullOrEmpty() && pkg != "android" && pkg != context.packageName) {
                        set.add(pkg)
                    }
                }
            }
            return set
        }

        /** The launcher Android uses as home now, unless that is the chooser or DiPlay itself. */
        fun defaultHome(context: Context): String? = runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()?.takeIf { it != "android" && it != context.packageName }
    }
}
