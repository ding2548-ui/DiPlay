package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AirPlayHidInputSemanticsTest {
    @Test fun touchscreenCoordinatesRemainAbsoluteSixteenBitValuesForBothContacts() {
        val device = AirPlayHid.touchHidDevice(1920, 1080, "test-display")
        val fields = inputFields(device["hidDescriptor"] as ByteArray).filter {
            it.page == 1 && (0x30 in it.usages || 0x31 in it.usages)
        }
        assertEquals(4, fields.size)
        assertTrue(fields.all { it.size == 16 && it.count == 1 && it.flags == 0x02 })
        assertEquals(listOf(0x30, 0x31, 0x30, 0x31), fields.map { it.usages.single() })
    }

    @Test fun legacyKnobCoordinatesStayAbsoluteWhileWheelMovementRemainsRelative() {
        val device = AirPlayHid.knobHidDevice("test-display")
        val fields = inputFields(device["hidDescriptor"] as ByteArray)
        val coordinates = fields.single { it.page == 1 && it.usages == listOf(0x30, 0x31) }
        val wheel = fields.single { it.page == 1 && it.usages == listOf(0x38) }
        assertEquals(0x02, coordinates.flags)
        assertEquals(8, coordinates.size)
        assertEquals(2, coordinates.count)
        assertEquals(0x06, wheel.flags)
        assertEquals(8, wheel.size)
        assertEquals(1, wheel.count)
    }

    private data class InputField(val page: Int, val usages: List<Int>, val size: Int, val count: Int, val flags: Int)

    /** Decode HID short items so these checks assert field semantics, not byte positions. */
    private fun inputFields(descriptor: ByteArray): List<InputField> {
        val fields = mutableListOf<InputField>()
        val usages = mutableListOf<Int>()
        var page = 0
        var reportSize = 0
        var reportCount = 0
        var offset = 0
        while (offset < descriptor.size) {
            val prefix = descriptor[offset++].toInt() and 0xff
            require(prefix != 0xfe) { "Only HID short items are expected" }
            val size = if ((prefix and 3) == 3) 4 else (prefix and 3)
            var value = 0
            repeat(size) { byte -> value = value or ((descriptor[offset++].toInt() and 0xff) shl (8 * byte)) }
            val type = (prefix shr 2) and 3
            val tag = prefix shr 4
            when (type) {
                1 -> when (tag) {
                    0 -> page = value
                    7 -> reportSize = value
                    9 -> reportCount = value
                }
                2 -> if (tag == 0) usages += value
                0 -> {
                    if (tag == 8) fields += InputField(page, usages.toList(), reportSize, reportCount, value)
                    usages.clear()
                }
            }
        }
        return fields
    }
}
