package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiScanPauseJournalTest {
    private class Storage(initial: Boolean = false) {
        var memory = initial
        var disk = initial
        var writable = false

        // Android updates the in-memory preference before knowing whether disk commit succeeded.
        fun commit(value: Boolean): Boolean {
            memory = value
            if (writable) disk = value
            return writable
        }

        fun journal() = WifiScanPauseJournal(disk, ::commit)
    }

    @Test
    fun failedPreferenceCommitCannotAuthorizeALaterDisableFromItsMemoryCache() {
        val storage = Storage()
        val journal = storage.journal()
        var enabled = true
        val session = WifiScanPauseSession(
            setEnabled = { value -> enabled = value; WifiScanSwitchResult.DONE },
            loadJournal = { journal.pending },
            saveJournal = journal::save,
        )
        assertFalse(session.acquire(Any()) { true }.paused)
        assertTrue(storage.memory)
        assertFalse(storage.disk)
        assertFalse(journal.pending)
        assertFalse(session.acquire(Any()) { true }.paused)
        assertTrue(enabled)
        storage.writable = true
        val owner = Any()
        assertTrue(session.acquire(owner) { true }.paused)
        assertTrue(storage.disk)
        assertFalse(enabled)
        assertTrue(session.release(owner))
        assertFalse(storage.disk)
        assertTrue(enabled)
    }

    @Test
    fun failedPreferenceClearRetainsRecoveryDespiteMemoryCacheRemoval() {
        val storage = Storage(initial = true)
        val journal = storage.journal()
        assertFalse(journal.save(false))
        assertFalse(storage.memory)
        assertTrue(storage.disk)
        assertTrue(journal.pending)
        assertTrue(storage.journal().pending)
        storage.writable = true
        assertTrue(journal.save(false))
        assertFalse(journal.pending)
        assertFalse(storage.journal().pending)
    }
}
