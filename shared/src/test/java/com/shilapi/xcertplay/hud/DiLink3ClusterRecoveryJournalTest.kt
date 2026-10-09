package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class DiLink3ClusterRecoveryJournalTest {
    private val ok = "Result: Parcel(00000000 00000000 '........')"

    @Test fun aFailedCommitThatChangesMemoryCannotAdmitANewClusterMutation() {
        var memory = false
        var commitsAllowed = false
        val journal = DiLink3ClusterRecoveryJournal(false) { next ->
            memory = next // SharedPreferences changes this before discovering the disk failure.
            commitsAllowed
        }
        val commands = mutableListOf<String>()
        val session = DiLink3ClusterModeSession({ commands.add(it); ok }, { journal.pending }, journal::save,
            projectionStepDelay = {})
        repeat(2) { assertFalse(session.apply(BydDiLink3ClusterMode.Mode.PROJECTION)) }
        assertTrue(memory)
        assertFalse(journal.pending)
        assertTrue(commands.isEmpty())
        commitsAllowed = true
        assertTrue(session.apply(BydDiLink3ClusterMode.Mode.PROJECTION))
        assertEquals(listOf(BydDiLink3ClusterMode.Mode.PROJECTION.entryCommand,
            BydDiLink3ClusterMode.Mode.PROJECTION.command), commands)
        assertTrue(journal.pending)
    }

    @Test fun failedClearDuringStartupRecoveryKeepsTheDurableMarkerRetryable() {
        var memory = true
        var commitsAllowed = false
        val journal = DiLink3ClusterRecoveryJournal(true) { next ->
            memory = next
            commitsAllowed
        }
        val commands = mutableListOf<String>()
        val session = DiLink3ClusterModeSession({ commands.add(it); ok }, { journal.pending }, journal::save)
        assertFalse(session.recoverInterrupted())
        assertFalse(memory)
        assertTrue(journal.pending)
        commitsAllowed = true
        assertTrue(session.recoverInterrupted())
        assertEquals(List(2) { BydDiLink3ClusterMode.Mode.STOCK.command }, commands)
        assertFalse(journal.pending)
        assertTrue(session.apply(null))
        assertEquals(2, commands.size)
    }
}
