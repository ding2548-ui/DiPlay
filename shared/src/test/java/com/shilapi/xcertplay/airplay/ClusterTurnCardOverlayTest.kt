package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterTurnCardOverlayTest {
    @Test
    fun manualOffsetsReachThePanelEdges() {
        val left = ClusterTurnCardOverlay.card(
            1920, 720, 10, 28, 40,
        )
        val right = ClusterTurnCardOverlay.card(
            1920, 720, 90, 28, 40,
        )
        assertTrue(left.left < 1920 * 0.12)
        assertTrue(right.left + right.width > 1920 * 0.88)
        assertTrue(right.left - left.left > 1920 * 0.45)
    }

    @Test
    fun defaultSitsOnTheRightOfThePanel() {
        val card = ClusterTurnCardOverlay.card(
            1920, 720,
            ClusterTurnCardOverlay.DEFAULT_X_PERCENT,
            ClusterTurnCardOverlay.DEFAULT_Y_PERCENT,
            55,
        )
        assertTrue(card.left > 1920 / 2)
        assertTrue(card.top < 720 / 2)
        assertTrue(card.left + card.width <= 1920)
        assertTrue(card.top + card.height <= 720)
    }

    @Test
    fun largeCardDoesNotCoverTheWholePanel() {
        val large = ClusterTurnCardOverlay.card(
            1920, 720, 75, 28, 75,
        )
        assertTrue(large.width < 1920 / 2)
        assertTrue(large.height < 720 / 2)
    }

    @Test
    fun percentStepsStayOnThePanel() {
        for (x in ClusterTurnCardOverlay.xPercents) {
            for (y in ClusterTurnCardOverlay.yPercents) {
                val card = ClusterTurnCardOverlay.card(
                    1920, 720, x, y, 55,
                )
                assertTrue(card.left >= 0)
                assertTrue(card.top >= 0)
                assertTrue(card.left + card.width <= 1920)
                assertTrue(card.top + card.height <= 720)
            }
        }
        assertEquals(76, ClusterTurnCardOverlay.snap(76, ClusterTurnCardOverlay.xPercents))
        assertEquals(2, ClusterTurnCardOverlay.STEP_PERCENT)
    }
}
