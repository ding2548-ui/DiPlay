package com.shilapi.xcertplay.network

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

/** Covers app recreation and multiple app_process instances, not just in-process callers. */
internal object HotspotJoinLock {
    fun <T> run(directory: File, action: () -> T): T? = RandomAccessFile(File(directory, "lock"), "rw").use { file ->
        val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            ?: return null
        lock.use { action() }
    }
}
