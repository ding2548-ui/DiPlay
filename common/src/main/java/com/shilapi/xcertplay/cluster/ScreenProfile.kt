package com.shilapi.xcertplay.cluster

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import org.json.JSONObject

/**
 * OgleaPlay's normalized rectangle, ported 1:1 from `p/sy2`.
 *
 * It is a rectangle in the secondary screen's 0..1 canvas, not in pixels: the same profile then
 * survives a resolution change or a different panel. The constructor keeps the original's
 * validation, including the hard-coded 0.1 % tolerance that stops `0.3 + 0.7` from failing on
 * float rounding.
 */
data class ScreenRegion(val x: Float, val y: Float, val width: Float, val height: Float) {
    init {
        require(x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite()) {
            "ScreenRegion values must be finite"
        }
        require(x >= 0f && y >= 0f) { "ScreenRegion origin must not be negative" }
        require(width > 0f && height > 0f) { "ScreenRegion size must be positive" }
        require(x + width <= 1.001f) { "ScreenRegion exceeds the canvas horizontally" }
        require(y + height <= 1.001f) { "ScreenRegion exceeds the canvas vertically" }
    }

    val right: Float get() = (x + width).coerceAtMost(1f)
    val bottom: Float get() = (y + height).coerceAtMost(1f)

    fun clamped(): ScreenRegion = ScreenRegion(
        x.coerceIn(0f, 0.999f),
        y.coerceIn(0f, 0.999f),
        width.coerceIn(0.001f, 1f - x.coerceIn(0f, 0.999f)),
        height.coerceIn(0.001f, 1f - y.coerceIn(0f, 0.999f)),
    )

