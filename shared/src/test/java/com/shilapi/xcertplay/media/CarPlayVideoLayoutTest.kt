package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class CarPlayVideoLayoutTest {
    @Test fun sameAspectRatioFillsTheWindow() {
        assertEquals(CarPlayVideoLayout(0f, 0f, 960f, 495f), CarPlayVideoLayout.fit(1920, 990, 960, 495))
    }

    @Test fun reducedHeightAddsSideBarsWithoutDistortion() {
        val content = CarPlayVideoLayout.fit(1920, 990, 1920, 942)
        assertEquals(942f, content.height, 0.001f)
        assertEquals(0f, content.top, 0.001f)
        assertEquals((1920f - content.width) / 2, content.left, 0.001f)
        assertEquals(1920f / 990, content.width / content.height, 0.0001f)
        assertFalse(content.contains(1f, 471f))
        assertTrue(content.contains(960f, 471f))
    }

    @Test fun narrowSurroundViewWindowAddsTopAndBottomBars() {
        val content = CarPlayVideoLayout.fit(1920, 990, 700, 990)
        assertEquals(700f, content.width, 0.001f)
        assertEquals(0f, content.left, 0.001f)
        assertEquals((990f - content.height) / 2, content.top, 0.001f)
        assertEquals(1920f / 990, content.width / content.height, 0.0001f)
        assertFalse(content.contains(350f, 1f))
    }

    @Test fun returningToTheOriginalWindowRemovesTheBars() {
        CarPlayVideoLayout.fit(1920, 990, 700, 990)
        assertEquals(CarPlayVideoLayout(0f, 0f, 1920f, 990f), CarPlayVideoLayout.fit(1920, 990, 1920, 990))
    }
    @Test fun smallAspectDifferencesStillPreserveTheCanvasAndTouchBounds() {
        val layout = CarPlayVideoLayout.fit(1920, 990, 1920, 970)
        assertEquals(1920f / 990f, layout.width / layout.height, 0.0001f)
        assertTrue(layout.left > 0)
        assertFalse(layout.contains(0f, 485f))
    }

}
