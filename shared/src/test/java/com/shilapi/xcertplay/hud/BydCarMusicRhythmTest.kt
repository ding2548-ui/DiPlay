package com.shilapi.xcertplay.hud

import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BydCarMusicRhythmTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val direct = FakeCall()
    private val helper = FakeCall()

    @Before fun useFakes() {
        BydCarMusicRhythm.setPlaying(context, false)
        settle()
        BydCarMusicRhythm.direct = direct
        BydCarMusicRhythm.helper = helper
    }

    @After fun restore() {
        BydCarMusicRhythm.setPlaying(context, false)
        settle()
        BydCarMusicRhythm.direct = BydCarMusicRhythmDirect
        BydCarMusicRhythm.helper = BydCarMusicRhythmHelper
    }

    @Test fun aDirectStartNeedsNoAdbAndIsStoppedDirectly() {
        play(true)
        assertEquals(1, direct.starts)
        assertEquals(0, helper.starts)
        play(false)
        assertEquals(1, direct.stops)
        assertEquals(0, helper.stops)
    }

    @Test fun aBlockedDirectCallFallsBackToTheHelperWhichAlsoStopsIt() {
        direct.works = false
        play(true)
        assertEquals(1, direct.starts)
        assertEquals(1, helper.starts)
        play(false)
        assertEquals(0, direct.stops)
        assertEquals(1, helper.stops)
    }

    @Test fun eachSessionIsStoppedTheWayItStarted() {
        direct.works = false
        play(true)
        play(false)
        // The direct call works again: the next session starts and stops directly, the helper is not touched.
        direct.works = true
        play(true)
        play(false)
        assertEquals(2, direct.starts)
        assertEquals(1, direct.stops)
        assertEquals(1, helper.starts)
        assertEquals(1, helper.stops)
    }

    @Test fun supportIsCheckedDirectlyBeforeOverAdb() {
        assertTrue(BydCarMusicRhythm.checkSupport(context).get(2, TimeUnit.SECONDS))
        assertEquals(0, helper.checks)

        direct.works = false
        assertTrue(BydCarMusicRhythm.checkSupport(context).get(2, TimeUnit.SECONDS))
        assertEquals(1, helper.checks)

        helper.works = false
        assertFalse(BydCarMusicRhythm.checkSupport(context).get(2, TimeUnit.SECONDS))
        assertEquals(0, direct.starts + helper.starts)
    }

    private fun play(value: Boolean) {
        BydCarMusicRhythm.setPlaying(context, value)
        settle()
    }

    private fun settle() {
        val executor = BydCarMusicRhythm::class.java.getDeclaredField("executor").apply { isAccessible = true }
            .get(BydCarMusicRhythm) as ExecutorService
        executor.submit {}.get(2, TimeUnit.SECONDS)
    }

    private class FakeCall : BydCarMusicRhythmCall {
        var works = true
        var checks = 0
        var starts = 0
        var stops = 0
        override fun supported(context: Context) = works.also { checks++ }
        override fun start(context: Context) = works.also { starts++ }
        override fun stop(context: Context) { stops++ }
    }
}
