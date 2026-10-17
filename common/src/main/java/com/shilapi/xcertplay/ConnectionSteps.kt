package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R

/**
 * The full CarPlay connection broken into visible steps, mirroring FreeAuto's
 * ConnectionSteps: wireless CarPlay is 40 steps (Bluetooth bootstrap 1-31,
 * Wi-Fi bring-up 32-40) and wired CarPlay is 25 steps.
 *
 * The step names come from the same step_wl_102_* / step_wd_102_* string
 * families FreeAuto ships (English defaults, Chinese in values-zh-rCN).
 */
class Step(val nameRes: Int)

object ConnectionSteps {
    // Wireless anchors, same numbering as FreeAuto.
    const val WIRELESS_TOTAL = 40
    const val WIRELESS_WIFI_FIRST = 32
    const val WIRELESS_BLUETOOTH_LAST = 31
    const val WIRELESS_PICTURE = 40
    const val WIRED_TOTAL = 25
    const val WIRED_PICTURE = 25

    val WIRELESS: List<Step> = listOf(
        Step(R.string.connection_step_wl_1),
        Step(R.string.connection_step_wl_2),
        Step(R.string.connection_step_wl_3),
        Step(R.string.connection_step_wl_4),
        Step(R.string.connection_step_wl_5),
        Step(R.string.connection_step_wl_6),
        Step(R.string.connection_step_wl_7),
        Step(R.string.connection_step_wl_8),
        Step(R.string.connection_step_wl_9),
        Step(R.string.connection_step_wl_10),
        Step(R.string.connection_step_wl_11),
        Step(R.string.connection_step_wl_12),
        Step(R.string.connection_step_wl_13),
        Step(R.string.connection_step_wl_14),
        Step(R.string.connection_step_wl_15),
        Step(R.string.connection_step_wl_16),
        Step(R.string.connection_step_wl_17),
        Step(R.string.connection_step_wl_18),
        Step(R.string.connection_step_wl_19),
        Step(R.string.connection_step_wl_20),
        Step(R.string.connection_step_wl_21),
        Step(R.string.connection_step_wl_22),
        Step(R.string.connection_step_wl_23),
        Step(R.string.connection_step_wl_24),
        Step(R.string.connection_step_wl_25),
        Step(R.string.connection_step_wl_26),
        Step(R.string.connection_step_wl_27),
        Step(R.string.connection_step_wl_28),
        Step(R.string.connection_step_wl_29),
        Step(R.string.connection_step_wl_30),
        Step(R.string.connection_step_wl_31),
        Step(R.string.connection_step_wl_32),
        Step(R.string.connection_step_wl_33),
        Step(R.string.connection_step_wl_34),
        Step(R.string.connection_step_wl_35),
        Step(R.string.connection_step_wl_36),
        Step(R.string.connection_step_wl_37),
        Step(R.string.connection_step_wl_38),
        Step(R.string.connection_step_wl_39),
        Step(R.string.connection_step_wl_40),
    )

    val WIRED: List<Step> = listOf(
        Step(R.string.connection_step_wd_1),
        Step(R.string.connection_step_wd_2),
        Step(R.string.connection_step_wd_3),
        Step(R.string.connection_step_wd_4),
        Step(R.string.connection_step_wd_5),
        Step(R.string.connection_step_wd_6),
        Step(R.string.connection_step_wd_7),
        Step(R.string.connection_step_wd_8),
        Step(R.string.connection_step_wd_9),
        Step(R.string.connection_step_wd_10),
        Step(R.string.connection_step_wd_11),
        Step(R.string.connection_step_wd_12),
        Step(R.string.connection_step_wd_13),
        Step(R.string.connection_step_wd_14),
        Step(R.string.connection_step_wd_15),
        Step(R.string.connection_step_wd_16),
        Step(R.string.connection_step_wd_17),
        Step(R.string.connection_step_wd_18),
        Step(R.string.connection_step_wd_19),
        Step(R.string.connection_step_wd_20),
        Step(R.string.connection_step_wd_21),
        Step(R.string.connection_step_wd_22),
        Step(R.string.connection_step_wd_23),
        Step(R.string.connection_step_wd_24),
        Step(R.string.connection_step_wd_25),
    )

    fun of(wireless: Boolean): List<Step> = if (wireless) WIRELESS else WIRED

    fun count(wireless: Boolean): Int = if (wireless) WIRELESS_TOTAL else WIRED_TOTAL
}
