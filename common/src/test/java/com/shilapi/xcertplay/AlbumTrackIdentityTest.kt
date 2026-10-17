package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test

class AlbumTrackIdentityTest {
    private val track = CarPlayNowPlaying(title = "song", artist = "artist", album = "album",
        durationMillis = 180_000, artworkTransferId = 1, elapsedMillis = 60_000, playing = true)

    @Test fun lyricAndPositionUpdatesKeepDecodedCover() {
        repeat(200) { assertFalse(albumTrackChanged(track, track.copy(title = "lyric $it", elapsedMillis = 60_000L + it * 500))) }
        assertFalse(albumTrackChanged(track, track.copy(elapsedMillis = 0)))
    }
    @Test fun realTrackChangesInvalidateOldCover() {
        assertTrue(albumTrackChanged(track, track.copy(artworkTransferId = null)))
        assertTrue(albumTrackChanged(track, track.copy(artworkTransferId = 2)))
        assertTrue(albumTrackChanged(track, track.copy(artist = "other")))
        assertTrue(albumTrackChanged(track, track.copy(durationMillis = 240_000)))
        assertTrue(albumTrackChanged(track, track.copy(title = "next", elapsedMillis = 0)))
    }
    @Test fun delayedFirstReferenceAndSourceAreAccepted() {
        assertFalse(albumTrackChanged(CarPlayNowPlaying(), track.copy(artworkTransferId = null)))
        assertTrue(albumTrackChanged(track.copy(artworkTransferId = null), track))
        assertFalse(albumTrackChanged(track.copy(sourceApp = null), track.copy(sourceApp = "Music")))
    }
}
