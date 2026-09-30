package com.shilapi.xcertplay.airplay

/**
 * CarPlay media HID press indices, matching the media descriptor in [AirPlayHid]
 * (usage list: 0=none, 1=Play, 2=Pause, 3=Play/Pause, 4=Next, 5=Previous).
 *
 * Hardware keys of the head unit are translated into these presses and sent to the iPhone with
 * [AirPlaySession.sendMedia]. A hardware play/pause key maps to the TOGGLE (3) rather than an
 * explicit play or pause: the firmware usually rewrites the key from its own idea of the play
 * state, and a wrong guess would make the button do nothing.
 */
object CarPlayButton {
    const val PLAY = 1
    const val PAUSE = 2
    const val PLAY_PAUSE = 3
    const val NEXT = 4
    const val PREVIOUS = 5

    /**
     * Leapmotor steering-wheel commands, as they arrive in the car's `car.meter.music.BROADCAST`
     * JSON payload (`data.action`), mapped to the CarPlay press. Unknown commands return null so a
     * future firmware command can never fire a random key.
     */
    fun forLeapmotorAction(action: String?): Int? = when (action?.trim()?.lowercase()) {
        "nextone", "next", "nexttrack" -> NEXT
        "preone", "previous", "prev", "previoustrack" -> PREVIOUS
        "playpause", "pauseplay", "toggleplay", "toggleplaypause" -> PLAY_PAUSE
        "play" -> PLAY
        "pause", "stop" -> PAUSE
        else -> null
    }
}
