package com.shilapi.xcertplay

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Capability-based detection for devices whose primary interaction model is a TV remote/D-pad.
 *
 * No manufacturer/model checks are used. Android TV feature flags and UI mode are preferred,
 * with a no-touchscreen fallback for other non-touch head-unit style devices.
 */
internal object AndroidTvInputMode {
    fun isTelevision(context: Context): Boolean {
        val packageManager = context.packageManager
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) return true

        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true

        return (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun shouldUseKnobAsPrimaryInput(context: Context): Boolean {
        if (isTelevision(context)) return true

        return context.resources.configuration.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH
    }
}
