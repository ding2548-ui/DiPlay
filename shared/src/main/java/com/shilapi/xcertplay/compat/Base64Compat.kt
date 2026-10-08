package com.shilapi.xcertplay.compat

/**
 * RFC 4648 Base64 with the semantics of the `java.util.Base64` basic and MIME codecs, which
 * Android only provides from API 26. `android.util.Base64` is not used because plain JVM unit
 * tests cannot run it.
 */
object Base64Compat {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    /** Same as `Base64.getEncoder().encodeToString(bytes)`. */
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index + 3 <= bytes.size) {
            val bits = (bytes[index].toInt() and 0xff shl 16) or
                (bytes[index + 1].toInt() and 0xff shl 8) or
                (bytes[index + 2].toInt() and 0xff)
            out.append(ALPHABET[bits ushr 18 and 63]).append(ALPHABET[bits ushr 12 and 63])
                .append(ALPHABET[bits ushr 6 and 63]).append(ALPHABET[bits and 63])
            index += 3
        }
        when (bytes.size - index) {
            1 -> {
                val bits = bytes[index].toInt() and 0xff shl 16
                out.append(ALPHABET[bits ushr 18 and 63]).append(ALPHABET[bits ushr 12 and 63]).append("==")
            }
            2 -> {
                val bits = (bytes[index].toInt() and 0xff shl 16) or (bytes[index + 1].toInt() and 0xff shl 8)
                out.append(ALPHABET[bits ushr 18 and 63]).append(ALPHABET[bits ushr 12 and 63])
                    .append(ALPHABET[bits ushr 6 and 63]).append('=')
            }
        }
        return out.toString()
    }

    /** Same as `Base64.getMimeEncoder(lineLength, separator).encodeToString(bytes)` for a multiple of 4. */
    fun encodeLines(bytes: ByteArray, lineLength: Int, separator: String): String {
        require(lineLength > 0 && lineLength % 4 == 0) { "Line length must be a positive multiple of 4" }
        return encode(bytes).chunked(lineLength).joinToString(separator)
    }

    /** Same as `Base64.getDecoder().decode(encoded)`; throws [IllegalArgumentException] when invalid. */
    fun decode(encoded: String): ByteArray = decode(encoded.length, ignoreOtherCharacters = false) { encoded[it].code }

    /** Same as `Base64.getMimeDecoder().decode(encoded)`: characters outside the alphabet are skipped. */
    fun decodeMime(encoded: ByteArray): ByteArray =
        decode(encoded.size, ignoreOtherCharacters = true) { encoded[it].toInt() and 0xff }

    private inline fun decode(length: Int, ignoreOtherCharacters: Boolean, charAt: (Int) -> Int): ByteArray {
        val out = ByteArray(length / 4 * 3 + 2)
        var size = 0
        var bits = 0
        var count = 0
        var index = 0
        while (index < length) {
            val code = charAt(index++)
            if (code == '='.code) {
                // Padding ends the data: "==" after two symbols of a unit, "=" after three.
                val complete = count == 3 || (count == 2 && index < length && charAt(index++) == '='.code)
                require(complete) { "Input byte array has wrong 4-byte ending unit" }
                while (index < length) {
                    val trailing = charAt(index++)
                    require(ignoreOtherCharacters && decodeValue(trailing) < 0) {
                        "Input byte array has incorrect ending byte at ${index - 1}"
                    }
                }
                break
            }
            val value = decodeValue(code)
            if (value < 0) {
                if (ignoreOtherCharacters) continue
                throw IllegalArgumentException("Illegal base64 character ${Integer.toHexString(code)}")
            }
            bits = bits shl 6 or value
            if (++count == 4) {
                out[size++] = (bits shr 16).toByte()
                out[size++] = (bits shr 8).toByte()
                out[size++] = bits.toByte()
                bits = 0
                count = 0
            }
        }
        when (count) {
            1 -> throw IllegalArgumentException("Last unit does not have enough valid bits")
            2 -> out[size++] = (bits shr 4).toByte()
            3 -> {
                out[size++] = (bits shr 10).toByte()
                out[size++] = (bits shr 2).toByte()
            }
        }
        if (size == out.size) return out
        return out.copyOf(size).also { out.fill(0) }
    }

    private fun decodeValue(code: Int): Int = if (code in 0 until 128) DECODE[code] else -1
}
