package com.shilapi.xcertplay

import android.content.Context

/** Only the latest received boot event and launch result; no Intent extras or device identifiers. */
internal object StartupDiagnosticSnapshot {
    private const val PREFS = "diplay_startup_diagnostics"

    fun received(context: Context, launchEnabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("receivedAt", System.currentTimeMillis())
                .putBoolean("launchEnabled", launchEnabled)
                .putString("launchResult", if (launchEnabled) "pending" else "disabled")
                .remove("failureClass").apply()
        }
    }

    fun launchResult(context: Context, failure: RuntimeException? = null) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("launchResult", if (failure == null) "startActivity-returned" else "failed")
                .putString("failureClass", failure?.javaClass?.simpleName?.take(80)
                    ?.replace(Regex("[^A-Za-z0-9_$]"), "?")).apply()
        }
    }

    fun report(context: Context): String = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val receivedAt = prefs.getLong("receivedAt", 0)
        if (receivedAt == 0L) {
            "Boot diagnostics received=false; no boot delivery has been recorded by this version."
        } else {
            "Boot diagnostics received=true receivedAtEpochMs=$receivedAt " +
                "launchEnabledAtBoot=${prefs.getBoolean("launchEnabled", false)} " +
                "launchResult=${prefs.getString("launchResult", "unknown")} " +
                "failureClass=${prefs.getString("failureClass", "none") ?: "none"}; " +
                "startActivity-returned does not prove the OEM displayed the app."
        }
    }.getOrDefault("Boot diagnostics unavailable.")
}
