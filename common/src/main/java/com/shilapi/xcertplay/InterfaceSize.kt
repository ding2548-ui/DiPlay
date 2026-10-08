// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/**
 * Scales DiPlay's own screens. Some head units report a low density for a large screen, so Android
 * lays them out like a very wide tablet with tiny text and controls. CarPlay's picture is not affected.
 */
object InterfaceSize {
    const val AUTO = 0
    val CHOICES = listOf(AUTO, 100, 125, 150, 200)

    // Auto scales up until the shorter side is this wide; screens already narrower stay at 100%.
    private const val AUTO_SMALLEST_WIDTH_DP = 720
    private const val PREFS = "diplay"
    private const val KEY = "interface_size"

    fun preference(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, AUTO).takeIf { it in CHOICES } ?: AUTO

    fun save(context: Context, choice: Int) {
        require(choice in CHOICES)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, choice).apply()
    }

    internal fun scale(choice: Int, smallestWidthDp: Int): Float = when {
        choice != AUTO -> choice / 100f
        smallestWidthDp <= AUTO_SMALLEST_WIDTH_DP -> 1f
        else -> smallestWidthDp.toFloat() / AUTO_SMALLEST_WIDTH_DP
    }

    /**
     * The override for [base], or null when the interface stays at its system size. It carries [base]'s
     * locales: before Android 13 the app language lives in the wrapped base context, and an override
     * without them falls back to the system language.
     */
    internal fun override(base: Configuration, choice: Int): Configuration? {
        val scale = scale(choice, base.smallestScreenWidthDp)
        if (scale == 1f) return null
        return Configuration().apply {
            densityDpi = (base.densityDpi * scale).roundToInt()
            screenWidthDp = (base.screenWidthDp / scale).roundToInt()
            screenHeightDp = (base.screenHeightDp / scale).roundToInt()
            smallestScreenWidthDp = (base.smallestScreenWidthDp / scale).roundToInt()
            setLocales(base.locales)
            setLayoutDirection(base.locales[0])
        }
    }

    /**
     * Keep window dimensions out of the context override. Android merges that override into every
     * configuration callback; explicit dimensions there would hide rotation and split-screen resizes.
     * The scaled dimensions are written to the live resources by [enforce] instead.
     */
    internal fun contextOverride(scaled: Configuration): Configuration = Configuration().apply {
        densityDpi = scaled.densityDpi
        setLocales(scaled.locales)
        setLayoutDirection(scaled.locales[0])
    }

    /** Applies the density override for [base] to [activity]; call from attachBaseContext. */
    internal fun attach(activity: ContextThemeWrapper, base: Context): Configuration? =
        override(base.resources.configuration, preference(base))?.also {
            activity.applyOverrideConfiguration(contextOverride(it))
        }

    /** Callbacks retain the context's scaled density, but report the system's fresh window dimensions. */
    internal fun configurationChange(reported: Configuration, systemDensityDpi: Int, choice: Int): Configuration? =
        override(Configuration(reported).apply { densityDpi = systemDensityDpi }, choice)

    /** An override with another density needs a new activity: applyOverrideConfiguration works once. */
    internal fun needsRecreate(applied: Configuration?, next: Configuration?): Boolean =
        applied?.densityDpi != next?.densityDpi

    /**
     * Some head-unit frameworks run every app in a compatibility mode that puts their own density back
     * after the override. Writes the override into the live metrics again; returns true if it had to.
     */
    internal fun enforce(resources: Resources, override: Configuration): Boolean {
        val metrics = resources.displayMetrics
        val configuration = resources.configuration
        if (metrics.densityDpi == override.densityDpi && configuration.densityDpi == override.densityDpi &&
            configuration.screenWidthDp == override.screenWidthDp &&
            configuration.screenHeightDp == override.screenHeightDp &&
            configuration.smallestScreenWidthDp == override.smallestScreenWidthDp
        ) return false
        val fontRatio = metrics.scaledDensity / metrics.density
        metrics.densityDpi = override.densityDpi
        metrics.density = override.densityDpi / DisplayMetrics.DENSITY_DEFAULT.toFloat()
        metrics.scaledDensity = metrics.density * fontRatio
        configuration.apply {
            densityDpi = override.densityDpi
            screenWidthDp = override.screenWidthDp
            screenHeightDp = override.screenHeightDp
            smallestScreenWidthDp = override.smallestScreenWidthDp
        }
        return true
    }

    fun displayName(context: Context, choice: Int): String =
        if (choice == AUTO) context.getString(R.string.settings_interface_size_auto) else "$choice%"

    fun showPicker(activity: Activity) {
        var selected = CHOICES.indexOf(preference(activity)).coerceAtLeast(0)
        activity.appDialogBuilder()
            .setTitle(R.string.settings_interface_size)
            .setSingleChoiceItems(CHOICES.map { displayName(activity, it) }.toTypedArray(), selected) { _, index ->
                selected = index
            }
            .setPositiveButton(R.string.language_apply) { _, _ ->
                if (CHOICES[selected] != preference(activity)) {
                    save(activity, CHOICES[selected])
                    activity.recreate()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
