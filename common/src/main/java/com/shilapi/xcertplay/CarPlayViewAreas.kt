package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import kotlin.math.abs
import kotlin.math.ln

/**
 * The view areas the main screen declares for one session, and the one CarPlay uses now. A fixed dock
 * declares every area once per dock edge; the head unit's split screen adds an area the size of DiPlay's
 * window there; a square canvas for a turning screen holds a landscape and a portrait screen. The car
 * moves CarPlay between them with updateViewArea, without reconnecting. No Android types beyond
 * preferences, so the choices are unit-tested.
 */
class CarPlayViewAreas private constructor(
    val areas: List<AirPlayViewArea>,
    private val slots: List<Slot>,
    initial: Int,
) {
    enum class Kind { FULL_SCREEN, SPLIT_SCREEN }

    /** A full screen the canvas holds: its size and whether the screen is portrait then. */
    data class Screen(val width: Int, val height: Int, val portrait: Boolean)

    private data class Slot(val kind: Kind, val portrait: Boolean)

    /** The area CarPlay uses now; shared by the session's host and its settings. */
    @Volatile var current: Int = initial
        private set

    /** True when the canvas holds both a landscape and a portrait screen. */
    val turnsWithScreen: Boolean = slots.any { it.portrait } && slots.any { !it.portrait }

    fun kindOf(index: Int): Kind = slots[index].kind

    /** The area of [kind] with [dockEdge] (or the edge-less one) on the current screen, or null. */
    fun index(kind: Kind, dockEdge: Int?): Int? = index(kind, slots[current].portrait, dockEdge)

    fun index(kind: Kind, portrait: Boolean, dockEdge: Int?): Int? =
        areas.indices.firstOrNull { slots[it].kind == kind && slots[it].portrait == portrait && areas[it].dockEdge == dockEdge }

    /**
     * The area for DiPlay's window on a [portrait] or landscape screen, keeping the dock edge: the
     * split-screen area in the head unit's split screen when one has about the window's shape, the
     * whole screen otherwise. Null when this session has no area for that screen (the caller reconnects).
     */
    fun indexFor(width: Int, height: Int, splitScreen: Boolean, portrait: Boolean = slots[current].portrait): Int? {
        val edge = areas[current].dockEdge
        val split = index(Kind.SPLIT_SCREEN, portrait, edge)
        if (splitScreen && split != null && closeShape(areas[split], width, height)) return split
        return index(Kind.FULL_SCREEN, portrait, edge)
    }

    /** The area in use after moving the dock to [dockEdge], keeping the kind of area and the screen. */
    fun withDock(dockEdge: Int): Int? = index(kindOf(current), dockEdge)

    fun use(index: Int) {
        current = index.coerceIn(0, areas.lastIndex)
    }

    companion object {
        /** Further than this (as an aspect ratio) from DiPlay's window, a split-screen area does not fit it. */
        private const val MAX_ASPECT_MISMATCH = 1.25

        /** One screen the size of the stream (no turning); see the general [build]. */
        fun build(width: Int, height: Int, dock: CarPlayDock, splitWindow: Pair<Float, Float>?): CarPlayViewAreas? =
            build(width, height, listOf(Screen(width, height, portrait = height > width)), dock,
                splitWindow = { splitWindow }, startPortrait = height > width)

        /**
         * The areas for a [canvasWidth] x [canvasHeight] stream holding [screens] (each at its top-left
         * corner), or null when the whole stream as one area will do (one screen, automatic dock, no split
         * screen). [splitWindow] gives the split-screen window on a portrait or landscape screen as
         * fractions of that full screen, or null without split-screen support.
         */
        fun build(
            canvasWidth: Int,
            canvasHeight: Int,
            screens: List<Screen>,
            dock: CarPlayDock,
            splitWindow: (portrait: Boolean) -> Pair<Float, Float>?,
            startPortrait: Boolean,
        ): CarPlayViewAreas? {
            val splits = screens.associateWith { splitWindow(it.portrait) }
            if (screens.size == 1 && dock.edge == null && splits.values.all { it == null } &&
                screens[0].width == canvasWidth && screens[0].height == canvasHeight) return null
            val edges = dock.edge?.let { listOf(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, AirPlayInfoPlist.DOCK_EDGE_BOTTOM) }
                ?: listOf(null)
            val areas = mutableListOf<AirPlayViewArea>()
            val slots = mutableListOf<Slot>()
            for (screen in screens) for (edge in edges) {
                areas += AirPlayViewArea(screen.width, screen.height, dockEdge = edge)
                slots += Slot(Kind.FULL_SCREEN, screen.portrait)
            }
            for (screen in screens) {
                val window = splits[screen] ?: continue
                // The window's own size (BYD shows its bars in split screen), kept even for the encoder.
                val splitWidth = (screen.width * window.first).toInt().coerceIn(2, screen.width) and 1.inv()
                val splitHeight = (screen.height * window.second).toInt().coerceIn(2, screen.height) and 1.inv()
                for (edge in edges) {
                    areas += AirPlayViewArea(splitWidth, splitHeight, dockEdge = edge)
                    slots += Slot(Kind.SPLIT_SCREEN, screen.portrait)
                }
            }
            val initial = areas.indices.firstOrNull {
                slots[it].kind == Kind.FULL_SCREEN && slots[it].portrait == startPortrait && areas[it].dockEdge == dock.edge
            } ?: areas.indices.first { slots[it].kind == Kind.FULL_SCREEN && areas[it].dockEdge == dock.edge }
            return CarPlayViewAreas(areas, slots, initial)
        }

        private fun closeShape(area: AirPlayViewArea, width: Int, height: Int): Boolean {
            if (width <= 0 || height <= 0) return false
            val ratio = area.width.toDouble() / area.height / (width.toDouble() / height)
            return abs(ln(ratio)) <= ln(MAX_ASPECT_MISMATCH)
        }
    }
}

/**
 * Optional: CarPlay fills DiPlay's window in the head unit's split screen without reconnecting. The
 * window there is remembered (as fractions of the full window), so the next connection declares an area
 * of exactly that size.
 */
object SplitScreenSettings {
    private const val PREFS = "diplay_split_screen"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /**
     * The split-screen window seen last on a [portrait] or landscape screen, as fractions of the full
     * window. Before that: half the width side by side, or half the height on a portrait screen.
     */
    fun window(context: Context, portrait: Boolean): Pair<Float, Float> {
        val key = if (portrait) "portrait" else "landscape"
        val width = prefs(context).getFloat("${key}_width", 0f)
        val height = prefs(context).getFloat("${key}_height", 0f)
        return if (width in 0.1f..1f && height in 0.1f..1f) width to height else if (portrait) 1f to 0.5f else 0.5f to 1f
    }

    fun saveWindow(context: Context, portrait: Boolean, width: Float, height: Float) {
        if (width !in 0.1f..1f || height !in 0.1f..1f) return
        val key = if (portrait) "portrait" else "landscape"
        prefs(context).edit().putFloat("${key}_width", width).putFloat("${key}_height", height).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
