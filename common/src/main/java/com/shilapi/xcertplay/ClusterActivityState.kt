package com.shilapi.xcertplay

/** Only these stock cluster activities affect our overlay; head-unit apps are irrelevant. */
internal class ClusterActivityState {
    data class Snapshot(val theme: DiLink51ClusterLayout.Theme?, val mapVisible: Boolean)
    private data class Activity(val name: String, val instance: Int)
    private val visible = mutableMapOf<Activity, Long>()

    fun event(pkg: String?, name: String?, instance: Int, type: Int, time: Long) {
        if (type == 26 || type == 27) { // UsageEvents DEVICE_SHUTDOWN / DEVICE_STARTUP
            visible.clear()
            return
        }
        if (!accepted(pkg, name)) return
        val key = Activity(name!!, instance)
        when (type) {
            1 -> visible[key] = time // ACTIVITY_RESUMED
            23 -> visible.remove(key) // ACTIVITY_STOPPED; paused activities can still be visible.
        }
    }

    fun snapshot(): Snapshot {
        val theme = visible.entries.filter { themeOf(it.key.name) != null }
            .maxByOrNull { it.value }?.key?.name?.let(::themeOf)
        val miniMap = visible.keys.any { it.name == MINI_MAP }
        return Snapshot(theme, theme != null && (theme == DiLink51ClusterLayout.Theme.MAP || miniMap))
    }

    private fun themeOf(name: String) = when (name) {
        FULL_MAP -> DiLink51ClusterLayout.Theme.MAP
        SCENARIO -> DiLink51ClusterLayout.Theme.SCENARIO
        SIMPLE -> DiLink51ClusterLayout.Theme.SIMPLE
        else -> null
    }

    companion object {
        const val FULL_MAP = "com.byd.automap.meter.MeterActivity"
        const val MINI_MAP = "com.byd.automap.meter.MeterSmallScreenActivity"
        const val SCENARIO = "com.byd.sr.cluster.ClusterActivity"
        const val SIMPLE = "com.byd.cluster.SimpleClusterDynastyActivity"
        fun accepted(pkg: String?, name: String?): Boolean = when (pkg) {
            "com.byd.launchermap" -> name == FULL_MAP || name == MINI_MAP
            "com.byd.sr" -> name == SCENARIO
            "com.byd.cluster" -> name == SIMPLE
            else -> false
        }
    }
}