    companion object {
        val FULL = ScreenRegion(0f, 0f, 1f, 1f)

        /** The music card's default in the original: `(0.2, 0.15, 0.6, 0.7)`. */
        val MUSIC_DEFAULT = ScreenRegion(0.2f, 0.15f, 0.6f, 0.7f)

        fun fromJson(json: JSONObject?, fallback: ScreenRegion): ScreenRegion {
            if (json == null) return fallback
            return runCatching {
                ScreenRegion(
                    json.optDouble("x", fallback.x.toDouble()).toFloat(),
                    json.optDouble("y", fallback.y.toDouble()).toFloat(),
                    json.optDouble("w", fallback.width.toDouble()).toFloat(),
                    json.optDouble("h", fallback.height.toDouble()).toFloat(),
                )
            }.getOrDefault(fallback)
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("x", x.toDouble()).put("y", y.toDouble())
        .put("w", width.toDouble()).put("h", height.toDouble())
}

/** `p/cy2`: how the source frame maps into its region. */
enum class FitMode { FIT, CROP }

/**
 * `p/ty2`: which CarPlay stream feeds the secondary screen.
 *
 * [streamType] is the CarPlay stream type the original used: 110 mirrors the main screen, 111 is
 * the iPhone's independent cluster stream, and MUSIC carries no video at all.
 */
enum class ContentSource(val label: String, val streamType: Int?) {
    MAIN("跟随主屏", 110),
    NAVIGATION("独立导航", 111),
    MUSIC("独立音乐", null),
    ;

    /** The original cycled MAIN → NAVIGATION → MUSIC with a five-finger swipe. */
    fun next(): ContentSource = when (this) {
        MAIN -> NAVIGATION
        NAVIGATION -> MUSIC
        MUSIC -> MAIN
    }
}

/** `p/oy2`: launch a real activity on the display, or fall back to a dialog on the main screen. */
enum class OutputMode { ACTIVITY, WINDOW }

/**
 * `p/ry2`: the seven fields the original kept per display.
 *
 * Every source owns **both** a video region and a crop region, so switching sources never inherits
 * another source's geometry — that separation is the whole point of the model.
 */
data class ScreenProfile(
    val video: ScreenRegion = ScreenRegion.FULL,
    val mode: FitMode = FitMode.FIT,
    val crop: ScreenRegion = ScreenRegion.FULL,
    val source: ContentSource = ContentSource.MAIN,
    val navigationVideo: ScreenRegion = ScreenRegion.FULL,
    val navigationCrop: ScreenRegion = ScreenRegion.FULL,
    val musicVideo: ScreenRegion = ScreenRegion.MUSIC_DEFAULT,
) {
    /** `b()`: the region of whichever source is active. */
    val activeVideo: ScreenRegion
        get() = when (source) {
            ContentSource.MAIN -> video
            ContentSource.NAVIGATION -> navigationVideo
            ContentSource.MUSIC -> musicVideo
        }

    /** `d()`: the crop rect of whichever source is active. */
    val activeCrop: ScreenRegion
        get() = when (source) {
            ContentSource.MAIN -> crop
            ContentSource.NAVIGATION -> navigationCrop
            ContentSource.MUSIC -> musicVideo
        }

    /** `c(region)`: writes the active source's video region, never another source's. */
    fun withVideo(region: ScreenRegion): ScreenProfile = when (source) {
        ContentSource.MAIN -> copy(video = region)
        ContentSource.NAVIGATION -> copy(navigationVideo = region)
        ContentSource.MUSIC -> copy(musicVideo = region)
    }

    /** Writes the active source's crop rect. */
    fun withCrop(region: ScreenRegion): ScreenProfile = when (source) {
        ContentSource.MAIN -> copy(crop = region)
        ContentSource.NAVIGATION -> copy(navigationCrop = region)
        ContentSource.MUSIC -> copy(musicVideo = region)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("video", video.toJson())
        .put("mode", mode.name)
        .put("crop", crop.toJson())
        .put("source", source.name)
        .put("navigationVideo", navigationVideo.toJson())
        .put("navigationCrop", navigationCrop.toJson())
        .put("musicVideo", musicVideo.toJson())

    companion object {
        /** A fresh profile starts on [source] with everything full-screen, as the original did. */
        fun initial(source: ContentSource = ContentSource.MAIN): ScreenProfile =
            ScreenProfile(source = source)

        fun fromJson(text: String?): ScreenProfile {
            if (text.isNullOrBlank()) return ScreenProfile()
            return runCatching {
                val json = JSONObject(text)
                ScreenProfile(
                    video = ScreenRegion.fromJson(json.optJSONObject("video"), ScreenRegion.FULL),
                    mode = FitMode.entries.firstOrNull { it.name == json.optString("mode") } ?: FitMode.FIT,
                    crop = ScreenRegion.fromJson(json.optJSONObject("crop"), ScreenRegion.FULL),
                    source = ContentSource.entries.firstOrNull { it.name == json.optString("source") }
                        ?: ContentSource.MAIN,
                    navigationVideo = ScreenRegion.fromJson(
                        json.optJSONObject("navigationVideo"), ScreenRegion.FULL),
                    navigationCrop = ScreenRegion.fromJson(
                        json.optJSONObject("navigationCrop"), ScreenRegion.FULL),
                    musicVideo = ScreenRegion.fromJson(
                        json.optJSONObject("musicVideo"), ScreenRegion.MUSIC_DEFAULT),
                )
            }.getOrDefault(ScreenProfile())
        }
    }
}

/**
 * `p/ua2`: per-display persistence, keyed the way the original keyed it so two panels (or two cars)
 * never overwrite each other's calibration.
 *
 * Key = `model|firmware|uniqueId|WxH`. `uniqueId` needs `Display.getUniqueId()`, which is API 29+;
 * the original fell back to `name:displayId` there and so do we.
 */
internal object ScreenProfileStore {
    private const val PREFS = "screen_profiles"
    private const val EXTENSION_PREFS = "vehicle_extensions"
    private const val KEY_OUTPUT_MODE = "screen_output_mode"
    private const val KEY_ENABLED = "secondary_screen_enabled"
    private const val KEY_LAST_IDENTITY = "last_screen_output_identity"
    private const val KEY_LAST_ID = "last_screen_output_id"
    private const val KEY_MODEL = "model"
    private const val KEY_FIRMWARE = "firmware"
    private const val NAV_REQUESTED_SUFFIX = "_navigation_stream_requested"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun extensions(context: Context) =
        context.getSharedPreferences(EXTENSION_PREFS, Context.MODE_PRIVATE)

    /** `A(display)`: the identity key this display's profile is stored under. */
    fun identity(context: Context, display: Display): String {
        val extensions = extensions(context)
        val model = extensions.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL.orEmpty()
        val firmware = extensions.getString(KEY_FIRMWARE, null)?.takeIf { it.isNotBlank() }
            ?: Build.DISPLAY.orEmpty()
        // `Display.getUniqueId()` is @hide; the original reached it by reflection and so does this.
        val uniqueId = runCatching {
            Display::class.java.getMethod("getUniqueId").invoke(display) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "${display.name}:${display.displayId}"
        val size = runCatching {
            val mode = display.mode
            "${mode.physicalWidth}x${mode.physicalHeight}"
        }.getOrDefault("unknown")
        return "$model|$firmware|$uniqueId|$size"
    }

    fun load(context: Context, display: Display): ScreenProfile =
        ScreenProfile.fromJson(prefs(context).getString(identity(context, display), null))

    fun save(context: Context, display: Display, profile: ScreenProfile) {
        val key = identity(context, display)
        prefs(context).edit().putString(key, profile.toJson().toString()).apply()
        // `G()` also maintains the navigation-stream flag other parts of the protocol read.
        prefs(context).edit()
            .putBoolean(key + NAV_REQUESTED_SUFFIX, profile.source == ContentSource.NAVIGATION)
            .apply()
    }

    fun outputMode(context: Context): OutputMode {
        val name = extensions(context).getString(KEY_OUTPUT_MODE, null)
        return OutputMode.entries.firstOrNull { it.name == name } ?: OutputMode.ACTIVITY
    }

    /** Whether the secondary-screen output replaces the in-app cluster card. */
    fun enabled(context: Context): Boolean = extensions(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        extensions(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * The card/cluster profile is what the settings page edits, and the same content source and
     * regions must drive every output. This mirrors them into [display]'s profile, keeping the
     * crop rectangles (which only the secondary renderer can realize).
     */
    fun syncFromClusterProfile(context: Context, display: Display) {
        val previous = load(context, display)
        fun region(source: ClusterScreenProfile.Source): ScreenRegion {
            val value = ClusterScreenProfile.region(context, source)
            return ScreenRegion(value.x, value.y, value.width, value.height)
        }
        val mode = when (ClusterScreenProfile.scaleMode(context, ClusterScreenProfile.source(context))) {
            ClusterScreenProfile.ScaleMode.FIT -> FitMode.FIT
            ClusterScreenProfile.ScaleMode.CROP -> FitMode.CROP
        }
        val source = when (ClusterScreenProfile.source(context)) {
            ClusterScreenProfile.Source.MAIN -> ContentSource.MAIN
            ClusterScreenProfile.Source.NAVIGATION -> ContentSource.NAVIGATION
            ClusterScreenProfile.Source.MUSIC -> ContentSource.MUSIC
        }
        save(context, display, ScreenProfile(
            video = region(ClusterScreenProfile.Source.MAIN),
            mode = mode,
            crop = previous.crop,
            source = source,
            navigationVideo = region(ClusterScreenProfile.Source.NAVIGATION),
            navigationCrop = previous.navigationCrop,
            musicVideo = region(ClusterScreenProfile.Source.MUSIC),
        ))
    }

    fun setOutputMode(context: Context, mode: OutputMode) {
        extensions(context).edit().putString(KEY_OUTPUT_MODE, mode.name).apply()
    }

    /** The display that worked last time, so a reconnect lands on the same panel. */
    fun lastIdentity(context: Context): String? =
        extensions(context).getString(KEY_LAST_IDENTITY, null)?.takeIf { it.isNotBlank() }

    fun lastDisplayId(context: Context): Int =
        extensions(context).getInt(KEY_LAST_ID, -1)

    fun rememberOutput(context: Context, display: Display) {
        extensions(context).edit()
            .putString(KEY_LAST_IDENTITY, identity(context, display))
            .putInt(KEY_LAST_ID, display.displayId)
            .apply()
    }

    /** `g()`: every valid display except the default one. */
    fun secondaryDisplays(context: Context): List<Display> {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        return manager.displays.filter { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
    }

    /** The remembered display, else the first one; null when the unit has no secondary display. */
    fun preferredDisplay(context: Context): Display? {
        val displays = secondaryDisplays(context)
        if (displays.isEmpty()) return null
        val identity = lastIdentity(context)
        if (identity != null) displays.firstOrNull { identity(context, it) == identity }?.let { return it }
        val lastId = lastDisplayId(context)
        return displays.firstOrNull { it.displayId == lastId } ?: displays.first()
    }

    fun describe(context: Context, display: Display): String {
        val size = runCatching {
            val mode = display.mode
            "${mode.physicalWidth}x${mode.physicalHeight}"
        }.getOrDefault("?x?")
        return "Display ${display.displayId} · ${display.name} · $size"
    }
}
