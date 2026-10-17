package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig

/**
 * The charging current DiPlay offers the iPhone over wired iAP2. The offer tells the iPhone how much it
 * may draw; it does not limit the car's USB port. A lower offer can help when the port cannot supply the
 * iPhone's charging current and the iPhone drops off USB a few seconds after charging starts.
 * Stable preference values; never persist enum ordinals.
 */
enum class UsbChargingCurrent(val key: String, val milliAmps: Int) {
    NORMAL("normal", CarPlayRuntimeConfig.DEFAULT_AVAILABLE_CURRENT_MILLI_AMPS),
    REDUCED("reduced", 1500),
    LOW("low", 500);

    companion object {
        fun fromKey(key: String?): UsbChargingCurrent = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}
