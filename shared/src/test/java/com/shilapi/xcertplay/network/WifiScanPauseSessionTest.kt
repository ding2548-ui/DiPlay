package com.shilapi.xcertplay.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiScanPauseSessionTest {
    private class Fixture {
        var journal = false
        var enabled = true
        var writesAllowed = true
        var clearAllowed = true
        var restoreAvailable = true
        val commands = mutableListOf<Boolean>()

        fun write(value: Boolean): WifiScanSwitchResult {
            assertTrue("Recovery must be durable before any firmware write", journal)
            commands.add(value)
            if (value && !restoreAvailable) return WifiScanSwitchResult.ADB_OFF
            enabled = value
            return WifiScanSwitchResult.DONE
        }

        /** A reply that reports without touching the firmware. */
        fun refuse(result: WifiScanSwitchResult): (Boolean) -> WifiScanSwitchResult = { value ->
            if (value) write(true) else {
                assertTrue("Recovery must be durable before any firmware write", journal)
                commands.add(false)
                result
            }
        }

        fun session(write: (Boolean) -> WifiScanSwitchResult = ::write) = WifiScanPauseSession(
            setEnabled = write,
            loadJournal = { journal },
            saveJournal = { pending ->
                if (!writesAllowed || (!pending && !clearAllowed)) false
                else {
                    journal = pending
                    true
                }
            },
        )
    }

    @Test
    fun twoControllersShareOnePauseUntilTheLastLeaseEnds() {
        val fixture = Fixture()
        val session = fixture.session()
        val first = Any()
        val second = Any()
        assertTrue(session.acquire(first) { true }.paused)
        assertTrue(session.acquire(first) { true }.paused)
        assertTrue(session.acquire(second) { true }.paused)
        assertEquals(listOf(false), fixture.commands)
        assertTrue(session.release(first))
        assertFalse(fixture.enabled)
        assertTrue(fixture.journal)
        assertFalse(session.recoveryPending())
        assertTrue(session.release(second))
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
        assertEquals(listOf(false, true), fixture.commands)
    }

    @Test
    fun failedRestoreRetainsRecoveryAndRetriesAfterAdbReturns() {
        val fixture = Fixture()
        val session = fixture.session()
        val owner = Any()
        assertTrue(session.acquire(owner) { true }.paused)
        fixture.restoreAvailable = false
        assertFalse(session.release(owner))
        assertFalse(fixture.enabled)
        assertTrue(session.recoveryPending())
        fixture.restoreAvailable = true
        assertTrue(session.recover())
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
        assertEquals(listOf(false, true, true), fixture.commands)
    }

    @Test
    fun nextProcessRestoresDurableMarker() {
        val fixture = Fixture()
        assertTrue(fixture.session().acquire(Any()) { true }.paused)
        val nextProcess = fixture.session()
        assertTrue(nextProcess.recoveryPending())
        assertTrue(nextProcess.recover())
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
        assertEquals(listOf(false, true), fixture.commands)
    }

    @Test
    fun failedJournalWriteNeverDisablesFirmware() {
        val fixture = Fixture().apply { writesAllowed = false }
        val outcome = fixture.session().acquire(Any()) { true }
        assertFalse(outcome.paused)
        assertEquals("journal-failed", outcome.reason)
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
        assertTrue(fixture.commands.isEmpty())
    }

    @Test
    fun failedJournalClearKeepsRestoreRetryable() {
        val fixture = Fixture()
        val session = fixture.session()
        val owner = Any()
        assertTrue(session.acquire(owner) { true }.paused)
        fixture.clearAllowed = false
        assertFalse(session.release(owner))
        assertTrue(fixture.enabled)
        assertTrue(session.recoveryPending())
        fixture.clearAllowed = true
        assertTrue(session.recover())
        assertFalse(fixture.journal)
    }

    @Test
    fun missingAdbChangesNothingAndLeavesNoRestoreToRetry() {
        for (result in listOf(
            WifiScanSwitchResult.ADB_OFF,
            WifiScanSwitchResult.ADB_NOT_APPROVED,
            WifiScanSwitchResult.ADB_PAIRING_ONLY,
            WifiScanSwitchResult.UNSUPPORTED,
            WifiScanSwitchResult.DENIED,
        )) {
            val fixture = Fixture()
            val session = fixture.session(fixture.refuse(result))
            val outcome = session.acquire(Any()) { true }
            assertFalse(outcome.paused)
            assertEquals(result.reason, outcome.reason)
            assertTrue(fixture.enabled)
            assertFalse(fixture.journal)
            assertFalse(session.recoveryPending())
            assertEquals(listOf(false), fixture.commands)
        }
    }

    @Test
    fun refusedPauseNeverDiscardsAnEarlierProcessRestore() {
        val fixture = Fixture().apply { journal = true; enabled = false; restoreAvailable = false }
        val session = fixture.session(fixture.refuse(WifiScanSwitchResult.ADB_OFF))
        assertFalse(session.acquire(Any()) { true }.paused)
        assertTrue(fixture.journal)
        assertTrue(session.recoveryPending())
        fixture.restoreAvailable = true
        assertTrue(session.recover())
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
    }

    @Test
    fun searchSomeoneElseTurnedOffIsLeftOff() {
        val fixture = Fixture().apply { enabled = false }
        val session = fixture.session(fixture.refuse(WifiScanSwitchResult.ALREADY_OFF))
        val owner = Any()
        val outcome = session.acquire(owner) { true }
        assertFalse(outcome.paused)
        assertEquals("already-off", outcome.reason)
        assertFalse(fixture.journal)
        assertTrue(session.release(owner))
        assertFalse(fixture.enabled)
        assertEquals(listOf(false), fixture.commands)
    }

    @Test
    fun earlierProcessPauseStillCountsAndIsRestoredLater() {
        val fixture = Fixture().apply { journal = true; enabled = false }
        val session = fixture.session(fixture.refuse(WifiScanSwitchResult.ALREADY_OFF))
        val owner = Any()
        assertTrue(session.acquire(owner) { true }.paused)
        assertTrue(fixture.journal)
        assertTrue(session.release(owner))
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
    }

    @Test
    fun uncertainDisableReplyStillRestoresPossiblyMutatedSystem() {
        val fixture = Fixture()
        val session = fixture.session { value ->
            fixture.write(value)
            if (value) WifiScanSwitchResult.DONE else WifiScanSwitchResult.UNKNOWN // Reply lost after the change.
        }
        val outcome = session.acquire(Any()) { true }
        assertFalse(outcome.paused)
        assertEquals("no-reply", outcome.reason)
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
        assertEquals(listOf(false, true), fixture.commands)
    }

    @Test
    fun cancellationBeforeOrDuringAcquireCannotLeaveScansSuppressed() {
        val fixture = Fixture()
        var current = false
        val session = fixture.session { value ->
            fixture.write(value)
            current = false
            WifiScanSwitchResult.DONE
        }
        assertEquals("cancelled", session.acquire(Any()) { current }.reason)
        assertTrue(fixture.commands.isEmpty())
        current = true
        val outcome = session.acquire(Any()) { current }
        assertFalse(outcome.paused)
        assertEquals("cancelled", outcome.reason)
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
        assertEquals(listOf(false, true), fixture.commands)
    }

    @Test
    fun thrownDisableStillLeavesRecoveryWithoutAStaleOwner() {
        val fixture = Fixture()
        val session = fixture.session { value ->
            fixture.write(value)
            if (!value) error("reply failed after mutation")
            WifiScanSwitchResult.DONE
        }
        try {
            session.acquire(Any()) { true }
            throw AssertionError("Expected lost shell failure")
        } catch (_: IllegalStateException) {
            assertTrue(session.recoveryPending())
        }
        assertTrue(session.recover())
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
    }

    @Test
    fun oldRecoveryAndRepeatedReleaseCannotUndoNewerPause() {
        val fixture = Fixture()
        val session = fixture.session()
        val old = Any()
        val current = Any()
        assertTrue(session.acquire(old) { true }.paused)
        fixture.restoreAvailable = false
        assertFalse(session.release(old))
        assertTrue(session.acquire(current) { true }.paused)
        fixture.restoreAvailable = true
        assertTrue(session.recover()) // A scheduled old retry runs after the replacement acquired.
        assertTrue(session.release(old))
        assertFalse(fixture.enabled)
        assertTrue(fixture.journal)
        assertEquals(listOf(false, true, false), fixture.commands)
        assertTrue(session.release(current))
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
    }

    @Test
    fun blockedOldRestoreCompletesBeforeReplacementCanWritePause() {
        val fixture = Fixture()
        val restoreStarted = CountDownLatch(1)
        val allowRestore = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val session = fixture.session { value ->
            if (value) {
                restoreStarted.countDown()
                assertTrue(allowRestore.await(5, TimeUnit.SECONDS))
            }
            fixture.write(value)
        }
        val old = Any()
        val replacement = Any()
        try {
            assertTrue(worker.submit<Boolean> { session.acquire(old) { true }.paused }.get(5, TimeUnit.SECONDS))
            val oldRestore = worker.submit<Boolean> { session.release(old) }
            assertTrue(restoreStarted.await(5, TimeUnit.SECONDS))
            val replacementPause = worker.submit<Boolean> { session.acquire(replacement) { true }.paused }
            assertFalse(replacementPause.isDone)
            allowRestore.countDown()
            assertTrue(oldRestore.get(5, TimeUnit.SECONDS))
            assertTrue(replacementPause.get(5, TimeUnit.SECONDS))
            assertFalse(fixture.enabled)
            assertTrue(worker.submit<Boolean> { session.recover() }.get(5, TimeUnit.SECONDS))
            assertFalse(fixture.enabled)
            assertEquals(listOf(false, true, false), fixture.commands)
            assertTrue(worker.submit<Boolean> { session.release(replacement) }.get(5, TimeUnit.SECONDS))
            assertTrue(fixture.enabled)
        } finally {
            allowRestore.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
