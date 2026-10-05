package com.shilapi.xcertplay.hud

/**
 * How DiPlay keeps the car's own map off the instrument-cluster surface while it mirrors there.
 *
 * On DiLink 4.0 the car draws its cluster map with a single activity
 * ([BydOemClusterNavi.STOCK_MAP_CLUSTER_ACTIVITY]), so disabling just that activity stops the
 * projection while the rest of the app — and, on some firmware, the cluster's own navigation mode —
 * keeps running. Disabling the whole package is blunter and takes the app's other services with it,
 * which is why the driver picks here instead of the app deciding. Needs ADB over network.
 */
enum class BydOemClusterHold {
    /** Share the surface with the car's map; do nothing. */
    OFF,

    /** Disable only the car map's cluster projection, leaving the rest of the app running. */
    COMPONENT,

    /** Disable the whole car map package while DiPlay mirrors the cluster. */
    PACKAGE;

    companion object {
        /** The saved preference, or null when it is absent or no longer a known value. */
        fun fromName(name: String?): BydOemClusterHold? = entries.firstOrNull { it.name == name }
    }
}
