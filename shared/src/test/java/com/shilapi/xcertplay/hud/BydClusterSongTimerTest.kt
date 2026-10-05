package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class BydClusterSongTimerTest {
    private val type = BydClusterSong::class.java
    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private fun get(name: String) = field(name).get(BydClusterSong)
    private fun set(name: String, value: Any?) = field(name).set(BydClusterSong, value)
    private fun wanted() = get("wanted") as ClusterSong?

    private fun song(title: String = "Track") = BydClusterSong.onFrame(
        Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) {
            group(0) { string(1, title); string(12, "Artist") }
        },
    )

    private fun playback(playing: Boolean) = BydClusterSong.onFrame(
        Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) {
            group(1) { u8(0, if (playing) 1 else 2) }
        },
    )

    private fun expiry(name: String, app: Context, token: Any, alreadyShown: ClusterSong?) {
        // The real writer is blocked below. Pretend the expected output has already been sent,
        // so the actual expiry callback exercises its state transitions without contacting ADB.
        set("shown", alreadyShown)
        type.getDeclaredMethod(name, Context::class.java, Any::class.java)
            .apply { isAccessible = true }.invoke(BydClusterSong, app, token)
    }

    private fun expireChange(app: Context, token: Any) =
        expiry("endChange", app, token, get("EMPTY") as ClusterSong)

    private fun withWriterBlocked(check: (Context) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val writer = get("writer") as ScheduledExecutorService
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        writer.execute { entered.countDown(); release.await() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            BydClusterSong.attach(app)
            BydOutputSettings.setClusterSong(app, true)
            BydOutputSettings.setClusterSongOnChange(app, true)
            (get("state") as ClusterSongState).clear()
            set("note", null)
            set("onChange", null)
            set("announced", null)
            set("wanted", null)
            set("shown", null)
            check(app)
        } finally {
            (get("state") as ClusterSongState).clear()
            set("note", null)
            set("onChange", null)
            set("announced", null)
            set("wanted", null)
            set("shown", null)
            set("context", null)
            BydOutputSettings.setClusterSong(app, false)
            BydOutputSettings.setClusterSongOnChange(app, false)
            release.countDown()
            writer.submit {}.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun persistentModeCannotBeBlankedByAnOldSongTimer() = withWriterBlocked { app ->
        song()
        val token = get("onChange")!!
        val current = BydClusterSong.current()
        BydOutputSettings.setClusterSongOnChange(app, false)
        BydClusterSong.onChangeSettingChanged()
        assertNull(get("onChange"))
        expireChange(app, token)
        assertEquals(current, wanted())
    }

    @Test fun disablingSongOutputInvalidatesPendingSongAndNoteTimers() = withWriterBlocked { app ->
        song()
        val songToken = get("onChange")!!
        BydClusterSong.note("Zoom")
        val noteToken = get("note")!!
        BydOutputSettings.setClusterSong(app, false)
        BydClusterSong.settingChanged(false)
        assertNull(get("onChange"))
        assertNull(get("note"))
        expireChange(app, songToken)
        expiry("endNote", app, noteToken, null)
        assertNull(wanted())
    }

    @Test fun disableAndReenableGivesTheSongANewFullWindow() = withWriterBlocked { app ->
        song()
        val oldToken = get("onChange")!!
        BydOutputSettings.setClusterSong(app, false)
        BydClusterSong.settingChanged(false)
        BydOutputSettings.setClusterSong(app, true)
        BydClusterSong.settingChanged(true)
        val newToken = get("onChange")!!
        assertNotSame(oldToken, newToken)
        expireChange(app, oldToken)
        assertSame(newToken, get("onChange"))
        assertEquals(BydClusterSong.current(), wanted())
        expireChange(app, newToken)
        assertEquals(get("EMPTY"), wanted())
    }

    @Test fun aNewSongRejectsThePreviousSongsExpiry() = withWriterBlocked { app ->
        song("First")
        val oldToken = get("onChange")!!
        song("Second")
        val newToken = get("onChange")!!
        expireChange(app, oldToken)
        assertSame(newToken, get("onChange"))
        assertEquals(BydClusterSong.current(), wanted())
    }

    @Test fun playbackAndDuplicateUpdatesDoNotExtendOrRepeatTheWindow() = withWriterBlocked { app ->
        song()
        val token = get("onChange")!!
        song()
        playback(true)
        assertSame(token, get("onChange"))
        assertTrue(wanted()!!.playing)
        expireChange(app, token)
        assertEquals(get("EMPTY"), wanted())
        playback(false)
        assertNull(get("onChange"))
        assertEquals(get("EMPTY"), wanted())
    }

    @Test fun sessionEndRejectsOldSongAndNoteCallbacksEvenAfterANewSongArrives() = withWriterBlocked { app ->
        song("Old")
        val oldSongToken = get("onChange")!!
        BydClusterSong.note("Old zoom note")
        val oldNoteToken = get("note")!!
        BydClusterSong.end()
        assertNull(BydClusterSong.current())
        assertNull(get("note"))
        assertNull(wanted())
        song("New")
        val current = BydClusterSong.current()
        val newSongToken = get("onChange")!!
        expireChange(app, oldSongToken)
        expiry("endNote", app, oldNoteToken, current)
        assertSame(newSongToken, get("onChange"))
        assertEquals(current, wanted())
    }

    @Test fun songUpdatesKeepTheNoteVisibleAndRestoreTheLatestSong() = withWriterBlocked { app ->
        song("First")
        BydClusterSong.note("Zoom", source = 7)
        val noteToken = get("note")!!
        val card = wanted()
        song("Second")
        playback(true)
        assertEquals(card, wanted())
        assertEquals(7, wanted()!!.source)
        val current = BydClusterSong.current()
        expiry("endNote", app, noteToken, current)
        assertEquals(current, wanted())
        assertTrue(wanted()!!.playing)
    }

    @Test fun songExpiryKeepsTheNoteAndNoteExpiryLeavesAnEmptyCard() = withWriterBlocked { app ->
        song()
        val songToken = get("onChange")!!
        BydClusterSong.note("Zoom")
        val noteToken = get("note")!!
        val card = wanted()
        expireChange(app, songToken)
        assertEquals(card, wanted())
        expiry("endNote", app, noteToken, get("EMPTY") as ClusterSong)
        assertEquals(get("EMPTY"), wanted())
    }

    @Test fun anOlderNoteCannotDismissANewerNote() = withWriterBlocked { app ->
        song()
        BydClusterSong.note("First note")
        val oldToken = get("note")!!
        BydClusterSong.note("Second note")
        val newToken = get("note")!!
        val card = wanted()
        expiry("endNote", app, oldToken, card)
        assertSame(newToken, get("note"))
        assertEquals(card, wanted())
    }

    @Test fun changingToPersistentModeKeepsTheNoteThenRestoresTheSong() = withWriterBlocked { app ->
        song()
        val songToken = get("onChange")!!
        BydClusterSong.note("Zoom")
        val noteToken = get("note")!!
        val card = wanted()
        BydOutputSettings.setClusterSongOnChange(app, false)
        BydClusterSong.onChangeSettingChanged()
        expireChange(app, songToken)
        assertEquals(card, wanted())
        val current = BydClusterSong.current()
        expiry("endNote", app, noteToken, current)
        assertEquals(current, wanted())
    }

    @Test fun timerChecksSavedModeBeforeTheSettingCallbackRuns() = withWriterBlocked { app ->
        song()
        val token = get("onChange")!!
        val current = BydClusterSong.current()
        BydOutputSettings.setClusterSongOnChange(app, false)
        expireChange(app, token)
        assertEquals(current, wanted())
    }

    @Test fun timerChecksSavedEnableBeforeTheSettingCallbackRuns() = withWriterBlocked { app ->
        song()
        val token = get("onChange")!!
        val current = BydClusterSong.current()
        BydOutputSettings.setClusterSong(app, false)
        expireChange(app, token)
        assertEquals(current, wanted())
        BydClusterSong.settingChanged(false)
        assertNull(wanted())
    }
}
