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
            PackageInstaller.STATUS_SUCCESS -> Unit
            // The process is killed during the replace; UpdateInstalledReceiver reopens DiPlay.
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "错误码未知"
                Toast.makeText(context, "更新安装失败：$message", Toast.LENGTH_LONG).show()
            }
        }
    }
}
