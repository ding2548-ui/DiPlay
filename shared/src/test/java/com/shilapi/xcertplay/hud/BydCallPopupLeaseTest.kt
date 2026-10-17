package com.shilapi.xcertplay.hud

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class BydCallPopupLeaseTest {
    private class Fixture(initial: Int = 3) : BydCallPopupLease.Ops {
        var mode = initial
        var now = 0L
        var failHide = false
        var failRestore = false
        val writes = mutableListOf<Int>()
        val reports = mutableListOf<String>()
        val lease = BydCallPopupLease(this, { now }, { reports += it })
        override fun query() = mode
        override fun set(value: Int) {
            writes += value
            if (value != 1 && failRestore) throw IOException()
            mode = value
            if (value == 1 && failHide) throw IOException()
        }
    }

    @Test fun restoresTheExactOriginalPackageMode() {
        for (mode in listOf(0, 2, 3, 4)) {
            val f = Fixture(mode)
            f.lease.accept("1 START")
            assertEquals(1, f.mode)
            f.lease.accept("2 STOP")
            assertEquals(mode, f.mode)
            assertEquals(listOf(1, mode), f.writes)
            assertEquals("RESTORED", f.reports.last())
        }
    }

    @Test fun alreadyIgnoredDoesNotClaimOrChangeTheMode() {
        val f = Fixture(1)
        f.lease.accept("1 START")
        f.lease.accept("2 STOP")
        assertTrue(f.writes.isEmpty())
        assertEquals(listOf("ALREADY_HIDDEN"), f.reports)
    }

    @Test fun watchdogRestoresAfterTenSecondsWithoutNewHeartbeat() {
        val f = Fixture()
        f.lease.accept("1 START")
        f.now = 9_999
        f.lease.tick()
        assertEquals(1, f.mode)
        f.now = 10_000
        f.lease.tick()
        assertEquals(3, f.mode)
    }

    @Test fun rereadingTheSameControlFileDoesNotRenewTheLease() {
        val f = Fixture()
        f.lease.accept("1 START")
        f.now = 2_000
        f.lease.accept("2 PING")
        f.now = 11_999
        f.lease.accept("2 PING")
        assertEquals(1, f.mode)
        f.now = 12_000
        f.lease.accept("3 PING")
        assertEquals(3, f.mode)
    }

    @Test fun externallyChangedModeIsPreserved() {
        val f = Fixture()
        f.lease.accept("1 START")
        f.mode = 0
        f.now = 2_000
        f.lease.tick()
        f.lease.accept("2 STOP")
        assertEquals(0, f.mode)
        assertEquals(listOf(1), f.writes)
        assertEquals("EXTERNAL_CHANGE", f.reports.last())
    }

    @Test fun mutationThatThrowsStillRestores() {
        val f = Fixture().apply { failHide = true }
        f.lease.accept("1 START")
        assertEquals(3, f.mode)
        assertEquals("RESTORED", f.reports.last())
    }

    @Test fun failedRestorationIsRetriedAndNeverReportedAsRestored() {
        val f = Fixture()
        f.lease.accept("1 START")
        f.failRestore = true
        f.lease.accept("2 STOP")
        for (now in listOf(1_000L, 3_000L, 7_000L)) { f.now = now; f.lease.tick() }
        assertEquals(listOf(1, 3, 3, 3, 3), f.writes)
        assertEquals("ERROR RESTORE_FAILED", f.reports.last())
        assertFalse("RESTORED" in f.reports)
    }

    @Test fun stopBeforeStartNeverMutates() {
        val f = Fixture()
        f.lease.accept("1 STOP")
        f.lease.accept("2 START")
        assertTrue(f.writes.isEmpty())
        assertEquals("CANCELLED", f.reports.last())
    }

    @Test fun invalidOrUnqueryableModeIsNotMutated() {
        val f = Fixture(9)
        f.lease.accept("1 START")
        assertTrue(f.writes.isEmpty())
        assertEquals("ERROR QUERY_FAILED", f.reports.last())
    }

    @Test fun malformedControlRestoresRatherThanExecutingAnythingElse() {
        val f = Fixture()
        f.lease.accept("1 START")
        f.lease.accept("2 START; arbitrary-command")
        assertEquals(3, f.mode)
        assertTrue(f.lease.finished())
    }
}
