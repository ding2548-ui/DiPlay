package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.orchestration.ConnectionIoDiagnostics.Operation.*
import com.shilapi.xcertplay.orchestration.ConnectionIoDiagnostics.Result.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionIoDiagnosticsTest {
    @Test fun aPacketStormKeepsCountsAndTimingsWithoutLoggingEveryOperation() {
        var now = 0L
        val lines = mutableListOf<String>()
        val diagnostics = ConnectionIoDiagnostics(lines::add) { now }
        repeat(10_000) {
            diagnostics.record(WRITE, COMPLETED, if (it == 5_000) 1_937 else 2)
            diagnostics.record(READ, if (it % 2 == 0) TIMED_OUT else COMPLETED, 909)
        }
        assertEquals(2, lines.size)
        now = 10_000_000_000L
        diagnostics.record(READ, COMPLETED, 1)
        assertEquals(3, lines.size)
        diagnostics.finish()
        diagnostics.finish()
        assertEquals(4, lines.size)
        assertTrue(lines.last().contains("readCalls=10001 writeCalls=10000 readTimeouts=5000"))
        assertTrue(lines.last().contains("maxReadMs=909 maxWriteMs=1937"))
        assertTrue(lines.all { it.length < 250 })
    }

    @Test fun failuresAndEndOfStreamStayDistinctFromTimeoutsAndAreBounded() {
        val lines = mutableListOf<String>()
        val diagnostics = ConnectionIoDiagnostics(lines::add) { 0 }
        diagnostics.record(READ, TIMED_OUT, 900)
        repeat(100) { diagnostics.record(READ, FAILED, 7) }
        diagnostics.record(READ, ENDED, 0)
        diagnostics.finish()
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("result=FAILED"))
        assertTrue(lines.last().contains("readTimeouts=1 ends=1 failures=100"))
        assertFalse(lines.joinToString().contains("bytes"))
    }

    @Test fun aBrokenDiagnosticSinkCannotFailTheTransport() {
        val diagnostics = ConnectionIoDiagnostics({ throw IllegalStateException("private failure detail") }) { 0 }
        diagnostics.record(WRITE, COMPLETED, -5)
        diagnostics.record(READ, FAILED, 1)
        diagnostics.finish()
    }
}
