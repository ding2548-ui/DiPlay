package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist

/**
 * Where CarPlay puts its dock (app shortcuts, clock and status). Automatic leaves it to the iPhone,
 * which picks the driver's side on most screens. A fixed choice declares one whole-screen view area per
 * edge, so switching between the driver's side and the bottom moves the dock without reconnecting.
 */
enum class CarPlayDock(val edge: Int?) {
    AUTOMATIC(null),
    DRIVER_SIDE(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE),
    BOTTOM(AirPlayInfoPlist.DOCK_EDGE_BOTTOM);

    companion object {
        private const val PREFS = "diplay_carplay_dock"
        private const val KEY_DOCK = "dock"

        fun load(context: Context): CarPlayDock =
            entries.firstOrNull { it.name == context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DOCK, null) }
                ?: AUTOMATIC

        fun save(context: Context, dock: CarPlayDock) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DOCK, dock.name).apply()

        /** A change between two fixed edges can move the dock live; to or from automatic needs a reconnect. */
        fun movesLive(from: CarPlayDock, to: CarPlayDock): Boolean = from.edge != null && to.edge != null
    }
}
