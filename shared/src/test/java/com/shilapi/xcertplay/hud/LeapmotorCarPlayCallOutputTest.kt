package com.shilapi.xcertplay.hud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Leapmotor call card's wire format: the `{"type":"call", ...}` JSON envelope and the
 * `[u16 length BE][UTF-8 json]` packing the meter's `car.hmi.sender.BROADCAST` ingress expects.
 * The values are locked to the format the third-party `小零助手` app and `LauncherThree` use.
 */
class LeapmotorCarPlayCallOutputTest {
    @Test
    fun ringingCardCarriesTheCallerName() {
        val json = LeapmotorCarPlayCallOutput.callJson(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Mum"))
        assertEquals("{\"type\":\"call\",\"data\":{\"state\":\"ring\",\"name\":\"Mum\"}}", json)
    }

    @Test
    fun activeAndDialingCardsReportAnswer() {
        for (phase in listOf(CarPlayCallCard.Phase.ACTIVE, CarPlayCallCard.Phase.DIALING)) {
            val json = LeapmotorCarPlayCallOutput.callJson(CarPlayCallCard(phase, "Office"))
            assertEquals("{\"type\":\"call\",\"data\":{\"state\":\"answer\",\"name\":\"Office\"}}", json)
        }
    }

    @Test
    fun anEmptyCallerNameIsOmittedNotBlank() {
        val json = LeapmotorCarPlayCallOutput.callJson(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, ""))
        assertEquals("{\"type\":\"call\",\"data\":{\"state\":\"ring\"}}", json)
        assertFalse(json.contains("name"))
    }

    @Test
    fun noCardIsAnExplicitEnd() {
        assertEquals(
            "{\"type\":\"call\",\"data\":{\"state\":\"end\"}}",
            LeapmotorCarPlayCallOutput.callJson(null),
        )
    }

    @Test
    fun aCallerNameIsJsonEscaped() {
        val json = LeapmotorCarPlayCallOutput.callJson(
            CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "a\"b\\c\nd"),
        )
        assertEquals("{\"type\":\"call\",\"data\":{\"state\":\"ring\",\"name\":\"a\\\"b\\\\c\\nd\"}}", json)
    }

    @Test
    fun wirePrefixesTheUtf8LengthBigEndian() {
        val json = "{\"type\":\"call\",\"data\":{\"state\":\"end\"}}"
        val payload = json.toByteArray(Charsets.UTF_8)
        val wire = LeapmotorCarPlayCallOutput.wire(json)
        assertEquals(payload.size + 2L, wire.size.toLong())
        assertEquals(((payload.size shr 8) and 0xff).toByte(), wire[0])
        assertEquals((payload.size and 0xff).toByte(), wire[1])
        assertArrayEquals(payload, wire.copyOfRange(2, wire.size))
    }

    @Test
    fun wireLengthCountsUtf8BytesNotCharacters() {
        // A CJK name: 3 UTF-8 bytes per character. The prefix must count bytes, not chars.
        val json = LeapmotorCarPlayCallOutput.callJson(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "妈妈"))
        val payload = json.toByteArray(Charsets.UTF_8)
        val wire = LeapmotorCarPlayCallOutput.wire(json)
        val declared = ((wire[0].toInt() and 0xff) shl 8) or (wire[1].toInt() and 0xff)
        assertEquals(payload.size.toLong(), declared.toLong())
        assertTrue(declared > json.length)
    }
}
