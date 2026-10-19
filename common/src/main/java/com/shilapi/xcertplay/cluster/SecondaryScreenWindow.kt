package com.shilapi.xcertplay.cluster

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Surface
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager

/**
 * `p/jy2`: the window fallback.
 *
 * When the platform cannot launch onto the second display (API 25 has no
 * `setLaunchDisplayId`), or the system refuses it, the same renderer is shown as a dialog on the
 * main screen. It is a degraded but complete path — the geometry, the crop and the calibration all
 * behave identically, only the destination differs.
 */
internal class SecondaryScreenWindow(
    host: Activity,
    profile: ScreenProfile,
    grid: Boolean,
) : Dialog(host) {
    private val renderer: SecondaryScreenRenderer
    private var current = profile

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setCancelable(false)
        renderer = SecondaryScreenRenderer(
            host,
            onSurface = { surface -> SecondaryScreenController.surfaceChanged(this, surface) },
            onProfileEdited = { edited -> current = edited },
        )
        setContentView(renderer, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        renderer.applyProfile(profile)
        renderer.setCalibrating(grid)
    }

    fun applyProfile(profile: ScreenProfile) {
        current = profile
        renderer.applyProfile(profile)
    }

    fun setCalibrating(calibrating: Boolean) {
        renderer.setCalibrating(calibrating)
    }

    fun currentSurface(): Surface? = renderer.currentSurface()

    override fun show() {
        super.show()
        SecondaryScreenController.attachWindow(this)
        SecondaryScreenController.onDiagnostic?.invoke("Secondary screen: window fallback shown")
    }

    override fun dismiss() {
        SecondaryScreenController.detachWindow(this)
        runCatching { super.dismiss() }
    }
}
