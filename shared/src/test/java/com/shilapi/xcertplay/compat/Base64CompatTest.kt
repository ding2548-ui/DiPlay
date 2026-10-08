package com.shilapi.xcertplay.compat

import java.util.Base64
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Base64CompatTest {
    private val random = Random(7)

    @Test fun encodeAndDecodeMatchJavaBase64() {
        repeat(500) {
            val bytes = random.nextBytes(random.nextInt(0, 300))
            val encoded = Base64.getEncoder().encodeToString(bytes)

            assertEquals(encoded, Base64Compat.encode(bytes))
            assertArrayEquals(bytes, Base64Compat.decode(encoded))
            assertArrayEquals(bytes, Base64Compat.decode(encoded.trimEnd('=')))
        }
    }

    @Test fun lineEncodingMatchesJavaMimeEncoder() {
        val encoder = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
        repeat(200) {
            val bytes = random.nextBytes(random.nextInt(0, 600))

            assertEquals(encoder.encodeToString(bytes), Base64Compat.encodeLines(bytes, 64, "\n"))
        }
    }

    @Test fun mimeDecodingSkipsLineBreaksLikeJava() {
        repeat(200) {
            val bytes = random.nextBytes(random.nextInt(0, 600))
            val pem = "\r\n" + Base64.getMimeEncoder().encodeToString(bytes) + "\n"
            val ascii = pem.toByteArray(Charsets.US_ASCII)

            assertArrayEquals(Base64.getMimeDecoder().decode(ascii), Base64Compat.decodeMime(ascii))
        }
    }

    @Test fun invalidInputIsRejectedLikeJava() {
        for (invalid in listOf("A", "AB=", "A===", "AB==C", "AB C", "ABC*", "=AAA", "ABCDE")) {
            assertThrows(IllegalArgumentException::class.java) { Base64.getDecoder().decode(invalid) }
            assertThrows(invalid, IllegalArgumentException::class.java) { Base64Compat.decode(invalid) }
        }
    }
}
