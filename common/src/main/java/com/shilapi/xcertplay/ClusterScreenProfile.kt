package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * OgleaPlay-style profile for the centre dashboard-map card: which CarPlay stream feeds the card,
 * where each source's content sits inside the card, and how the frame maps into its region.
 * Regions are normalized fractions (0..1) of the card canvas, persisted as "x,y,w,h" percents.
 * PSA line: hard-coded Chinese labels, like the other PSA-only settings.
 */
internal object ClusterScreenProfile {
    /** 110 mirrors the CarPlay main screen; 111 is the iPhone's independent cluster stream;
     *  MUSIC shows no video, only the song card. */
    enum class Source(val label: String, val streamType: Int?) {
        MAIN("主屏视频", 110),
        NAVIGATION("独立导航", 111),
        MUSIC("音乐", null),
    }

    enum class ScaleMode(val label: String) {
        FIT("适应（完整放入，留黑边）"),
        CROP("裁满（放大铺满，溢出裁掉）"),
    }

    data class Region(val x: Float, val y: Float, val width: Float, val height: Float) {
        companion object {
            val FULL = Region(0f, 0f, 1f, 1f)

            /** Parses "x,y,w,h" percents (0..100); null when malformed or out of the canvas. */
            fun fromPercent(text: String): Region? {
                val parts = text.split(",").map { it.trim().toIntOrNull() }
                if (parts.size != 4) return null
                val values = parts.map { it ?: return null }
                if (values.any { it < 0 || it > 100 }) return null
                val region = Region(values[0] / 100f, values[1] / 100f, values[2] / 100f, values[3] / 100f)
                return if (region.width > 0f && region.height > 0f &&
                    region.x + region.width <= 1.001f && region.y + region.height <= 1.001f) region else null
            }

            fun toPercent(region: Region): String =
                "${(region.x * 100).toInt()},${(region.y * 100).toInt()}," +
                    "${(region.width * 100).toInt()},${(region.height * 100).toInt()}"

            /** Reference-space rect for the edge editor; [refW]/[refH] only define the aspect. */
            fun toRect(region: Region, refW: Int, refH: Int): com.shilapi.xcertplay.airplay.SafeAreaRect =
                com.shilapi.xcertplay.airplay.SafeAreaRect(
                    (region.x * refW).toInt(),
                    (region.y * refH).toInt(),
                    ((region.x + region.width) * refW).toInt(),
                    ((region.y + region.height) * refH).toInt(),
                )

            fun fromRect(rect: com.shilapi.xcertplay.airplay.SafeAreaRect, refW: Int, refH: Int): Region? {
                if (refW <= 0 || refH <= 0) return null
                val region = Region(
                    rect.left / refW.toFloat(),
                    rect.top / refH.toFloat(),
                    (rect.right - rect.left) / refW.toFloat(),
                    (rect.bottom - rect.top) / refH.toFloat(),
                )
                return if (region.width > 0f && region.height > 0f &&
                    region.x + region.width <= 1.001f && region.y + region.height <= 1.001f) region else null
            }
        }
    }

    private const val PREFS = "diplay_center_map"
    private const val KEY_SOURCE = "profile_source"
    private const val KEY_REGION_PREFIX = "profile_region_"
    private const val KEY_SCALE_PREFIX = "profile_scale_"

    /** Mirror key for the dashboard surface when the profile routes stream 110 into it. */
    const val DASHBOARD_KEY = "dashboard"

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addOnChangedListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeOnChangedListener(listener: () -> Unit) { listeners.remove(listener) }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** NAVIGATION by default: the card keeps the dashboard stream it had before this profile existed. */
    fun source(context: Context): Source {
        val name = prefs(context).getString(KEY_SOURCE, Source.NAVIGATION.name)
        return Source.entries.firstOrNull { it.name == name } ?: Source.NAVIGATION
    }

    fun setSource(context: Context, source: Source) {
        prefs(context).edit().putString(KEY_SOURCE, source.name).apply()
        notifyChanged()
    }

    fun region(context: Context, source: Source): Region {
        val text = prefs(context).getString(KEY_REGION_PREFIX + source.name, null) ?: return Region.FULL
        return Region.fromPercent(text) ?: Region.FULL
    }

    fun setRegion(context: Context, source: Source, region: Region) {
        prefs(context).edit().putString(KEY_REGION_PREFIX + source.name, Region.toPercent(region)).apply()
        notifyChanged()
    }

    fun scaleMode(context: Context, source: Source): ScaleMode {
        val name = prefs(context).getString(KEY_SCALE_PREFIX + source.name, null)
        return ScaleMode.entries.firstOrNull { it.name == name } ?: ScaleMode.FIT
    }

    fun setScaleMode(context: Context, source: Source, mode: ScaleMode) {
        prefs(context).edit().putString(KEY_SCALE_PREFIX + source.name, mode.name).apply()
        notifyChanged()
    }

    private fun notifyChanged() {
        main.post { listeners.forEach { it() } }
    }
}
