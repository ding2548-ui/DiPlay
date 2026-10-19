package com.shilapi.xcertplay.cluster

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/**
 * `extension.SecondaryScreenActivity`: the page that actually lands on the second panel.
 *
 * Two checks matter, both from the original:
 *  - the request id must still be the live one (a stale launch must not draw over a newer one);
 *  - the window must really be on the display it was aimed at. A launch that silently lands on the
 *    default display is worse than no launch: it covers the main screen with the cluster content.
 */
class SecondaryScreenActivity : Activity() {
    private var renderer: SecondaryScreenRenderer? = null
    private var requestId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getLongExtra(EXTRA_REQUEST_ID, -1L)
        val request = SecondaryScreenController.request
        if (request == null || request.id != requestId) {
            SecondaryScreenController.onDiagnostic?.invoke(
                "Secondary screen: request $requestId is no longer live; page closed")
            finish()
            return
        }
        val actual = currentDisplayId()
        if (actual != request.displayId) {
            SecondaryScreenController.onDiagnostic?.invoke(
                "Secondary screen: page landed on display $actual, target was ${request.displayId}")
            Toast.makeText(this, "副屏页面启动到了 Display $actual，目标是 ${request.displayId}；" +
                "请改用兼容窗口模式。", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val view = SecondaryScreenRenderer(
            this,
            onSurface = { surface -> SecondaryScreenController.surfaceChanged(this, surface) },
            onSave = { profile -> persist(profile) },
            onCancel = { SecondaryScreenController.revertProfile() },
            onExit = { finish() },
        )
        renderer = view
        setContentView(view)
        SecondaryScreenController.attachActivity(this)
        SecondaryScreenController.onDiagnostic?.invoke(
            "Secondary screen: page created on display $actual")
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayId(): Int = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.displayId
        else windowManager.defaultDisplay.displayId
    }.getOrNull() ?: -1

    internal fun applyProfile(profile: ScreenProfile) {
        renderer?.applyProfile(profile)
    }

    internal fun setCalibrating(calibrating: Boolean) {
        renderer?.setCalibrating(calibrating)
    }

    internal fun currentSurface(): Surface? = renderer?.currentSurface()

    /** A calibration drag finished: save it for this display and tell the live session. */
    private fun persist(profile: ScreenProfile) {
        val display = runCatching {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }.getOrNull() ?: return
        ScreenProfileStore.save(this, display, profile)
        SecondaryScreenController.push(profile, force = true)
    }

    override fun onDestroy() {
        SecondaryScreenController.detachActivity(this)
        renderer = null
        SecondaryScreenController.onDiagnostic?.invoke("Secondary screen: page closed")
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
