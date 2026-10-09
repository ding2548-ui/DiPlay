package com.shilapi.xcertplay

/** What the Settings overview tells the driver about the next connection, most urgent first. */
internal enum class SettingsReadiness {
    SETUP_ERROR, CONNECTED, CONNECTING, CHOOSE_IPHONE, HOTSPOT_SETUP, READY_WIRELESS, READY_USB;

    val needsAction: Boolean get() = this == SETUP_ERROR || this == CHOOSE_IPHONE || this == HOTSPOT_SETUP

    companion object {
        fun of(setupError: Boolean, active: Boolean, running: Boolean, wireless: Boolean, phoneChosen: Boolean, hotspotSetupNeeded: Boolean = false) = when {
            setupError -> SETUP_ERROR
            active -> CONNECTED
            running -> CONNECTING
            wireless && !phoneChosen -> CHOOSE_IPHONE
            wireless && hotspotSetupNeeded -> HOTSPOT_SETUP
            wireless -> READY_WIRELESS
            else -> READY_USB
        }
    }
}
