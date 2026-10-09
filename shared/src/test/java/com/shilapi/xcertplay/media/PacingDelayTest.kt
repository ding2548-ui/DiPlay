package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingDelayTest {
    private val ms = 1_000_000L

    @Test fun holdsTheStartingDelayUntilItHasMeasuredFrames() {
        val delay = PacingDelay(90 * ms)
        repeat(14) { delay.onFrame(150 * ms) }
        assertEquals(90 * ms, delay.nanos)
    }

    @Test fun settlesWhereNineInTenFramesAreReadyInTime() {
        val delay = PacingDelay(90 * ms)
        // Needs spread evenly over 40..99 ms: the 90th percentile is 93 ms, plus the 20 ms margin.
        repeat(1_000) { i -> delay.onFrame((40 + (i * 7) % 60) * ms) }
        assertEquals(113 * ms, delay.nanos)
    }

    @Test fun risesQuicklyAndFallsSlowly() {
        val delay = PacingDelay(90 * ms)
        var previous = delay.nanos
        // A 30 fps map: frames now need 150 ms.
        repeat(120) {
            delay.onFrame(150 * ms)
            assertTrue(delay.nanos - previous in 0..ms)
            previous = delay.nanos
        }
        assertEquals(170 * ms, delay.nanos)
        // Back to a 60 fps list that needs 60 ms: it waits until the slow frames are a tenth of the window,
        // then falls half a millisecond per frame.
        repeat(300) {
            delay.onFrame(60 * ms)
            assertTrue(previous - delay.nanos in 0..ms / 2)
            previous = delay.nanos
        }
        assertEquals(80 * ms, delay.nanos)
    }

    @Test fun aFewSlowFramesDoNotRaiseIt() {
        val delay = PacingDelay(80 * ms)
        repeat(1_000) { i -> delay.onFrame(if (i % 20 == 0) 400 * ms else 60 * ms) }
        assertEquals(80 * ms, delay.nanos)
    }

    @Test fun staysWithinItsBounds() {
        val high = PacingDelay(90 * ms)
        repeat(1_000) { high.onFrame(5_000 * ms) }
        assertEquals(200 * ms, high.nanos)
        val low = PacingDelay(90 * ms)
        repeat(1_000) { low.onFrame(0) }
        assertEquals(30 * ms, low.nanos)
        assertEquals(200 * ms, PacingDelay(500 * ms).nanos)
    }
}
