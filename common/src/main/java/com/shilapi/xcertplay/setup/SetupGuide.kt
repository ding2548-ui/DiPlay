package com.shilapi.xcertplay.setup

import android.content.Context

/** Which features the setup guide offers for each DiLink generation, and how well each is proven. */
object SetupGuide {
    const val STEP_CAR = 0
    const val STEP_CONNECTION = 1
    const val STEP_IPHONE = 2
    const val STEP_FEATURES = 3
    const val STEP_DONE = 4
    const val STEP_COUNT = 5

    enum class Feature { AUTO_CONNECT, LOCATION, CLUSTER_MAP, BYD_NAVIGATION, CALLS_ON_DASHBOARD, CALL_KEYS, DILINK4_ADB_CLUSTER }

    /** TESTED: confirmed on at least one car of this generation. HIDDEN: not offered in the guide. */
    enum class Status { TESTED, EXPERIMENTAL, HIDDEN }

    data class Entry(val feature: Feature, val status: Status, val needsAdb: Boolean)

    /** Claims follow docs/COMPATIBILITY.md: community reports, not a certified support list. */
    fun features(generation: DiLinkGeneration): List<Entry> {
        fun entry(feature: Feature, status: Status, needsAdb: Boolean = false) = Entry(feature, status, needsAdb)
        val dilink3 = generation == DiLinkGeneration.DILINK_3
        val dilink4 = generation == DiLinkGeneration.DILINK_4
        return listOf(
            entry(Feature.AUTO_CONNECT, Status.TESTED),
            entry(Feature.LOCATION, Status.TESTED),
            entry(Feature.CLUSTER_MAP, if (dilink3) Status.TESTED else Status.EXPERIMENTAL),
            // DiLink 4 needs its own ADB cluster route; on DiLink 3 that route blocks the dashboard map.
            entry(Feature.DILINK4_ADB_CLUSTER, if (dilink4) Status.EXPERIMENTAL else Status.HIDDEN, needsAdb = true),
            entry(Feature.BYD_NAVIGATION, if (dilink3 || generation == DiLinkGeneration.DILINK_5) Status.TESTED else Status.EXPERIMENTAL),
            entry(Feature.CALLS_ON_DASHBOARD, if (dilink3) Status.EXPERIMENTAL else Status.HIDDEN, needsAdb = true),
            entry(Feature.CALL_KEYS, if (dilink3) Status.EXPERIMENTAL else Status.HIDDEN, needsAdb = true),
        ).filter { it.status != Status.HIDDEN }
    }

    /** A DiLink 3 car with the DiLink 4 route on never sends the DiLink 3 dashboard commands. */
    fun hasConflictingClusterRoute(generation: DiLinkGeneration, adbClusterEnabled: Boolean): Boolean =
        generation == DiLinkGeneration.DILINK_3 && adbClusterEnabled

    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)

    fun seen(context: Context): Boolean = prefs(context).getBoolean("setup_guide_seen", false)

    fun markSeen(context: Context) {
        prefs(context).edit().putBoolean("setup_guide_seen", true).apply()
    }

    /** Only a first launch opens the guide by itself; anyone with a saved iPhone has already set up. */
    fun shouldOpenOnLaunch(seen: Boolean, phoneChosen: Boolean): Boolean = !seen && !phoneChosen
}
