package com.shilapi.xcertplay

/** The home card's session is the one DiPlay opened with the saved connection choice, saved before it opens. */
internal object HomeCardTransport {
    fun wired(sessionRunning: Boolean, wirelessSelected: Boolean): Boolean = sessionRunning && !wirelessSelected
}
