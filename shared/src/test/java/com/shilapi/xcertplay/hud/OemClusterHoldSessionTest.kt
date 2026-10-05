package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class OemClusterHoldSessionTest {
    private class Rig(initial: Int = 0) {
        var state = initial
        var journal: OemClusterHoldSession.Journal? = null
        var journalWritable = true
        var commandsSucceed = true
        var applyCommands = true
        var current = true
        var duringCommand: (() -> Unit)? = null
        val events = mutableListOf<String>()
        fun session() = OemClusterHoldSession(
            readState = { state },
            setState = { target, next ->
                events += "set=$target:$next"
                if (applyCommands) state = next
                duringCommand?.invoke()
                commandsSucceed
            },
            loadJournal = { journal },
            saveJournal = { next ->
                events += "journal=${next?.originalState}"
                if (journalWritable) journal = next
                journalWritable
            },
        )
    }

    @Test fun journalsBeforeDisablingAndRestoresTheExactOriginalState() {
        for (original in 0..4) {
            val r = Rig(original)
            val s = r.session()
            assertTrue(s.acquire(BydOemClusterHold.COMPONENT, "one") { r.current })
            assertEquals("journal=$original", r.events.first())
            assertEquals(3, r.state)
            assertTrue(s.release("one"))
            assertEquals(original, r.state)
            assertNull(r.journal)
        }
    }

    @Test fun diskFailurePreventsAnyOemWrite() {
        val r = Rig().apply { journalWritable = false }
        assertFalse(r.session().acquire(BydOemClusterHold.PACKAGE, "one") { true })
        assertEquals(0, r.state)
        assertFalse(r.events.any { it.startsWith("set=") })
    }

    @Test fun queuedStopBeforeAcquirePreventsDisable() {
        val r = Rig()
        val s = r.session()
        val queue = java.util.ArrayDeque<() -> Unit>()
        queue.add { assertFalse(s.acquire(BydOemClusterHold.COMPONENT, "one") { r.current }) }
        r.current = false
        queue.add { assertTrue(s.release("one")) }
        while (!queue.isEmpty()) queue.removeFirst().invoke()
        assertTrue(r.events.isEmpty())
    }

    @Test fun stopDuringDisableRestoresBeforeReturning() {
        val r = Rig()
        val s = r.session()
        r.duringCommand = { r.current = false }
        assertFalse(s.acquire(BydOemClusterHold.COMPONENT, "one") { r.current })
        assertEquals(0, r.state)
        assertNull(r.journal)
    }

    @Test fun restartRecoversACrashAfterDisable() {
        val r = Rig(1)
        assertTrue(r.session().acquire(BydOemClusterHold.PACKAGE, "one") { true })
        assertTrue(r.session().release())
        assertEquals(1, r.state)
        assertNull(r.journal)
    }

    @Test fun restartAfterJournalBeforeMutationDoesNotChangeTheOemState() {
        val r = Rig(2)
        r.journal = OemClusterHoldSession.Journal(OemClusterHoldSession.Target.COMPONENT, 2, "one")
        assertTrue(r.session().release())
        assertEquals(2, r.state)
        assertFalse(r.events.any { it.startsWith("set=") })
    }

    @Test fun failedRestorationKeepsJournalAndCanBeRetried() {
        val r = Rig(1)
        val s = r.session()
        assertTrue(s.acquire(BydOemClusterHold.COMPONENT, "one") { true })
        r.applyCommands = false
        r.commandsSucceed = false
        assertFalse(s.release("one"))
        assertNotNull(r.journal)
        r.applyCommands = true
        r.commandsSucceed = true
        assertTrue(s.release("one"))
        assertEquals(1, r.state)
        assertNull(r.journal)
    }

    @Test fun successfulCommandWithoutStateChangeCannotStartTheMirror() {
        val r = Rig().apply { applyCommands = false }
        assertFalse(r.session().acquire(BydOemClusterHold.COMPONENT, "one") { true })
        assertEquals(0, r.state)
    }

    @Test fun staleReleaseCannotUndoANewerLease() {
        val r = Rig()
        val s = r.session()
        assertTrue(s.acquire(BydOemClusterHold.COMPONENT, "one") { true })
        assertTrue(s.acquire(BydOemClusterHold.PACKAGE, "two") { true })
        assertTrue(s.release("one"))
        assertEquals(3, r.state)
        assertEquals("two", r.journal?.lease)
        assertTrue(s.release("two"))
        assertEquals(0, r.state)
    }

    @Test fun componentCommandsUseTheFlattenedNameAndSupportAllOriginalStates() {
        val component = "com.byd.automap/com.byd.automap.extra.MeterActivity"
        assertEquals("pm disable-user --user 0 $component",
            BydOemClusterNavi.command(OemClusterHoldSession.Target.COMPONENT, 3))
        assertEquals("pm default-state --user 0 $component",
            BydOemClusterNavi.command(OemClusterHoldSession.Target.COMPONENT, 0))
        for (state in 0..4) assertTrue(BydOemClusterNavi.command(OemClusterHoldSession.Target.PACKAGE, state)
            .endsWith("--user 0 com.byd.automap"))
    }
}
