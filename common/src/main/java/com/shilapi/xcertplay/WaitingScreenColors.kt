package com.shilapi.xcertplay

/** The screen before CarPlay's video follows CarPlay's day/night mode, so it does not glare at night. */
internal data class WaitingScreenColors(val background: Int, val text: Int, val secondary: Int) {
    companion object {
        fun of(night: Boolean): WaitingScreenColors = DiPlayPalette.of(night).let {
            WaitingScreenColors(it.background, it.primaryText, it.secondaryText)
        }
    }
}
