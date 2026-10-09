package com.shilapi.xcertplay.hud

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CarPlayCallWatchOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ownership get() = CarPlayCallWatchOwnership(temporary.root)
    private fun token(packageName: String = "com.example.diplay") = File(temporary.root,
        File(CarPlayCallWatchOwnership.newToken(packageName)).name).path

    @Test fun immediateNextCallCannotReuseThePreviousWatchersToken() {
        assertNotEquals(token(), token())
    }

    @Test fun oldWatcherCannotUpdateOrEndTheNewCall() {
        val old = token()
        val next = token()
        ownership.claim(old) { it.writeText("active 1") }
        ownership.claim(next) { it.writeText("active 2") }
        var writes = 0
        assertFalse(ownership.update(old) { writes++ })
        assertFalse(ownership.retire(old) { writes++ })
        assertEquals(0, writes)
        assertFalse(File(old).exists())
        assertEquals("active 2", File(next).readText())
        assertTrue(ownership.update(next) { writes++ })
        assertEquals(1, writes)
    }

    @Test fun anotherVariantCannotHaveItsTokenDeletedByTheOldProcess() {
        val old = token("com.example.mobile")
        val next = token("com.example.home")
        ownership.claim(old) { it.writeText("active 1") }
        ownership.claim(next) { it.writeText("ringing 0") }
        assertFalse(ownership.retire(old) { fail("Foreign hardware reset") })
        assertTrue(File(next).exists())
        assertTrue(ownership.update(next) {})
    }

    @Test fun successfulOwnedEndRetiresTheTokenAndPreventsLaterTimerWrites() {
        val path = token()
        ownership.claim(path) { it.writeText("active 1") }
        var ends = 0
        assertTrue(ownership.retire(path) { ends++ })
        assertEquals(1, ends)
        assertFalse(File(path).exists())
        assertFalse(ownership.update(path) { fail("Timer after end") })
    }

    @Test fun failingHardwareEndKeepsOwnershipForACompensationRetry() {
        val path = token()
        ownership.claim(path) { it.writeText("active 1") }
        try { ownership.retire(path) { throw IOException("write rejected") }; fail("Expected failure") }
        catch (_: IOException) {}
        assertTrue(File(path).exists())
        assertTrue(ownership.update(path) {})
        assertTrue(ownership.retire(path) {})
    }

    @Test fun cleanupIsJournaledBeforeAFirstShowCanMutateOrThrow() {
        val path = token()
        try {
            ownership.claim(path) {
                assertEquals("cleanup 0", it.readText())
                throw IOException("setter failed after mutation")
            }
            fail("Expected failure")
        } catch (_: IOException) {}
        assertEquals("cleanup 0", File(path).readText())
        assertTrue(ownership.update(path) {})
    }

    @Test fun watcherCleanupFailureKeepsTheJournalAndRetriesWithoutResettingAForeignOwner() {
        val path = token()
        ownership.claim(path) { }
        var attempts = 0
        assertFalse(ownership.retireOrRetry(path) { attempts++; throw IOException("idle setter refused") })
        assertEquals("cleanup 0", File(path).readText())
        assertTrue(ownership.retireOrRetry(path) { attempts++ })
        assertEquals(2, attempts)
        assertFalse(File(path).exists())
        val old = token()
        val next = token("com.example.other")
        ownership.claim(old) { }
        ownership.claim(next) { }
        assertTrue(ownership.retireOrRetry(old) { fail("Foreign reset") })
        assertEquals("cleanup 0", File(next).readText())
    }

    @Test fun failedEndTurnsAnActiveJournalIntoPendingCleanupBeforeTheSetter() {
        val path = token()
        ownership.claim(path) { it.writeText("active 1") }
        assertFalse(ownership.retireOrRetry(path) {
            assertEquals("cleanup 0", File(path).readText())
            throw IOException("idle setter failed after mutation")
        })
        assertEquals("cleanup 0", File(path).readText())
        assertTrue(ownership.update(path) {})
    }

    @Test fun preparedAppDeathAndCancellationNeverEnterAnIdleSetter() {
        val path = token()
        assertTrue(ownership.prepare(path, "123"))
        assertTrue(ownership.markWatcherReady(path, "123", "456"))
        assertTrue(ownership.retireStaged(path) { fail("Pristine app death must not reset hardware") })
        assertFalse(File(path).exists())
        assertFalse(File("$path.ready").exists())
        val next = token()
        assertTrue(ownership.prepare(next, "123"))
        assertTrue(ownership.cancelPrepared(next))
        assertFalse(ownership.markWatcherReady(next, "123", "456"))
    }

    @Test fun missingDeadForeignOrLateWatcherReadinessCannotPermitTheFirstSetter() {
        val path = token()
        assertTrue(ownership.prepare(path, "123"))
        assertFalse(ownership.claimReady(path, "123", { true }) { fail("No ready child") })
        assertFalse(ownership.markWatcherReady(path, "999", "456"))
        assertTrue(ownership.markWatcherReady(path, "123", "456"))
        assertFalse(ownership.claimReady(path, "123", { false }) { fail("Dead watcher") })
        assertFalse(ownership.watcherReady(path, "999") { true })
        assertTrue(ownership.cancelPrepared(path))
        val replacement = token()
        assertTrue(ownership.prepare(replacement, "999"))
        assertFalse(ownership.markWatcherReady(path, "123", "456"))
        assertFalse(ownership.claimReady(path, "123", { true }) { fail("Late/foreign child") })
        assertTrue(ownership.update(replacement) {})
    }

    @Test fun readyClaimJournalsDirtyOwnershipBeforeTheSetterAndCannotBeCancelledAsPristine() {
        val path = token()
        assertTrue(ownership.prepare(path, "123"))
        assertTrue(ownership.markWatcherReady(path, "123", "456"))
        try {
            ownership.claimReady(path, "123", { true }) {
                assertEquals("cleanup 0 123", it.readText())
                throw IOException("partial hardware mutation")
            }
            fail("Expected refusal")
        } catch (_: IOException) {}
        assertFalse(ownership.cancelPrepared(path))
        assertFalse(ownership.claimReady(path, "123", { true }) { fail("Uncompensated mutation") })
        try { ownership.retireStaged(path) { throw IOException("end refused") }; fail("Expected end refusal") }
        catch (_: IOException) {}
        assertEquals("cleanup 0 123", File(path).readText())
        assertTrue(ownership.markWatcherReady(path, "123", "789")) // recovery child restart
        assertTrue(ownership.retireStaged(path) {})
        assertFalse(File(path).exists())
    }

    @Test fun pristineRetirementAndConcurrentFirstMutationUseTheSameOwnershipLock() {
        val path = token()
        assertTrue(ownership.prepare(path, "123"))
        assertTrue(ownership.markWatcherReady(path, "123", "456"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var ends = 0
        try {
            val mutation = executor.submit<Boolean> {
                ownership.claimReady(path, "123", { true }) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    it.writeText("active 1 123")
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val end = executor.submit<Boolean> { ownership.retireStaged(path) { ends++ } }
            assertFalse(end.isDone)
            release.countDown()
            assertTrue(mutation.get(5, TimeUnit.SECONDS))
            assertTrue(end.get(5, TimeUnit.SECONDS))
            assertEquals(1, ends) // first mutation was not mistaken for an untouched reservation
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun prepareRefusesLiveOrUncertainForeignReservationsAndEveryDirtyForeignRecord() {
        val old = token("com.example.old")
        val next = token("com.example.new")
        assertTrue(ownership.prepare(old, "123"))
        assertFalse(ownership.prepare(next, "999") { true }) // live or unknown app PID
        assertEquals("prepared 0 123", File(old).readText())
        assertTrue(ownership.markWatcherReady(old, "123", "456"))
        assertTrue(ownership.claimReady(old, "123", { true }) { it.writeText("active 1 123") })
        assertFalse(ownership.prepare(next, "999") { false }) // dead app, but dirty hardware
        assertEquals("active 1 123", File(old).readText())
        assertTrue(ownership.retireStaged(old) {})
        assertTrue(ownership.prepare(next, "999")) // existing dirty cleanup remains a recovery path
    }

    @Test fun aPositivelyDeadPristineAppWithoutAnInitializedChildDoesNotBlockFutureCalls() {
        val old = token()
        val next = token()
        assertTrue(ownership.prepare(old, "123"))
        assertTrue(ownership.prepare(next, "999") { pid -> assertEquals("123", pid); false })
        assertFalse(File(old).exists())
        assertEquals("prepared 0 999", File(next).readText())
        assertFalse(ownership.markWatcherReady(old, "123", "456"))
        assertFalse(ownership.retireStaged(old) { fail("Stale pristine hardware reset") })
        assertTrue(ownership.update(next) {})
    }

    @Test fun missingOldTokenAndOldChildExitCannotDeleteNewReadinessOrBlockRecovery() {
        val old = token()
        assertTrue(ownership.prepare(old, "123"))
        File(old).delete() // reservation never reached a setter
        val next = token()
        assertTrue(ownership.prepare(next, "123"))
        assertTrue(ownership.markWatcherReady(next, "123", "456"))
        assertTrue(ownership.markWatcherReady(next, "123", "789"))
        ownership.clearWatcherReady(next, "456")
        assertTrue(ownership.watcherReady(next, "123") { it == "789" })
        ownership.clearWatcherReady(next, "789")
        assertFalse(ownership.watcherReady(next, "123") { true })
    }

    @Test fun callerCannotDeleteAnUnrelatedFile() {
        val unrelated = temporary.newFile("other-owner")
        try { ownership.retire(unrelated.path) {}; fail("Expected invalid token") }
        catch (_: IllegalArgumentException) {}
        assertTrue(unrelated.exists())
    }
}
