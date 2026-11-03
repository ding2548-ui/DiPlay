package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.LeapmotorVehicleSignals

/**
 * Whether in-car video may play on a Leapmotor car.
 *
 * The two car lines do not agree on the gear that means "parked". BYD reports **P** and
 * [BydParkedState] reads it over ADB. A Leapmotor head unit only reports R/N/D on the CAN bus
 * (`1010105`) and the car allows video in **N** — the OEM video player works that way, and there is
 * no P value to look for.
 *
 * Lives in `shared` (not next to `LeapmotorGearMonitor` in `common`) because
 * [BydNavigationOutputs] needs to answer the same question for the session, and `shared` cannot see
 * `common`. Public so the `common`-side monitor can delegate here instead of duplicating the rule.
 */
object LeapmotorParkedState {
    /**
     * True only when the newest CAN frame says N. Null while no gear was ever reported, which the
     * video gate treats as "not allowed": a missing broadcast must never be read as parked.
     */
    fun videoAllowed(): Boolean? {
        val raw = LeapmotorVehicleSignals.snapshot()?.gearRaw ?: return null
        return raw == LeapmotorVehicleSignals.GEAR_N
    }
}
