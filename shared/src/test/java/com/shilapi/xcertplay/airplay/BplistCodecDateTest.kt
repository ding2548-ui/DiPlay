package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class BplistCodecDateTest {
    @Test
    fun decodesDates() {
        // What Safari's insertPlayQueueItem carries: a Start-Date next to the media URL (made with Python plistlib).
        val hex = "62706c6973743030d201020308546974656d5474797065d2040506075f1010436f6e74656e742d4c6f636174696f6e" +
            "5a53746172742d446174655f101968747470733a2f2f6578616d706c652e636f6d2f762e6d70343341c83698a00000005f1013" +
            "696e73657274506c617951756575654974656d080d12171c2f3a565f000000000000010100000000000000090000000000000000" +
            "0000000000000075"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val root = BplistCodec.decode(bytes) as Map<*, *>
        val item = root["item"] as Map<*, *>

        assertEquals("insertPlayQueueItem", root["type"])
        assertEquals("https://example.com/v.mp4", item["Content-Location"])
        assertEquals(Date(1_790_769_600_000L), item["Start-Date"]) // 2026-09-30 12:00:00 UTC
    }
}
