package com.shilapi.xcertplay.cluster

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/**
 * `p/qy2`: the secondary-screen controller.
 *
 * It owns one live output at a time: which display, which profile, and the surface the renderer
 * hands back to the CarPlay session. The launch itself is `ActivityOptions.setLaunchDisplayId`,
 * which is API 26; this app still ships to API 25 head units, so on anything older the controller
 * degrades to the window mode instead of calling a method that does not exist there.
 *
 * The original kept a `Request` registry keyed by an incrementing id, checked twice: before the
 * launch (the target display must still be valid) and in `onCreate` (the page must have landed on
 * the display it was aimed at). A launch that lands on the wrong display is the one failure mode
 * that silently shows the wrong screen, so both checks are kept.
 */
internal object SecondaryScreenController {
    /** `p/py2`: one launch credential. */
    class Request(
        val id: Long,
        val displayId: Int,
        var profile: ScreenProfile,
        val grid: Boolean,
        val mode: OutputMode,
    ) {
        override fun toString(): String =
            "Request(id=$id, displayId=$displayId, profile=$profile, grid=$grid, mode=$mode)"
    }

    private val nextId = AtomicLong(1)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var current: Request? = null
    @Volatile private var enabled = false

    private var hostRef = WeakReference<Activity>(null)
    private var activityRef = WeakReference<SecondaryScreenActivity>(null)
    private var windowRef = WeakReference<SecondaryScreenWindow>(null)
    private var displayListener: DisplayManager.DisplayListener? = null
    private var onSurface: ((Surface?) -> Unit)? = null
    private var surfaceOwner: Any? = null

    /** Diagnostic lines for the exported report; bound by the host. */
    @Volatile var onDiagnostic: ((String) -> Unit)? = null

    /**
     * A pushed profile may have changed the active source, and each source routes to a different
     * decoder (110 mirror / 111 alt screen / none). The host re-attaches the surface on this.
     */
    @Volatile var onProfilePushed: (() -> Unit)? = null

    val request: Request? get() = current
    val running: Boolean get() = current != null

    private fun report(message: String) {
        onDiagnostic?.invoke(message)
    }

    /** Called by the host once per session; [callback] receives the renderer's surface. */
    fun bind(host: Activity, callback: (Surface?) -> Unit) {
        hostRef = WeakReference(host)
        onSurface = callback
        enabled = true
        callback(surfaceOf())
    }

    private fun surfaceOf(): Surface? = activityRef.get()?.currentSurface() ?: windowRef.get()?.currentSurface()

    /**
     * `h()`: launch (or re-push) onto the secondary display. Returns false with a reason in the
     * report when the unit has no secondary display.
     */
    fun ensure(host: Activity): Boolean {
        hostRef = WeakReference(host)
        val display = ScreenProfileStore.preferredDisplay(host)
        if (display == null) {
            report("Secondary screen: no display besides the default one; nothing to launch")
            return false
        }
        val profile = ScreenProfileStore.load(host, display)
        val mode = ScreenProfileStore.outputMode(host)
        return launch(host, display, profile, grid = false, mode = mode)
    }

    /** `l()`: push a changed profile to the live output without relaunching. */
    fun push(profile: ScreenProfile, force: Boolean = false) {
        val request = current ?: return
        if (!force && request.profile == profile) return
        val sourceChanged = request.profile.source != profile.source
        request.profile = profile
        activityRef.get()?.applyProfile(profile)
        windowRef.get()?.applyProfile(profile)
        if (sourceChanged) onProfilePushed?.invoke()
    }

    fun setCalibrating(calibrating: Boolean) {
        activityRef.get()?.setCalibrating(calibrating)
        windowRef.get()?.setCalibrating(calibrating)
    }

    /** Re-reads the profile from storage and pushes it, after the settings editor saved. */
    fun refresh(host: Activity) {
        val request = current ?: return
        val display = ScreenProfileStore.secondaryDisplays(host)
            .firstOrNull { it.displayId == request.displayId } ?: return
        push(ScreenProfileStore.load(host, display), force = true)
    }

    /** The calibration bar's 保存: persist the edited profile for the live display and push it. */
    fun saveProfile(profile: ScreenProfile) {
        val request = current ?: return
        val host = hostRef.get() ?: return
        val display = ScreenProfileStore.secondaryDisplays(host)
            .firstOrNull { it.displayId == request.displayId } ?: return
        ScreenProfileStore.save(host, display, profile)
        push(profile, force = true)
    }

    /** The calibration bar's 取消: discard the edits and re-apply the stored profile. */
    fun revertProfile() {
        val request = current ?: return
        val host = hostRef.get() ?: return
        val display = ScreenProfileStore.secondaryDisplays(host)
            .firstOrNull { it.displayId == request.displayId } ?: return
        push(ScreenProfileStore.load(host, display), force = true)
    }

