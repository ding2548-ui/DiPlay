package com.shilapi.xcertplay

import android.content.Context

/**
 * Optional: CarPlay can move to two thirds of the main screen while DiPlay shows its own panel in the rest:
 * the right third of a landscape screen, a band at the bottom of a portrait one. The areas are declared
 * with the session's view areas ([CarPlayViewAreas]); the in-session menu shows and hides the panel. The
 * iPhone keeps streaming the whole frame and leaves the panel's part black; the panel is an Android view
 * over it, so CarPlay knows nothing about its contents.
 */
object SidePanelSettings {
    private const val PREFS = "diplay_side_panel"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
}
