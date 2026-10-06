package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File

/** Bounded, private diagnostics. Each write is redacted before touching storage. */
internal class SessionLogFile(val file: File) : Closeable {
    private val lock = Any()
    private var closed = false
    fun reset(header: String) = synchronized(lock) {
        if (!closed) {
            file.parentFile?.mkdirs()
            rotate()
            file.writeText("")
            append(header)
        }
    }
    fun append(line: String) = synchronized(lock) {
        if (closed) return@synchronized
        val safe = DiagnosticRedactor.redact(line) ?: return@synchronized
        runCatching {
            if (file.length() > MAX_BYTES) {
                rotate()
                file.writeText("")
            }
            file.appendText(safe + "\n")
        }
        Unit
    }
    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
    }
    override fun close() = synchronized(lock) { closed = true }
    companion object {
        const val MAX_BYTES = 512 * 1024L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        // "crash.txt" is written by the uncaught-exception handler in CarPlayHostActivity.onCreate.
        // The report itself is produced by a live process, so without this the stack that killed
        // the session never reaches the exported file — reading the logs is then the only way to
        // see how far the previous attempt got.
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log" + "crash.txt"
    }
}
