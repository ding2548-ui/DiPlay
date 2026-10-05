package com.shilapi.xcertplay.hud

/**
 * The cluster's navigation mode, the one the driver picks in the steering-wheel menu. BYD keeps it
 * in instrument property INSTRUMENT_NAVI_TYPE; apps need a BYD signature for it, the adb shell
 * reads it through the autoservice binder. Verified on DiLink 5.0 (Android 12).
 */
enum class BydClusterNaviMode(val code: Int, val label: String) {
    OFF(1, "Off"),
    TURN_ON_BY_NAVI(2, "Turn on by navi"),
    SMALL(3, "Small screen navi"),
    FULL(4, "Full screen navi");

    /** Small and Full screen navi show the projection (DiPlay's map); Off and Turn on by navi do not. */
    val showsMap: Boolean get() = this == SMALL || this == FULL

    companion object {
        private const val INSTRUMENT_DEVICE = 1007
        private const val NAVI_TYPE_GET = 0x40C03032
        private const val GET_INT = 5

        const val READ_COMMAND = "service call autoservice $GET_INT i32 $INSTRUMENT_DEVICE i32 $NAVI_TYPE_GET"

        fun fromCode(code: Int): BydClusterNaviMode? = entries.firstOrNull { it.code == code }

        /** `Result: Parcel(00000000 00000002 '........')`: no exception, then the value. */
        fun parseRead(output: String?): BydClusterNaviMode? = BydParcel.value(output)?.let(::fromCode)
    }
}
