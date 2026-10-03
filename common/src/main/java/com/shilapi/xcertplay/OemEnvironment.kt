// SPDX-License-Identifier: GPL-3.0-only
// OEM capability probing, ported from EasyPlay's "reflective integration + capability
// detection + graceful degradation" architecture. One APK adapts to whichever car it runs
// on: detected OEM buses are reported to the diagnostics, undetected ones degrade
// silently, and the user-learned wheel bindings remain the universal fallback.
package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

internal object OemEnvironment {
    private const val TAG = "DiPlay-Oem"

    @Volatile
    var onDiagnostic: ((String) -> Unit)? = null

    /** Known OEM integration anchors, checked through PackageManager only (no reflection crashes). */
    private val ANCHORS = listOf(
        "leapmotor" to "com.leapmotor.carcanserver",
        "leapmotor-launcher" to "com.leapmotor.launcherapp.three",
        "ecarx" to "com.ecarx.sdk.mediacenter",
        "byd-hud" to "com.byd.audiocar",
    )

    /**
     * Reports which car environment this install runs in. Called once per process from the
     * host activity; the result decides nothing by itself — backends degrade on their own —
     * but it makes "which input sources are expected to work" visible in the report.
     */
    fun probe(context: Context): List<String> {
        val manager = context.packageManager
        val present = ANCHORS.mapNotNull { (label, packageName) ->
            val found = runCatching {
                manager.getPackageInfo(packageName, 0) != null
            }.getOrDefault(false)
            if (found) label else null
        }
        val sources = buildList {
            if (present.any { it.startsWith("leapmotor") }) add("CAR(Leapmotor)")
            add("MEDIA_BUTTON")
            add("LEARNING")
        }
        report("oem probe: detected=[${present.joinToString(", ")}] wheel-sources=[${sources.joinToString(", ")}]")
        return present
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }
}
