package com.shilapi.xcertplay.network

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class WirelessReceiveDiagnosticsTest {
    @Test fun fullWidthCounterDeltasFitOnSeparateExportableLines() {
        var value = 0L
        var clock = 0L
        val diagnostics = WirelessReceiveDiagnostics("p2p0", { path, _ ->
            when (path) {
                "/proc/net/snmp" -> udp4(value)
                "/proc/net/snmp6" -> udp6(value)
                else -> value.toString()
            }
        }, { clock })
        diagnostics.snapshot()
        value = Long.MAX_VALUE
        clock = Long.MAX_VALUE
        val lines = diagnostics.snapshot().lines()
        assertEquals(3, lines.size)
        assertTrue(lines.all { "wireless snapshot cached=true $it".length < 350 })
        assertTrue(lines.first().contains("ifaceRxMissedErrorsDelta=${Long.MAX_VALUE}"))
        assertTrue(lines[1].contains("udp4InCsumErrorsDelta=${Long.MAX_VALUE}"))
        assertTrue(lines[2].contains("udp6InCsumErrorsDelta=${Long.MAX_VALUE}"))
    }

    @Test fun reportsNumericWindowDeltasForBothFamiliesWithoutExportingFileContent() {
        var value = 10L
        var clock = 0L
        val diagnostics = WirelessReceiveDiagnostics("p2p0", { path, limit ->
            assertTrue(limit in 1..16 * 1024)
            when (path) {
                "/proc/net/snmp" -> udp4(value) + "\nprivate unrelated text"
                "/proc/net/snmp6" -> udp6(value)
                else -> value.toString()
            }
        }, { clock })
        val first = diagnostics.snapshot()
        assertEquals(3, first.lines().size)
        assertTrue(first.lines().all { it.contains("windowMs=baseline udpScope=device") })
        assertTrue(first.contains("ifaceRx=baseline") && first.contains("udp4=baseline") && first.contains("udp6=baseline"))
        value = 13
        clock = 10_000_000_000L
        val result = diagnostics.snapshot()
        assertTrue(result.contains("windowMs=10000 udpScope=device"))
        assertTrue(result.contains("ifaceRxPacketsDelta=3 ifaceRxBytesDelta=3 ifaceRxDroppedDelta=3"))
        assertTrue(result.contains("udp4RcvbufErrorsDelta=3"))
        assertTrue(result.contains("udp6RcvbufErrorsDelta=3"))
        assertFalse(result.contains("p2p0") || result.contains("private") || result.contains("unrelated"))
    }

    @Test fun recreatedInterfaceAndResetCountersStartANewBaseline() {
        var value = 20L
        val diagnostics = WirelessReceiveDiagnostics("p2p0", { path, _ ->
            when (path) {
                "/proc/net/snmp" -> udp4(value)
                "/proc/net/snmp6" -> udp6(value)
                else -> value.toString()
            }
        })
        diagnostics.snapshot()
        value = 2
        val reset = diagnostics.snapshot()
        assertTrue(reset.contains("ifaceRx=reset") && reset.contains("udp4=reset") && reset.contains("udp6=reset"))
        assertFalse(reset.contains("Delta=-"))
        value = 3
        assertTrue(diagnostics.snapshot().contains("ifaceRxPacketsDelta=1"))
    }

    @Test fun inaccessibleSourcesAreTriedOnceAndExceptionDetailsStayPrivate() {
        var attempts = 0
        val diagnostics = WirelessReceiveDiagnostics("p2p0", { _, _ ->
            attempts++
            throw SecurityException("phone name/password/address should never be in the report")
        })
        val first = diagnostics.snapshot()
        val second = diagnostics.snapshot()
        assertEquals(3, attempts)
        for (result in listOf(first, second)) {
            assertTrue(result.contains("ifaceRx=unavailable failureClass=SecurityException"))
            assertTrue(result.contains("udp4=unavailable") && result.contains("udp6=unavailable"))
            assertFalse(result.contains("password") || result.contains("phone") || result.contains("address"))
        }
    }

    @Test fun interfaceNameCannotEscapeTheFixedStatisticsDirectory() {
        val paths = mutableListOf<String>()
        val diagnostics = WirelessReceiveDiagnostics("../../private", { path, _ ->
            paths.add(path)
            throw IOException()
        })
        assertTrue(diagnostics.snapshot().contains("ifaceRx=unavailable"))
        assertEquals(listOf("/proc/net/snmp", "/proc/net/snmp6"), paths)
    }

    @Test fun parserUsesHeadersAndRejectsMissingMalformedOrOversizedCounters() {
        val result = WirelessReceiveDiagnostics.parseUdp4(
            "Tcp: ignored fields\nUdp: RcvbufErrors NoPorts InErrors InDatagrams\nUdp: 7 123 9 11\n",
        )
        assertEquals(linkedMapOf("InDatagrams" to 11L, "InErrors" to 9L, "RcvbufErrors" to 7L), result)
        val bad = listOf(
            "Udp: InDatagrams InErrors\nUdp: 1 2",
            "Udp: InDatagrams InErrors RcvbufErrors\nUdp: 1 -2 3",
            "Udp: InDatagrams InErrors RcvbufErrors\nUdp: 1 secret 3",
            "Udp: InDatagrams InErrors RcvbufErrors\nUdp: 1 2",
            "x".repeat(16 * 1024 + 1),
        )
        bad.forEach { text ->
            try { WirelessReceiveDiagnostics.parseUdp4(text); fail("Malformed counters accepted") }
            catch (_: IOException) { }
        }
        try { WirelessReceiveDiagnostics.parseUdp6("Udp6InDatagrams 1\nUdp6InErrors 2"); fail("Missing receive-buffer counter accepted") }
        catch (_: IOException) { }
    }

    @Test fun diagnosticFileReadRejectsTruncationRatherThanPublishingAnIncorrectNumber() {
        val file = File.createTempFile("diplay-counter-test", ".txt")
        try {
            file.writeText("123\n")
            assertEquals("123\n", readBoundedDiagnosticFile(file.path, 4))
            try { readBoundedDiagnosticFile(file.path, 3); fail("Oversized source accepted") }
            catch (_: IOException) { }
        } finally { file.delete() }
    }

    private fun udp4(value: Long): String =
        "Udp: InDatagrams NoPorts InErrors OutDatagrams RcvbufErrors SndbufErrors InCsumErrors\n" +
            "Udp: $value 0 $value 0 $value 0 $value"

    private fun udp6(value: Long): String =
        "Udp6InDatagrams $value\nUdp6InErrors $value\nUdp6RcvbufErrors $value\nUdp6InCsumErrors $value"
}