    private fun launch(
        host: Activity,
        display: Display,
        profile: ScreenProfile,
        grid: Boolean,
        mode: OutputMode,
    ): Boolean {
        stopInternal(finishActivity = true)
        val request = Request(nextId.getAndIncrement(), display.displayId, profile, grid, mode)
        current = request
        report("Secondary screen: request $request -> ${ScreenProfileStore.describe(host, display)}")
        registerDisplayListener(host)
        // API 26 introduced setLaunchDisplayId; before that the only way onto a second panel is a
        // window, so the original's ACTIVITY/WINDOW split becomes a hard platform split here.
        val activityMode = mode == OutputMode.ACTIVITY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        if (!activityMode) {
            if (mode == OutputMode.ACTIVITY) {
                report("Secondary screen: API ${Build.VERSION.SDK_INT} has no setLaunchDisplayId; " +
                    "using the window fallback")
            }
            return showWindow(host, request)
        }
        return launchActivity(host, request)
    }

    private fun launchActivity(host: Activity, request: Request): Boolean {
        val intent = Intent(host, SecondaryScreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra(SecondaryScreenActivity.EXTRA_REQUEST_ID, request.id)
        val options = ActivityOptionsCompat.launchOnDisplay(request.displayId)
        return try {
            if (options != null) host.startActivity(intent, options)
            else host.startActivity(intent)
            report("Secondary screen: activity launched on display ${request.displayId}")
            true
        } catch (error: SecurityException) {
            report("Secondary screen: the system refused display ${request.displayId} " +
                "(${error.javaClass.simpleName}); try the window mode")
            current = null
            false
        } catch (error: Exception) {
            report("Secondary screen: launch failed ${error.javaClass.simpleName}: ${error.message}")
            current = null
            false
        }
    }

    private fun showWindow(host: Activity, request: Request): Boolean = try {
        val window = SecondaryScreenWindow(host, request.profile, request.grid)
        windowRef = WeakReference(window)
        window.show()
        true
    } catch (error: Exception) {
        report("Secondary screen: window fallback failed ${error.javaClass.simpleName}")
        current = null
        false
    }

    /** Called by the renderer when its surface is ready or gone. */
    fun surfaceChanged(owner: Any, surface: Surface?) {
        if (surface != null) surfaceOwner = owner
        else if (surfaceOwner === owner) surfaceOwner = null
        onSurface?.invoke(surface)
    }

    fun attachActivity(activity: SecondaryScreenActivity) {
        activityRef = WeakReference(activity)
        activity.applyProfile(current?.profile ?: ScreenProfile())
        activity.setCalibrating(current?.grid == true)
    }

    fun detachActivity(activity: SecondaryScreenActivity) {
        if (activityRef.get() !== activity) return
        activityRef.clear()
        if (windowRef.get() == null) {
            onSurface?.invoke(null)
            current = null
        }
    }

    fun attachWindow(window: SecondaryScreenWindow) {
        windowRef = WeakReference(window)
    }

    fun detachWindow(window: SecondaryScreenWindow) {
        if (windowRef.get() !== window) return
        windowRef.clear()
        if (activityRef.get() == null) {
            onSurface?.invoke(null)
            current = null
        }
    }

    /** The settings editor changed the target display: relaunch on it. */
    fun relaunchOn(host: Activity, display: Display, grid: Boolean = false): Boolean {
        val profile = ScreenProfileStore.load(host, display)
        ScreenProfileStore.rememberOutput(host, display)
        return launch(host, display, profile, grid, ScreenProfileStore.outputMode(host))
    }

    fun stop(host: Activity) {
        stopInternal(finishActivity = true)
    }

    private fun stopInternal(finishActivity: Boolean) {
        unregisterDisplayListener()
        if (finishActivity) {
            activityRef.get()?.finish()
            windowRef.get()?.dismiss()
        }
        activityRef.clear()
        windowRef.clear()
        surfaceOwner = null
        current = null
        onSurface?.invoke(null)
    }

    // --- display hot plug -------------------------------------------------------------------

    private fun registerDisplayListener(context: Context) {
        if (displayListener != null) return
        val manager = context.getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) {
                val request = current ?: return
                if (request.displayId != displayId) return
                report("Secondary screen: display $displayId removed; output cleared")
                main.post { stopInternal(finishActivity = true) }
            }

            override fun onDisplayChanged(displayId: Int) {
                val request = current ?: return
                if (request.displayId != displayId) return
                val host = hostRef.get() ?: return
                val display = ScreenProfileStore.secondaryDisplays(host)
                    .firstOrNull { it.displayId == displayId }
                if (display == null) {
                    report("Secondary screen: display $displayId is no longer valid")
                    main.post { stopInternal(finishActivity = true) }
                    return
                }
                // A resolution change keeps the normalized profile valid, but the page was laid out
                // for the old size; relaunching is what the original did.
                report("Secondary screen: display $displayId changed; relaunching with the same profile")
                main.post { launch(host, display, request.profile, request.grid, request.mode) }
            }
        }
        runCatching { manager.registerDisplayListener(listener, main) }
            .onSuccess { displayListener = listener }
            .onFailure { report("Secondary screen: display listener failed ${it.javaClass.simpleName}") }
    }

    private fun unregisterDisplayListener() {
        val listener = displayListener ?: return
        displayListener = null
        hostRef.get()?.getSystemService(DisplayManager::class.java)
            ?.let { runCatching { it.unregisterDisplayListener(listener) } }
    }
}

/** Thin guard so the API 26 call never even links on API 25. */
private object ActivityOptionsCompat {
    fun launchOnDisplay(displayId: Int): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching {
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        }.getOrNull()
    }
}
