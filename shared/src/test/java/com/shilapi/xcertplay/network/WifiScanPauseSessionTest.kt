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
        var code: Int? = 62
        var writesAllowed = true
        var clearAllowed = true
        var restoreAvailable = true
        val commands = mutableListOf<Pair<Int, Boolean>>()

        fun write(code: Int, value: Boolean): Boolean {
            assertTrue("Recovery must be durable before any firmware write", journal)
            commands.add(code to value)
            if (value && !restoreAvailable) return false
            enabled = value
            return true
        }

        fun session(write: (Int, Boolean) -> Boolean = ::write) = WifiScanPauseSession(
            transactionCode = { code },
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
        assertTrue(session.acquire(first) { true })
        assertTrue(session.acquire(first) { true })
        assertTrue(session.acquire(second) { true })
        assertEquals(listOf(62 to false), fixture.commands)
        assertTrue(session.release(first))
        assertFalse(fixture.enabled)
        assertTrue(fixture.journal)
        assertFalse(session.recoveryPending())
        assertTrue(session.release(second))
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
        assertEquals(listOf(62 to false, 62 to true), fixture.commands)
    }

    @Test
    fun failedRestoreRetainsRecoveryAndRetriesAfterAdbReturns() {
        val fixture = Fixture()
        val session = fixture.session()
        val owner = Any()
        assertTrue(session.acquire(owner) { true })
        fixture.restoreAvailable = false
        assertFalse(session.release(owner))
        assertFalse(fixture.enabled)
        assertTrue(session.recoveryPending())
        fixture.restoreAvailable = true
        assertTrue(session.recover())
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
        assertEquals(listOf(62 to false, 62 to true, 62 to true), fixture.commands)
    }

    @Test
    fun nextProcessRestoresDurableMarkerUsingItsCurrentFrameworkCode() {
        val fixture = Fixture()
        assertTrue(fixture.session().acquire(Any()) { true })
        fixture.code = 73
        val nextProcess = fixture.session()
        assertTrue(nextProcess.recoveryPending())
        assertTrue(nextProcess.recover())
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
        assertEquals(listOf(62 to false, 73 to true), fixture.commands)
    }

    @Test
    fun failedJournalWriteNeverDisablesFirmware() {
        val fixture = Fixture().apply { writesAllowed = false }
        assertFalse(fixture.session().acquire(Any()) { true })
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
        assertTrue(fixture.commands.isEmpty())
    }

    @Test
    fun failedJournalClearKeepsRestoreRetryable() {
        val fixture = Fixture()
        val session = fixture.session()
        val owner = Any()
        assertTrue(session.acquire(owner) { true })
        fixture.clearAllowed = false
        assertFalse(session.release(owner))
        assertTrue(fixture.enabled)
        assertTrue(session.recoveryPending())
        fixture.clearAllowed = true
        assertTrue(session.recover())
        assertFalse(fixture.journal)
    }

    @Test
    fun unknownFrameworkNeverWritesOrDiscardsRecovery() {
        val fixture = Fixture().apply { code = null }
        val session = fixture.session()
        assertFalse(session.acquire(Any()) { true })
        assertTrue(session.recover())
        fixture.journal = true
        assertFalse(session.recover())
        assertTrue(session.recoveryPending())
        assertTrue(fixture.commands.isEmpty())
    }

    @Test
    fun uncertainDisableReplyStillRestoresPossiblyMutatedSystem() {
        val fixture = Fixture()
        val session = fixture.session { code, value ->
            fixture.write(code, value)
            value // The disable happened, but its reply was lost.
        }
        assertFalse(session.acquire(Any()) { true })
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
        assertEquals(listOf(62 to false, 62 to true), fixture.commands)
    }

    @Test
    fun cancellationBeforeOrDuringAcquireCannotLeaveScansSuppressed() {
        val fixture = Fixture()
        var current = false
        val session = fixture.session { code, value ->
            fixture.write(code, value)
            current = false
            true
        }
        assertFalse(session.acquire(Any()) { current })
        assertTrue(fixture.commands.isEmpty())
        current = true
        assertFalse(session.acquire(Any()) { current })
        assertTrue(fixture.enabled)
        assertFalse(fixture.journal)
        assertEquals(listOf(62 to false, 62 to true), fixture.commands)
    }

    @Test
    fun thrownDisableStillLeavesRecoveryWithoutAStaleOwner() {
        val fixture = Fixture()
        val session = fixture.session { code, value ->
            fixture.write(code, value)
            if (!value) error("reply failed after mutation")
            true
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
        assertTrue(session.acquire(old) { true })
        fixture.restoreAvailable = false
        assertFalse(session.release(old))
        assertTrue(session.acquire(current) { true })
        fixture.restoreAvailable = true
        assertTrue(session.recover()) // A scheduled old retry runs after the replacement acquired.
        assertTrue(session.release(old))
        assertFalse(fixture.enabled)
        assertTrue(fixture.journal)
        assertEquals(listOf(62 to false, 62 to true, 62 to false), fixture.commands)
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
        val session = fixture.session { code, value ->
            if (value) {
                restoreStarted.countDown()
                assertTrue(allowRestore.await(5, TimeUnit.SECONDS))
            }
            fixture.write(code, value)
        }
        val old = Any()
        val replacement = Any()
        try {
            assertTrue(worker.submit<Boolean> { session.acquire(old) { true } }.get(5, TimeUnit.SECONDS))
            val oldRestore = worker.submit<Boolean> { session.release(old) }
            assertTrue(restoreStarted.await(5, TimeUnit.SECONDS))
            val replacementPause = worker.submit<Boolean> { session.acquire(replacement) { true } }
            assertFalse(replacementPause.isDone)
            allowRestore.countDown()
            assertTrue(oldRestore.get(5, TimeUnit.SECONDS))
            assertTrue(replacementPause.get(5, TimeUnit.SECONDS))
            assertFalse(fixture.enabled)
            assertTrue(worker.submit<Boolean> { session.recover() }.get(5, TimeUnit.SECONDS))
            assertFalse(fixture.enabled)
            assertEquals(listOf(62 to false, 62 to true, 62 to false), fixture.commands)
            assertTrue(worker.submit<Boolean> { session.release(replacement) }.get(5, TimeUnit.SECONDS))
            assertTrue(fixture.enabled)
        } finally {
            allowRestore.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
