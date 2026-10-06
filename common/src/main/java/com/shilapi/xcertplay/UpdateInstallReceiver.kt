// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/** Receives the PackageInstaller session result for an in-app update. */
class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The platform withheld silent installation: open the system confirm dialog.
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppUpdater.clearManualApk(context)
                // The process is killed during the replace; UpdateInstalledReceiver reopens DiPlay.
            }
            else -> {
                // Non-platform-signed ROMs reject silent sessions with an opaque failure
                // ("未知错误"). Fall back to the system installer UI over the same APK.
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "错误码未知"
                val apk = AppUpdater.pendingManualApk(context)
                if (apk != null) {
                    Toast.makeText(context, "静默安装失败（$message），已转手动安装", Toast.LENGTH_LONG).show()
                    runCatching { AppUpdater.installManually(context, apk) }
                        .onFailure {
                            Toast.makeText(context, "手动安装无法启动：${it.message}", Toast.LENGTH_LONG).show()
                        }
                } else {
                    Toast.makeText(context, "更新安装失败：$message", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
