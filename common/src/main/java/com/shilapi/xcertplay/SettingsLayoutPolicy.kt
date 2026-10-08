package com.shilapi.xcertplay

/** Chooses the settings information architecture from usable window dimensions, not pixels. */
internal object SettingsLayoutPolicy {
    // 840 keeps 1280-pixel units at 240 dpi (853 dp) on the rail.
    const val EXPANDED_MIN_WIDTH_DP = 840
    // The rail scrolls on its own, so short ultrawide units (1920x720 at 240 dpi, 480 dp) still get it.
    const val EXPANDED_MIN_HEIGHT_DP = 400
    // Two overview columns need about 320 dp each beside the rail.
    const val OVERVIEW_TWO_COLUMN_MIN_WIDTH_DP = 1000
    const val RAIL_WIDTH_DP = 240
    private const val MAX_RAIL_SCALE = 1.5f

    // Larger text needs proportionally more width before the rail and content fit side by side.
    fun isExpanded(widthDp: Int, heightDp: Int, fontScale: Float): Boolean =
        widthDp >= EXPANDED_MIN_WIDTH_DP * fontScale.coerceAtLeast(1f) &&
            heightDp >= EXPANDED_MIN_HEIGHT_DP

    fun overviewHasTwoColumns(widthDp: Int, fontScale: Float): Boolean =
        widthDp >= OVERVIEW_TWO_COLUMN_MIN_WIDTH_DP * fontScale.coerceAtLeast(1f)

    fun railWidthDp(fontScale: Float): Int = (RAIL_WIDTH_DP * fontScale.coerceIn(1f, MAX_RAIL_SCALE)).toInt()
}
