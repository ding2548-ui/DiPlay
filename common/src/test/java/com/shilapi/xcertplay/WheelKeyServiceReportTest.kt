package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import com.shilapi.xcertplay.media.NavigationPlaybackSnapshot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** What the diagnostic report says about the keys the wheel key service was given. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelKeyServiceReportTest {
    private lateinit var service: WheelKeyService
    private lateinit var audio: AudioManager
    private var time = 0L

    @Before fun setUp() {
        WheelKeyLog.reset()
        service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        service.getSharedPreferences("diplay_navigation_wheel", Context.MODE_PRIVATE).edit().clear().commit()
        service.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        audio = service.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_NORMAL
    }

    @After fun tearDown() {
        service.onDestroy()
        CarPlayBackgroundSession.clear()
        WheelKeyLog.reset()
    }

    private fun send(code: Int, action: Int, repeat: Int = 0, downTime: Long = time): Boolean = service.javaClass
        .getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
        .invoke(service, KeyEvent(downTime, downTime, action, code, repeat, 0, 7, 0)) as Boolean

    /** A new press; true when DiPlay kept its first DOWN. */
    private fun press(code: Int): Boolean {
        time += 1_000
        val kept = send(code, KeyEvent.ACTION_DOWN)
        send(code, KeyEvent.ACTION_DOWN, repeat = 1)
        send(code, KeyEvent.ACTION_UP)
        return kept
    }

    private fun report(): List<String> = WheelKeyLog.report(WheelKeyService.connected())

    /** The report's entries without their time of day. */
    private fun entries(): List<String> = report().drop(1).map { it.substringAfter("  ") }

    @Test fun aKeyNoFeatureUsesIsListedOnceAndStaysWithTheHeadUnit() {
        assertFalse(press(KeyEvent.KEYCODE_F7))
        assertEquals(
            listOf("wheel key service connected", "key 137/0 device 7 left to the head unit"),
            entries(),
        )
        assertTrue(report().first(), report().first().startsWith(
            "Wheel keys since DiPlay started: presses=1 kept=0 leftToHeadUnit=1 textKeysNotListed=0 boundForMs="))
    }

    @Test fun aKeyDiPlayKeepsIsListedWithWhatItDid() {
        service.mapRoute = { "map" }
        WheelZoomSettings.setEnabled(service, true)
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.MODE, WheelKey(KeyEvent.KEYCODE_F1, 0, "?"))
        assertTrue(press(KeyEvent.KEYCODE_F1))
        assertEquals(
            listOf("wheel key service connected", "zoom mode on", "key 131/0 device 7 kept"),
            entries(),
        )
        assertTrue(report().first(), report().first().contains(" presses=1 kept=1 leftToHeadUnit=0 "))
    }

    @Test fun aLearnedKeyAndTheSiriKeyAreInTheReport() {
        var asked = 0
        service.session = { "phone" }
        service.requestSiri = { asked++; true }
        WheelZoomSettings.setSiriKey(service, true)
        assertTrue(WheelKeyService.learn(WheelZoomSettings.Role.SIRI) { _, _ -> })
        assertTrue(press(KeyEvent.KEYCODE_F9))
        assertTrue(press(KeyEvent.KEYCODE_F9))
        assertEquals(1, asked)
        assertEquals(
            listOf("wheel key service connected", "SIRI key is now 139/0", "key 139/0 device 7 kept",
                "Siri key sent=true", "key 139/0 device 7 kept"),
            entries(),
        )
    }

    @Test fun aVolumeKeySaysWhyNavigationVolumeLeftItToTheHeadUnit() {
        fun left(reason: String) {
            assertFalse(reason, press(KeyEvent.KEYCODE_VOLUME_UP))
            assertEquals("key 24/0 device 7 left to the head unit: navigation volume not used: $reason",
                entries().last().substringBefore(" x"))
        }
        var playback = NavigationPlaybackSnapshot(false, null)
        service.navigationPlayback = { playback }
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 2, 0)
        left("its setting is off")
        NavigationWheelSettings.setEnabled(service, true)
        left("no CarPlay session")
        // The first reason that applies is the one reported: a call comes before a missing session.
        audio.mode = AudioManager.MODE_IN_CALL
        left("a call is active")
        service.session = { "phone" }
        left("a call is active")
        audio.mode = AudioManager.MODE_NORMAL
        left("no guidance is speaking")
        playback = NavigationPlaybackSnapshot(true, AudioManager.STREAM_NOTIFICATION, priorityVoiceActive = true)
        left("Siri or a call is speaking")
        playback = NavigationPlaybackSnapshot(true, AudioManager.STREAM_MUSIC)
        left("navigation audio stream 3 has no volume of its own")
        playback = NavigationPlaybackSnapshot(true, null)
        left("navigation audio stream automatic has no volume of its own")
        playback = NavigationPlaybackSnapshot(true, AudioManager.STREAM_NOTIFICATION)
        shadowOf(audio).setStreamMaxVolume(1)
        left("stream 5 did not take the change")
        shadowOf(audio).setStreamMaxVolume(15)

        assertTrue(press(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals("key 24/0 device 7 kept: navigation volume", entries().last())
        // The press and its one repeat each moved the navigation volume a step.
        assertEquals(4, audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
        assertTrue(report().first(), report().first().contains(" presses=10 kept=1 leftToHeadUnit=9 "))
        // One entry for each different outcome (the two presses during the call share one), and the
        // redactor dropped none of them.
        assertEquals(1 + 9, entries().size)
        assertTrue(entries()[3], entries()[3].startsWith("key 24/0 device 7 left to the head unit: navigation volume not used: a call is active x2, last "))
    }

    @Test fun typedTextIsCountedAndItsKeysAreNotInTheReport() {
        for (code in listOf(KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER)) {
            assertFalse(press(code))
        }
        assertFalse(press(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(
            listOf("wheel key service connected", "key 87/0 device 7 left to the head unit"),
            entries(),
        )
        assertTrue(report().first(), report().first().contains(" presses=1 kept=0 leftToHeadUnit=1 textKeysNotListed=4 "))
    }

    @Test fun aHeldKeysRepeatsAndReleaseAddNothing() {
        time = 5_000
        assertFalse(send(KeyEvent.KEYCODE_F7, KeyEvent.ACTION_DOWN))
        for (repeat in 1..30) assertFalse(send(KeyEvent.KEYCODE_F7, KeyEvent.ACTION_DOWN, repeat))
        assertFalse(send(KeyEvent.KEYCODE_F7, KeyEvent.ACTION_UP))
        assertEquals(listOf("wheel key service connected", "key 137/0 device 7 left to the head unit"), entries())
        assertTrue(report().first(), report().first().contains(" presses=1 "))
    }

    @Test fun aServiceThatStopsLeavesItsLastKeysAndSaysItStopped() {
        assertFalse(press(KeyEvent.KEYCODE_F7))
        service.onUnbind(Intent())
        assertFalse(WheelKeyService.connected())
        assertEquals(
            listOf("wheel key service connected", "key 137/0 device 7 left to the head unit", "wheel key service unbound"),
            entries(),
        )
        assertTrue(report().first(), report().first().endsWith(" textKeysNotListed=0 bound=false"))
    }

    @Test fun theDiagnosticReportCarriesTheWheelKeyLines() {
        assertFalse(press(KeyEvent.KEYCODE_F7))
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val text = activity.javaClass.getDeclaredMethod("diagnosticReportText", Context::class.java)
            .apply { isAccessible = true }.invoke(activity, service.applicationContext) as String
        val lines = text.lines()
        val serviceLine = lines.indexOfFirst { it.startsWith("Wheel key service: ") }
        assertTrue(text, serviceLine >= 0)
        assertTrue(lines[serviceLine + 1], lines[serviceLine + 1].startsWith("Wheel keys since DiPlay started: presses=1 "))
        assertTrue(lines[serviceLine + 2], lines[serviceLine + 2].endsWith("  wheel key service connected"))
        assertTrue(lines[serviceLine + 3], lines[serviceLine + 3].endsWith("  key 137/0 device 7 left to the head unit"))
        assertEquals("", lines[serviceLine + 4])
    }
}
