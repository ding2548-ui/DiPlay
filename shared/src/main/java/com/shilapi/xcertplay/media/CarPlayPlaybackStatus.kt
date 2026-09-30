package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * Whether the iPhone reports media as playing, from the playback status in iAP2 NowPlayingUpdate
 * (0x5001), which DiPlay already subscribes to. The update carries only what changed, so frames
 * without a status leave the state as it is.
 */
class CarPlayPlaybackStatus {
    var playing = false
        private set

    /** The new state when [frame] changed it, otherwise null. */
    fun accept(frame: Iap2Frame): Boolean? {
        if (frame.messageId != NOW_PLAYING_UPDATE) return null
        val status = runCatching {
            Iap2BodyReader.of(frame).optionalGroup(PLAYBACK)?.optionalU8(STATUS)
        }.getOrNull() ?: return null
        val next = status == STATUS_PLAYING
        if (next == playing) return null
        playing = next
        return next
    }

    /** The session ended: nothing plays any more. */
    fun clear(): Boolean? {
        if (!playing) return null
        playing = false
        return false
    }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        private const val PLAYBACK = 1
        private const val STATUS = 0
        private const val STATUS_PLAYING = 1
    }
}
