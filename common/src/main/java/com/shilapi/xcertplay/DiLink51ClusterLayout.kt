package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay

/** Geometry measured against the stock full-map and right-hand side-card layers on this firmware. */
internal object DiLink51ClusterLayout {
    const val FINGERPRINT = "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys"
    const val BASE = "fission_bg_XDJAScreenProjection"
    const val FULL = "shared_${BASE}_0"
    const val SIDE = "shared_${BASE}_1"
    private const val PREFS = "diplay_cluster_layout"

    enum class Theme(val label: String) {
        SCENARIO("Scenario · side map"), MAP("Map · full map"), SIMPLE("Simple · side map")
    }
    enum class Contrast(val label: String) {
        DEFAULT("Theme default"), LIGHT("Light background · dark instruments"), DARK("Dark background · light instruments")
    }
    data class Plan(val left: Int, val top: Int, val width: Int, val height: Int, val fullMap: Boolean) {
        // Crop at native pixel scale around the centered car marker, retaining the bottom
        // of the map. The iPhone's stream size never changes when the driver changes themes.
        val sourceLeft: Int get() = (STREAM_WIDTH - width) / 2
        val sourceTop: Int get() = STREAM_HEIGHT - height
    }
    const val STREAM_WIDTH = 1920
    const val STREAM_HEIGHT = 720
    // DiLink 5.1 retains its measured native-size stream and bottom-centered crop.
    // DiLink 5's marker safe area and scale controls must not alter this profile.
    fun streamConfig(): AirPlayDisplayConfig = CarPlayClusterDisplay.config(
        STREAM_WIDTH, STREAM_HEIGHT, scalePercent = 100,
    ).copy(safeArea = null)

    fun supported(fingerprint: String = Build.FINGERPRINT): Boolean = fingerprint == FINGERPRINT
    fun theme(context: Context): Theme = Theme.entries.firstOrNull {
        it.name == context.getSharedPreferences(PREFS, 0).getString("theme", null)
    } ?: Theme.SCENARIO
    fun saveTheme(context: Context, theme: Theme) {
        context.getSharedPreferences(PREFS, 0).edit().putString("theme", theme.name).apply()
    }
    fun automatic(context: Context): Boolean = supported() && context.getSharedPreferences(PREFS, 0).getBoolean("automatic", false)
    fun saveAutomatic(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("automatic", enabled).apply()
    }
    fun contrast(context: Context): Contrast = Contrast.entries.firstOrNull {
        it.name == context.getSharedPreferences(PREFS, 0).getString("contrast", null)
    } ?: Contrast.DEFAULT
    fun saveContrast(context: Context, contrast: Contrast) {
        context.getSharedPreferences(PREFS, 0).edit().putString("contrast", contrast.name).apply()
    }
    fun dark(context: Context, theme: Theme = theme(context)): Boolean = when (contrast(context)) {
        Contrast.DEFAULT -> theme == Theme.SIMPLE
        Contrast.LIGHT -> false
        Contrast.DARK -> true
    }

    fun displayName(names: List<String>, fingerprint: String, theme: Theme): String? {
        if (supported(fingerprint)) {
            // Never substitute the full layer for a missing side layer: that hides Scenario/Simple.
            return (if (theme == Theme.MAP) FULL else SIDE).takeIf { it in names }
        }
        // Preserve PR #5's selection order on DiLink 5 and all unverified firmware.
        return names.firstOrNull { it == BASE }
            ?: names.firstOrNull { it.contains(BASE) && it.endsWith("_0") }
            ?: names.firstOrNull { it.contains(BASE) }
    }

    fun plan(width: Int, height: Int, theme: Theme): Plan? {
        // The measured side-map bounds are x=1320..1920 on the 1920x720 cluster.
        if (width != 1920 || height != 720) return null
        return if (theme == Theme.MAP) {
            // Protect speed/status and gear/range. Bottom-aligned cropping keeps the car marker
            // inside this viewport even when the phone app ignores safe-area insets.
            Plan(0, 144, width, 480, true)
        } else Plan(1320, 0, 600, height, false)
    }
}
