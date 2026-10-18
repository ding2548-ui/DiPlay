package com.shilapi.xcertplay

import android.view.KeyEvent
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WheelKeyLogTest {
    private val locale = Locale.getDefault()
    private val zone = TimeZone.getDefault()

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        WheelKeyLog.reset()
    }

    @After fun tearDown() {
        Locale.setDefault(locale)
        TimeZone.setDefault(zone)
        WheelKeyLog.reset()
    }

    private fun press(code: Int, kept: Boolean = false, detail: String? = null, at: Long = 0, scan: Int = 0) =
        WheelKeyLog.press(code, scan, 3, kept, detail, WheelKeyLog.writesText(code), at)

    @Test fun aServiceThatNeverRanSaysSo() {
        assertEquals(listOf("Wheel keys: the service has not run since DiPlay started"), WheelKeyLog.report(bound = false))
    }

    @Test fun aBoundServiceThatSawNoKeySaysSo() {
        WheelKeyLog.bound(1_000)
        assertEquals(
            listOf("Wheel keys since DiPlay started: presses=0 kept=0 leftToHeadUnit=0 textKeysNotListed=0 " +
                "boundForMs=4000; no key has reached the service"),
            WheelKeyLog.report(bound = true, nowMillis = 5_000),
        )
    }

    @Test fun listsEachPressWithItsCodesAndWhatBecameOfIt() {
        WheelKeyLog.bound(0)
        WheelKeyLog.note("wheel key service connected", 0)
        press(KeyEvent.KEYCODE_VOLUME_UP, at = 61_001, scan = 115, detail = "navigation volume not used: no guidance is speaking")
        press(292, kept = true, detail = "navigation volume", at = 62_002, scan = 114)
        assertEquals(
            listOf(
                "Wheel keys since DiPlay started: presses=2 kept=1 leftToHeadUnit=1 textKeysNotListed=0 boundForMs=70000",
                "Wheel keys: 00:00:00.000  wheel key service connected",
                "Wheel keys: 00:01:01.001  key 24/115 device 3 left to the head unit: navigation volume not used: no guidance is speaking",
                "Wheel keys: 00:01:02.002  key 292/114 device 3 kept: navigation volume",
            ),
            WheelKeyLog.report(bound = true, nowMillis = 70_000),
        )
    }

    @Test fun aStoppedServiceKeepsWhatItSawAndIsNotCalledBound() {
        WheelKeyLog.bound(0)
        press(KeyEvent.KEYCODE_MEDIA_NEXT, at = 10)
        val report = WheelKeyLog.report(bound = false, nowMillis = 9_000)
        assertTrue(report.first(), report.first().endsWith("textKeysNotListed=0 bound=false"))
        assertEquals(2, report.size)
    }

    @Test fun keysThatWriteTextAreCountedAndNeverListed() {
        WheelKeyLog.bound(0)
        val typed = listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_AT, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_SLASH,
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_NUMPAD_5, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_YEN)
        for (code in typed) {
            assertTrue("key $code", WheelKeyLog.writesText(code))
            press(code, at = 1)
        }
        assertEquals(
            listOf("Wheel keys since DiPlay started: presses=0 kept=0 leftToHeadUnit=0 " +
                "textKeysNotListed=${typed.size} boundForMs=5"),
            WheelKeyLog.report(bound = true, nowMillis = 5),
        )
    }

    @Test fun wheelMediaAndVendorKeysAreListed() {
        val wheel = listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F12, KeyEvent.KEYCODE_BUTTON_1,
            289, 291, 292, 304, 305, 307, 308, 313, 327, 328, 331, 353, GeelyWheelKeyCodes.KEY_VOICE_ASSIST)
        for (code in wheel) assertFalse("key $code", WheelKeyLog.writesText(code))
        for (role in WheelZoomSettings.Role.entries) {
            role.defaultKey?.let { assertFalse("$role", WheelKeyLog.writesText(it.code)) }
        }
    }

    @Test fun aKeySentAsManyPressesIsOneEntryWithItsCount() {
        WheelKeyLog.bound(0)
        for (at in 100L..500L step 100) press(KeyEvent.KEYCODE_VOLUME_UP, at = at)
        press(KeyEvent.KEYCODE_VOLUME_DOWN, at = 600)
        press(KeyEvent.KEYCODE_VOLUME_UP, at = 700)
        assertEquals(
            listOf(
                "Wheel keys since DiPlay started: presses=7 kept=0 leftToHeadUnit=7 textKeysNotListed=0 boundForMs=800",
                "Wheel keys: 00:00:00.100  key 24/0 device 3 left to the head unit x5, last 00:00:00.500",
                "Wheel keys: 00:00:00.600  key 25/0 device 3 left to the head unit",
                "Wheel keys: 00:00:00.700  key 24/0 device 3 left to the head unit",
            ),
            WheelKeyLog.report(bound = true, nowMillis = 800),
        )
    }

    @Test fun keepsTheNewestEntriesAndEveryCount() {
        WheelKeyLog.bound(0)
        val total = WheelKeyLog.ENTRIES * 3
        for (index in 0 until total) press(1_000 + index, kept = index % 2 == 0, at = index.toLong())
        val report = WheelKeyLog.report(bound = true, nowMillis = total.toLong())
        assertEquals(WheelKeyLog.ENTRIES + 1, report.size)
        assertTrue(report.first(), report.first().contains("presses=$total kept=${total / 2} leftToHeadUnit=${total / 2} "))
        assertTrue(report[1], report[1].contains(" key ${1_000 + total - WheelKeyLog.ENTRIES}/0 "))
        assertTrue(report.last(), report.last().contains(" key ${1_000 + total - 1}/0 "))
    }

    @Test fun digitsStayWesternWhateverTheHeadUnitsLanguage() {
        for (tag in listOf("ar-SA", "fa-IR", "hi-IN", "th-TH-u-nu-thai")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            WheelKeyLog.reset()
            WheelKeyLog.bound(0)
            press(KeyEvent.KEYCODE_VOLUME_UP, at = 3_723_004, scan = 115)
            press(KeyEvent.KEYCODE_VOLUME_UP, at = 3_723_005, scan = 115)
            assertEquals(
                tag,
                listOf(
                    "Wheel keys since DiPlay started: presses=2 kept=0 leftToHeadUnit=2 textKeysNotListed=0 boundForMs=3723006",
                    "Wheel keys: 01:02:03.004  key 24/115 device 3 left to the head unit x2, last 01:02:03.005",
                ),
                WheelKeyLog.report(bound = true, nowMillis = 3_723_006),
            )
        }
    }

    @Test fun pressesFromManyThreadsAreAllCountedWhileTheReportIsRead() {
        WheelKeyLog.bound(0)
        val threads = 8
        val each = 5_000
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val writers = (0 until threads).map { index ->
            thread {
                runCatching {
                    start.await()
                    repeat(each) { WheelKeyLog.press(500 + index, it, index, it % 4 == 0, null, it % 5 == 0, it.toLong()) }
                }.onFailure(failure::set)
            }
        }
        val reader = thread {
            runCatching {
                start.await()
                repeat(2_000) { assertTrue(WheelKeyLog.report(bound = true).size <= WheelKeyLog.ENTRIES + 1) }
            }.onFailure(failure::set)
        }
        start.countDown()
        (writers + reader).forEach { it.join() }
        failure.get()?.let { throw it }
        // Every fifth press writes text; of the others, every fourth is kept (multiples of 20 are both).
        val listed = threads * (each - each / 5)
        val kept = threads * (each / 4 - each / 20)
        assertTrue(WheelKeyLog.report(bound = true).first().startsWith(
            "Wheel keys since DiPlay started: presses=$listed kept=$kept leftToHeadUnit=${listed - kept} " +
                "textKeysNotListed=${threads * (each / 5)} ",
        ))
    }
}
