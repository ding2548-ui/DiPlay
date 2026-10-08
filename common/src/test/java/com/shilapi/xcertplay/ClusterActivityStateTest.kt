package com.shilapi.xcertplay

import com.shilapi.xcertplay.DiLink51ClusterLayout.Theme
import org.junit.Assert.*
import org.junit.Test

class ClusterActivityStateTest {
    private val state = ClusterActivityState()
    private fun event(name: String, type: Int, instance: Int = 1, time: Long = 100) {
        val pkg = when (name) {
            ClusterActivityState.SCENARIO -> "com.byd.sr"
            ClusterActivityState.SIMPLE -> "com.byd.cluster"
            else -> "com.byd.launchermap"
        }
        state.event(pkg, name, instance, type, time)
    }

    @Test fun scenarioOnlyShowsTheMiniMapWhileItsCardIsVisible() {
        event(ClusterActivityState.SCENARIO, 1)
        assertEquals(ClusterActivityState.Snapshot(Theme.SCENARIO, false), state.snapshot())
        event(ClusterActivityState.MINI_MAP, 1)
        assertTrue(state.snapshot().mapVisible)
        event(ClusterActivityState.MINI_MAP, 23)
        assertEquals(ClusterActivityState.Snapshot(Theme.SCENARIO, false), state.snapshot())
    }

    @Test fun switchingToMapDoesNotRetainTheSideLayout() {
        event(ClusterActivityState.SCENARIO, 1)
        event(ClusterActivityState.MINI_MAP, 1)
        event(ClusterActivityState.FULL_MAP, 1, time = 200)
        assertEquals(ClusterActivityState.Snapshot(Theme.MAP, true), state.snapshot())
        event(ClusterActivityState.SCENARIO, 23, time = 201)
        event(ClusterActivityState.MINI_MAP, 23, time = 202)
        assertEquals(ClusterActivityState.Snapshot(Theme.MAP, true), state.snapshot())
    }

    @Test fun switchingFromMapToSimpleWithoutAMapCardHidesTheOverlay() {
        event(ClusterActivityState.FULL_MAP, 1)
        event(ClusterActivityState.SIMPLE, 1, time = 200)
        assertEquals(ClusterActivityState.Snapshot(Theme.SIMPLE, false), state.snapshot())
        event(ClusterActivityState.MINI_MAP, 1, time = 210)
        assertEquals(ClusterActivityState.Snapshot(Theme.SIMPLE, true, true), state.snapshot())
    }

    @Test fun smallWindowFollowsTheNewestVisibleMapActivity() {
        event(ClusterActivityState.MINI_MAP, 1)
        assertTrue(state.snapshot().smallWindow)
        event(ClusterActivityState.FULL_MAP, 1, time = 200)
        assertFalse(state.snapshot().smallWindow)
        event(ClusterActivityState.MINI_MAP, 1, time = 300)
        assertTrue(state.snapshot().smallWindow)
        event(ClusterActivityState.MINI_MAP, 23, time = 301)
        assertFalse(state.snapshot().smallWindow)
    }

    @Test fun smallWindowAloneRemainsVisibleAndSwitchesBothDirections() {
        event(ClusterActivityState.MINI_MAP, 1, time = 100)
        assertEquals(ClusterActivityState.Snapshot(null, true, true), state.snapshot())
        event(ClusterActivityState.FULL_MAP, 1, time = 200)
        assertEquals(ClusterActivityState.Snapshot(Theme.MAP, true, false), state.snapshot())
        event(ClusterActivityState.MINI_MAP, 1, time = 300)
        assertEquals(ClusterActivityState.Snapshot(Theme.MAP, true, true), state.snapshot())
    }

    @Test fun coveredMapOnlyReconnectsWhenItReturnsInADifferentWindowMode() {
        val small = ClusterActivityState.Snapshot(Theme.SCENARIO, true, true)
        val covered = ClusterActivityState.Snapshot(Theme.SCENARIO, false, false)
        val full = ClusterActivityState.Snapshot(Theme.MAP, true, false)
        assertFalse(ClusterActivityState.shouldReconnectSmallWindowStream(small, covered, true))
        assertTrue(ClusterActivityState.shouldReconnectSmallWindowStream(covered, full, true))
        assertFalse(ClusterActivityState.shouldReconnectSmallWindowStream(covered, small, true))
        assertFalse(ClusterActivityState.shouldReconnectSmallWindowStream(full, full, true))
        assertTrue(ClusterActivityState.shouldReconnectSmallWindowStream(full, small, false))
    }

    @Test fun pausedButVisibleClusterSurvivesFocusMovingToTheHeadUnit() {
        event(ClusterActivityState.FULL_MAP, 1)
        event(ClusterActivityState.FULL_MAP, 2)
        state.event("com.example.music", "PlayerActivity", 9, 1, 300)
        assertEquals(ClusterActivityState.Snapshot(Theme.MAP, true), state.snapshot())
    }

    @Test fun stoppingAnOldInstanceDoesNotHideItsReplacement() {
        event(ClusterActivityState.SCENARIO, 1)
        event(ClusterActivityState.MINI_MAP, 1, instance = 1)
        event(ClusterActivityState.MINI_MAP, 1, instance = 2, time = 200)
        event(ClusterActivityState.MINI_MAP, 23, instance = 1, time = 201)
        assertTrue(state.snapshot().mapVisible)
        event(ClusterActivityState.MINI_MAP, 23, instance = 2, time = 202)
        assertFalse(state.snapshot().mapVisible)
    }

    @Test fun unknownStateAndRestartCannotDisplayAStaleOverlay() {
        assertEquals(ClusterActivityState.Snapshot(null, false), state.snapshot())
        event(ClusterActivityState.FULL_MAP, 1)
        state.event(null, null, 0, 26, 200)
        assertEquals(ClusterActivityState.Snapshot(null, false), state.snapshot())
        event(ClusterActivityState.FULL_MAP, 1)
        state.event(null, null, 0, 27, 300)
        assertEquals(ClusterActivityState.Snapshot(null, false), state.snapshot())
    }

    @Test fun matchingClassNameFromAnotherPackageCannotSelectATheme() {
        state.event("unrelated.app", ClusterActivityState.FULL_MAP, 1, 1, 100)
        assertNull(state.snapshot().theme)
    }

    @Test fun automapPackageReportsTheSameSmallAndFullWindows() {
        state.event("com.byd.automap", ClusterActivityState.MINI_MAP, 1, 1, 100)
        assertEquals(ClusterActivityState.Snapshot(null, true, true), state.snapshot())
        state.event("com.byd.automap", ClusterActivityState.FULL_MAP, 1, 1, 200)
        assertEquals(ClusterActivityState.Snapshot(Theme.MAP, true, false), state.snapshot())
    }
}
