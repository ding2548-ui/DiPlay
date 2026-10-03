package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** The retained subset of the iPhone's incremental iAP2 NowPlayingUpdate state. */
data class CarPlayNowPlaying(
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val artworkTransferId: Int? = null,
    val sourceApp: String? = null,
    val durationMillis: Long? = null,
    val elapsedMillis: Long? = null,
    val playing: Boolean = false,
)

/**
 * Retains NowPlayingUpdate (0x5001) fields requested by [Iap2ControlMessages]. Updates carry only
 * what changed, so an omitted field preserves its previous value while a present empty string
 * clears it.
 */
class CarPlayPlaybackStatus {
    var nowPlaying = CarPlayNowPlaying()
        private set

    val playing: Boolean get() = nowPlaying.playing

    /** The complete new retained state when this frame changed it, otherwise null. */
    fun acceptUpdate(frame: Iap2Frame): CarPlayNowPlaying? {
        if (frame.messageId != NOW_PLAYING_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        val media = runCatching { body.optionalGroup(MEDIA_ITEM) }.getOrNull()
        val playback = runCatching { body.optionalGroup(PLAYBACK) }.getOrNull()
        val previous = nowPlaying
        val next = previous.copy(
            title = media.updatedString(TITLE, previous.title),
            album = media.updatedString(ALBUM, previous.album),
            artist = media.updatedString(ARTIST, previous.artist),
            artworkTransferId = media.updatedU8(ARTWORK_TRANSFER_ID, previous.artworkTransferId),
            sourceApp = playback.updatedString(SOURCE_APP, previous.sourceApp),
            durationMillis = media.updatedU32(DURATION, previous.durationMillis),
            elapsedMillis = playback.updatedU32(ELAPSED, previous.elapsedMillis),
            playing = playback.updatedStatus(previous.playing),
        )
        if (next == previous) return null
        nowPlaying = next
        return next
    }

    /** The new state when [frame] changed it, otherwise null. */
    fun accept(frame: Iap2Frame): Boolean? {
        val previous = playing
        val next = acceptUpdate(frame)?.playing ?: return null
        return next.takeIf { it != previous }
    }

    /** The session ended: nothing plays any more. */
    fun clear(): Boolean? {
        if (!playing) return null
        nowPlaying = nowPlaying.copy(playing = false)
        return false
    }

    /** Clears all retained metadata at the end of a CarPlay session. */
    fun clearAll(): CarPlayNowPlaying? {
        if (nowPlaying == CarPlayNowPlaying()) return null
        nowPlaying = CarPlayNowPlaying()
        return nowPlaying
    }

    private fun Iap2BodyReader?.updatedString(id: Int, previous: String?): String? {
        if (this == null || !has(id)) return previous
        return runCatching { string(id).trim().ifEmpty { null } }.getOrElse { previous }
    }

    private fun Iap2BodyReader?.updatedU32(id: Int, previous: Long?): Long? {
        if (this == null || !has(id)) return previous
        return runCatching { u32(id) }.getOrElse { previous }
    }

    private fun Iap2BodyReader?.updatedU8(id: Int, previous: Int?): Int? {
        if (this == null || !has(id)) return previous
        return runCatching { u8(id) }.getOrElse { previous }
    }

    private fun Iap2BodyReader?.updatedStatus(previous: Boolean): Boolean {
        if (this == null || !has(STATUS)) return previous
        return runCatching { u8(STATUS) == STATUS_PLAYING }.getOrElse { previous }
    }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        private const val MEDIA_ITEM = 0
        private const val PLAYBACK = 1

        private const val TITLE = 1
        private const val DURATION = 4
        private const val ALBUM = 6
        private const val ARTIST = 12
        private const val ARTWORK_TRANSFER_ID = 26

        private const val STATUS = 0
        private const val ELAPSED = 1
        private const val SOURCE_APP = 7
        private const val STATUS_PLAYING = 1
    }
}
