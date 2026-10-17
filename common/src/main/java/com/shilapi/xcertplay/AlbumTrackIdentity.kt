package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** Lyrics can occupy title. A title change alone must not invalidate the decoded cover. */
internal fun albumTrackChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean {
    if (previous.artworkTransferId != next.artworkTransferId) return true
    fun changed(old: Any?, new: Any?) = old != null && old != new
    if (changed(previous.sourceApp, next.sourceApp) || changed(previous.artist, next.artist) ||
        changed(previous.album, next.album) || changed(previous.durationMillis, next.durationMillis)) return true
    // Same album/reference is common. A new title plus a large position restart identifies it;
    // an ordinary lyric update or a seek without a title change keeps the existing palette.
    return previous.title != next.title && previous.elapsedMillis?.let { it > 15_000 } == true &&
        next.elapsedMillis?.let { it < 3_000 } == true
}
