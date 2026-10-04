package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.media.MediaMetadata
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Upstream 0.2.11: the iPhone repeats NowPlayingUpdate about twice a second for the position
 * alone; only a real metadata change may republish the media session metadata (and with it the
 * artwork bitmap) to every media listener.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayAudioOwnershipMetadataTest {
    @Test
    fun positionAndPlayStateDoNotRepublishMetadata() {
        val song = CarPlayNowPlaying(
            title = "Song",
            artist = "Artist",
            artworkTransferId = 7,
            elapsedMillis = 1_000,
            playing = true,
        )
        assertEquals(false, CarPlayAudioOwnership.metadataChanged(song, song.copy(elapsedMillis = 1_450)))
        assertEquals(false, CarPlayAudioOwnership.metadataChanged(song, song.copy(playing = false)))
        assertEquals(true, CarPlayAudioOwnership.metadataChanged(song, song.copy(title = "Next")))
        assertEquals(true, CarPlayAudioOwnership.metadataChanged(song, song.copy(artworkTransferId = 8)))
        assertEquals(true, CarPlayAudioOwnership.metadataChanged(CarPlayNowPlaying(), song))
    }

    @Test
    fun nowPlayingFieldsBecomeAndroidMediaMetadata() {
        val artwork = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val metadata = CarPlayAudioOwnership.androidMetadata(
            CarPlayNowPlaying(
                title = "Dreams",
                album = "Rumours",
                artist = "Fleetwood Mac",
                sourceApp = "Music",
                durationMillis = 257_000,
            ),
            artwork,
        )

        assertEquals("Dreams", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("Dreams", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        assertEquals("Fleetwood Mac", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals("Fleetwood Mac", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
        assertEquals("Rumours", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))
        assertEquals("Music", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION))
        assertEquals(257_000L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        assertEquals(artwork, metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertEquals(artwork, metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
    }

    @Test
    fun emptyNowPlayingProducesEmptyMetadata() {
        val metadata = CarPlayAudioOwnership.androidMetadata(CarPlayNowPlaying())
        assertNull(metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertNull(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals(0L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
    }
}
