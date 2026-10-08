// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration

/** Independent override for the expanded Settings navigation. */
object SettingsLayoutPreferences {
    fun isActive(context: Context): Boolean = forceFull(context) &&
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun forceFull(context: Context): Boolean =
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
            .getBoolean("settings_force_full_layout", false)

    fun saveForceFull(context: Context, enabled: Boolean) {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putBoolean("settings_force_full_layout", enabled).apply()
    }
}
