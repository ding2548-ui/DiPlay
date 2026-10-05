package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class BydStandaloneStreetTest {
    private fun content(road: String): ByteArray {
        val bytes = BydStandalonePackets.streetName(road).split(',').map { it.toInt(16).toByte() }
        assertTrue(bytes.size <= 182)
        return bytes.chunked(13).flatMapIndexed { index, record ->
            assertEquals(13, record.size)
            assertEquals(0x43, record[0].toInt() and 255)
            assertEquals(0xFA, record[1].toInt() and 255)
            assertEquals((index + 1) shl 4, record[2].toInt() and 255)
            assertEquals(8, record[3].toInt() and 255)
            assertEquals(8, record[4].toInt() and 255)
            assertEquals(0xA1 + index, record[5].toInt() and 255)
            record.drop(6)
        }.toByteArray()
    }
    private fun decoded(road: String): String {
        val data = content(road)
        val size = data[0].toInt() and 255
        val text = data.copyOfRange(1, size + 1)
        assertEquals(text.sumOf { it.toInt() and 255 } and 255, data[size + 1].toInt() and 255)
        assertTrue(data.drop(size + 2).all { it == 0xFF.toByte() })
        return text.toString(Charsets.UTF_16LE)
    }
    @Test fun matchesActualArm64EncoderEnglishAndArabicVectors() {
        assertEquals("43,FA,10,08,08,A1,16,4D,00,75,00,73,00,43,FA,20,08,08,A2,63,00,61,00,74,00,20,43,FA,30,08,08,A3,00,52,00,6F,00,61,00,43,FA,40,08,08,A4,64,00,13,FF,FF,FF,FF", BydStandalonePackets.streetName("Muscat Road"))
        assertEquals("43,FA,10,08,08,A1,12,34,06,27,06,31,06,43,FA,20,08,08,A2,39,06,20,00,45,06,33,43,FA,30,08,08,A3,06,42,06,37,06,06,FF", BydStandalonePackets.streetName("شارع مسقط"))
    }
    @Test fun textIsBoundedWithoutBreakingUnicodePairs() {
        assertEquals("A".repeat(48), decoded("A".repeat(500)))
        assertEquals("A".repeat(47), decoded("A".repeat(47) + "🚗End"))
        assertEquals("A".repeat(46) + "🚗", decoded("A".repeat(46) + "🚗End"))
        assertEquals("AB", decoded("A\uD800B\uDC00"))
    }
    @Test fun runsOfWhitespaceCollapseToOneCell() {
        assertEquals("A B", decoded("A   B"))
        assertEquals("A B C", decoded("A \t\n B  C"))
        assertEquals("A B", decoded("  A  B  "))
    }
    @Test fun blankAndControlsReplacePriorTextWithSafeWhitespace() {
        assertEquals(" ", decoded(""))
        assertEquals(" ", decoded("\n\u0000\t"))
        assertEquals("A B C", decoded("A\u0000B\nC"))
    }
    @Test fun everyBoundaryHasCorrectLengthChecksumPaddingAndReceiverBound() {
        for (length in 1..48) {
            val road = "R".repeat(length)
            assertEquals(road, decoded(road))
            assertTrue(BydStandalonePackets.guidance(2, 0, 500, road)!!.split(',').size <= 252)
        }
    }
    @Test fun changedStreetIsPublishedAndMissingStreetReplacesIt() {
        val sent = mutableListOf<String>()
        val session = BydStandaloneSession(sent::add, {}, nanoTime = { 0L })
        session.update(2, 0, 500, "First Road")
        session.update(2, 0, 500, "Second Road")
        assertEquals(BydStandalonePackets.guidance(2, 0, 500, "Second Road"), sent.last())
        val count = sent.size
        session.update(2, 0, 500, "Second Road")
        assertEquals(count, sent.size)
        session.update(2, 0, 500, "")
        assertEquals(BydStandalonePackets.guidance(2, 0, 500, ""), sent.last())
        session.clear()
        assertEquals(BydStandalonePackets.clear(), sent.last())
    }
}
