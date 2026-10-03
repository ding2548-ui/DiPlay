// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired after the system replaced DiPlay with a newer build. Removes the
 * downloaded update APK and reopens DiPlay, but only when the replacement came
 * from the in-app updater (a downloaded APK exists) so manual reinstalls via
 * adb stay undisturbed.
 */
class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val updated = AppUpdater.pendingApk(context) != null
        AppUpdater.cleanup(context)
        if (updated) {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
