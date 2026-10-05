package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class BydStandaloneSessionTest {
    private class Rig(recovery: Boolean = false) {
        var now = 0L
        var failPacket: String? = null
        var journalFails = false
        val events = mutableListOf<String>()
        val session = BydStandaloneSession(
            send = { packet ->
                events += packet
                if (packet == failPacket) throw IllegalStateException("dispatch failed")
            },
            rememberPendingClear = { dirty ->
                if (journalFails) throw IllegalStateException("disk failed")
                events += "pending=$dirty"
            },
            needsRecovery = recovery,
            nanoTime = { now },
        )
    }

    @Test fun startsOnceUpdatesTurnAndClearsOnRouteEnd() {
        val r = Rig()
        r.session.update(2, 0, 500)
        r.session.update(3, 0, 800)
        r.session.clear()
        assertEquals(listOf("pending=true", BydStandalonePackets.start(),
            BydStandalonePackets.guidance(2, 0, 500), BydStandalonePackets.guidance(3, 0, 800),
            BydStandalonePackets.clear(), "pending=false"), r.events)
        r.session.clear()
        assertEquals(6, r.events.size)
    }

    @Test fun unchangedGuidanceIsRateLimitedButKeptAlive() {
        val r = Rig()
        r.session.update(9, 0, 0)
        repeat(9) { r.now += 100_000_000L; r.session.update(9, 0, 0) }
        assertEquals(3, r.events.size)
        r.now = 1_000_000_000L
        r.session.update(9, 0, 0)
        assertEquals(4, r.events.size)
    }

    @Test fun unknownManeuverEndsGuidanceInsteadOfInventingStraight() {
        val r = Rig()
        r.session.update(2, 0, 500)
        r.session.update(0, 0, 500)
        assertEquals(listOf(BydStandalonePackets.clear(), "pending=false"), r.events.takeLast(2))
        val count = r.events.size
        r.session.update(999, 0, 500)
        assertEquals(count, r.events.size)
    }

    @Test fun invalidDistanceEndsGuidance() {
        val r = Rig()
        r.session.update(2, 0, 500)
        r.session.update(2, 0, 16777215)
        assertEquals(BydStandalonePackets.clear(), r.events[r.events.lastIndex - 1])
        assertNull(BydStandalonePackets.guidance(2, 0, -1))
        assertNotNull(BydStandalonePackets.guidance(2, 0, 16777214))
    }

    @Test fun switchingToLyricsClearsThePreviousArrowsBeforePublishingText() {
        val r = Rig()
        r.session.update(2, 0, 500, "Road")
        r.events.clear()
        r.session.showText("Song")
        assertEquals(listOf(BydStandalonePackets.clear(), "pending=false", "pending=true",
            BydStandalonePackets.start(), BydStandalonePackets.text("Song")), r.events)
        r.events.clear()
        r.session.showText("Song")
        assertTrue(r.events.isEmpty())
        r.session.clear()
        assertEquals(listOf(BydStandalonePackets.clear(), "pending=false"), r.events)
    }

    @Test fun lyricsJournalFailureCannotStartHardwareOutput() {
        val r = Rig().apply { journalFails = true }
        assertThrows(IllegalStateException::class.java) { r.session.showText("Song") }
        assertTrue(r.events.isEmpty())
    }

    @Test fun failedGuidanceAfterStartSendsEndAndNextUpdateStartsAgain() {
        val r = Rig()
        r.failPacket = BydStandalonePackets.guidance(2, 0, 500)
        assertThrows(IllegalStateException::class.java) { r.session.update(2, 0, 500) }
        assertEquals(listOf(BydStandalonePackets.clear(), "pending=false"), r.events.takeLast(2))
        r.failPacket = null
        r.events.clear()
        r.session.update(3, 0, 800)
        assertEquals(listOf("pending=true", BydStandalonePackets.start()), r.events.take(2))
    }

    @Test fun failedEndIsRetriedBeforeAnyNewGuidance() {
        val r = Rig()
        r.session.update(2, 0, 500)
        r.failPacket = BydStandalonePackets.clear()
        assertThrows(IllegalStateException::class.java) { r.session.clear() }
        r.events.clear()
        assertThrows(IllegalStateException::class.java) { r.session.update(3, 0, 800) }
        assertEquals(listOf(BydStandalonePackets.clear()), r.events)
        r.failPacket = null
        r.events.clear()
        r.session.update(3, 0, 800)
        assertEquals(listOf(BydStandalonePackets.clear(), "pending=false", "pending=true",
            BydStandalonePackets.start(), BydStandalonePackets.guidance(3, 0, 800)), r.events)
    }

    @Test fun interruptedSessionClearsBeforeItStartsAnotherRoute() {
        val r = Rig(recovery = true)
        r.session.update(2, 0, 500)
        assertEquals(listOf(BydStandalonePackets.clear(), "pending=false", "pending=true",
            BydStandalonePackets.start()), r.events.take(4))
    }

    @Test fun cannotStartIfRecoveryJournalCannotBeSaved() {
        val r = Rig()
        r.journalFails = true
        assertThrows(IllegalStateException::class.java) { r.session.update(2, 0, 500) }
        assertTrue(r.events.isEmpty())
    }

    @Test fun fixedWireVectorsAndRoundaboutMapping() {
        assertEquals("43,E0,00,3A,01,02", BydStandalonePackets.start())
        assertEquals("43,E0,00,3A,01,01", BydStandalonePackets.clear())
        assertEquals("43,F0,10,18,04,00,00,01,F4,43,F0,10,10,04,00,00,00,01,43,F0,10,30,04,00,00,00,01",
            BydStandalonePackets.guidance(2, 0, 500))
        assertEquals("43,F0,10,18,04,00,00,03,20,43,F0,10,10,04,00,00,00,02,43,F0,10,30,04,00,00,00,02",
            BydStandalonePackets.guidance(3, 0, 800))
        val bytes = BydStandalonePackets.guidance(11, 10, 0)!!.split(',')
        assertEquals(27, bytes.size)
        assertEquals("22", bytes[17]) // Factory right-roundabout exit 10 = 34.
        assertEquals("22", bytes[26])
    }
}
