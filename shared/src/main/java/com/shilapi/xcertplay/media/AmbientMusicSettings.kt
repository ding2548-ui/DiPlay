package com.shilapi.xcertplay.media

import android.content.Context

object AmbientMusicSettings {
    private const val PREFS = "ambient_music"

    /** Default flow-light brightness (OEM `lampDeg` scale, 0..10). */
    const val FLOW_BRIGHTNESS_DEFAULT = 5

    /** OEM `lampDeg` range. 0 is the lowest level, not a power-off sentinel. */
    val FLOW_BRIGHTNESS_RANGE = 0..10

    data class Values(val enabled: Boolean = false, val music: Boolean = true,
        val colorMode: AmbientColorMode = AmbientColorMode.BEAT,
        val speed: AmbientColorSpeed = AmbientColorSpeed.STANDARD, val selectedColors: List<Int> = listOf(1), val colorCycle: Boolean = false, val color: Int = 1,
        val flowMode: AmbientFlowMode = AmbientFlowMode.MULTI,
        /**
         * The BYD "maximum brightness" ceiling (1..6) and the Leapmotor flow-light brightness
         * (0..10) share one user-facing row. [brightness] keeps the BYD path byte-for-byte
         * identical; [flowBrightness] is what the Leapmotor protocol sends as `lampDeg`, which is a
         * brightness level and not an angle — the headlight-levelling angle is a separate channel
         * (`angledeng`) that this protocol does not carry.
         */
        val brightness: Int = 0, val flowBrightness: Int = FLOW_BRIGHTNESS_DEFAULT,
        val area: Int = 3,
        val colorSource: AmbientColorSource = AmbientColorSource.SELECTED)

    fun load(context: Context): Values {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun boolean(key: String, fallback: Boolean) = runCatching { p.getBoolean(key, fallback) }.getOrDefault(fallback)
        fun integer(key: String, fallback: Int) = runCatching { p.getInt(key, fallback) }.getOrDefault(fallback)
        fun text(key: String, fallback: String) = runCatching { p.getString(key, fallback) ?: fallback }.getOrDefault(fallback)
        val color = integer("color", 1).coerceIn(1, 31)
        val palette = runCatching { p.getStringSet("selectedColors", null)?.mapNotNull { it.toIntOrNull() } }.getOrNull()
        return Values(
            enabled = boolean("enabled", false),
            music = boolean("music", true),
            colorMode = runCatching { AmbientColorMode.valueOf(text("colorMode", "BEAT")) }.getOrDefault(AmbientColorMode.BEAT),
            speed = runCatching { AmbientColorSpeed.valueOf(text("speed", "STANDARD")) }.getOrDefault(AmbientColorSpeed.STANDARD),
            selectedColors = normalizeAmbientPalette(palette ?: listOf(color), color),
            colorCycle = boolean("colorCycle", false),
            color = color,
            flowMode = runCatching { AmbientFlowMode.valueOf(text("flowMode", AmbientFlowMode.MULTI.name)) }
                .getOrDefault(AmbientFlowMode.MULTI),
            brightness = integer("brightness", 0).coerceIn(0, 6),
            flowBrightness = integer("flowBrightness", FLOW_BRIGHTNESS_DEFAULT)
                .coerceIn(FLOW_BRIGHTNESS_RANGE.first, FLOW_BRIGHTNESS_RANGE.last),
            area = integer("area", 3).coerceIn(1, 3),
            colorSource = runCatching { AmbientColorSource.valueOf(text("colorSource", "SELECTED")) }.getOrDefault(AmbientColorSource.SELECTED),
        )
    }

    fun save(context: Context, values: Values) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", values.enabled).putBoolean("music", values.music)
            .putBoolean("colorCycle", values.colorCycle).putString("colorMode", values.colorMode.name)
            .putString("speed", values.speed.name).putString("colorSource", values.colorSource.name)
            .putString("flowMode", values.flowMode.name)
            .putStringSet("selectedColors", normalizeAmbientPalette(values.selectedColors, values.color).map { it.toString() }.toSet())
            .putInt("color", values.color.coerceIn(1, 31))
            .putInt("brightness", values.brightness.coerceIn(0, 6))
            .putInt("flowBrightness", values.flowBrightness
                .coerceIn(FLOW_BRIGHTNESS_RANGE.first, FLOW_BRIGHTNESS_RANGE.last))
            .putInt("area", values.area.coerceIn(1, 3)).apply()
        AmbientMusicController.settingsChanged(context)
    }
}
