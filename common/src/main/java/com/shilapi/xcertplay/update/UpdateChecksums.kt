package com.shilapi.xcertplay.update

import java.io.File
import java.security.MessageDigest

internal object UpdateChecksums {
    private val LINE = Regex("^([0-9a-fA-F]{64})\\s+([^\\s].*)$")

    internal fun parse(text: String): Map<String, String> =
        text.lineSequence()
            .mapNotNull { line ->
                LINE.find(line.trim())?.let { match ->
                    match.groupValues[2].trim() to match.groupValues[1].lowercase()
                }
            }
            .toMap()

    internal fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun matches(expected: Map<String, String>, fileName: String, actualHex: String): Boolean {
        val wanted = expected[fileName] ?: return false
        return MessageDigest.isEqual(wanted.lowercase().toByteArray(), actualHex.lowercase().toByteArray())
    }
}
