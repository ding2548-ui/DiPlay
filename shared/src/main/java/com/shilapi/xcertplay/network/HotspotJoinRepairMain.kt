package com.shilapi.xcertplay.network

import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

/** Privileged entry point. Prints ONLY the protocol result: no exception text, parcels or credentials. */
object HotspotJoinRepairMain {
    @JvmStatic fun main(args: Array<String>) {
        // A stuck Binder call must not leave a privileged helper running indefinitely.
        Thread({ Thread.sleep(25_000); exitProcess(2) }, "hotspot-helper-deadline")
            .apply { isDaemon = true; start() }
        val result = try { execute(args) } catch (_: HotspotJoinJournal.FirmwareChanged) {
            HotspotJoinTransaction.Result(HotspotJoinTransaction.Status.FIRMWARE_CHANGED, rollback = true)
        } catch (_: IOException) {
            HotspotJoinTransaction.Result(HotspotJoinTransaction.Status.STORAGE_FAILED, rollback = true)
        } catch (_: Throwable) {
            // Unknown could include a Binder write that took effect before throwing. Keep the journal.
            HotspotJoinTransaction.Result(HotspotJoinTransaction.Status.RECOVERY_REQUIRED, rollback = true)
        }
        println("${HotspotJoinRepair.HEADER}|${result.status}|${result.token ?: "-"}|${if (result.rollback) 1 else 0}")
        exitProcess(0)
    }

    private fun execute(args: Array<String>): HotspotJoinTransaction.Result {
        val uid = Process.myUid()
        require(uid == 2000 || uid == 0)
        require(args.size == 4)
        val (packageName, action, request, token) = args
        require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")))
        require(action in setOf("check", "apply", "restore", "cancel") && HotspotJoinRepair.validToken(request))
        require(token == "-" || HotspotJoinRepair.validToken(token))
        if (Build.VERSION.SDK_INT < 33) return HotspotJoinTransaction.Result(HotspotJoinTransaction.Status.UNSUPPORTED)
        Os.umask(0x3f) // All new journal/lock/cancellation files start at 0600, directories at 0700.
        val directory = File("/data/local/tmp/diplay-hotspot-join-$packageName")
        if (!directory.exists()) Os.mkdir(directory.path, 0x1c0)
        val stat = Os.lstat(directory.path)
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != uid || stat.st_mode and 0x3f != 0)
            throw IOException()
        for (file in directory.listFiles() ?: throw IOException()) {
            val entry = Os.lstat(file.path)
            if (!OsConstants.S_ISREG(entry.st_mode) || entry.st_uid != uid || entry.st_mode and 0x3f != 0)
                throw IOException() // Never follow foreign/symlinked backup or lock paths.
        }
        val cancellation = File(directory, "cancel-$request")
        if (action == "cancel") {
            cancellation.writeText("")
            return HotspotJoinTransaction.Result(HotspotJoinTransaction.Status.CANCELLED)
        }
        val deadline = System.nanoTime() + 20_000_000_000L
        try {
            return HotspotJoinLock.run(directory) {
                val port = try { HotspotJoinPlatform.connect() }
                    catch (_: Exception) { return@run HotspotJoinTransaction.Result(HotspotJoinTransaction.Status.UNSUPPORTED) }
                val journal = HotspotJoinJournal(directory, port.firmware(), port.codec) {
                    for (durableDirectory in listOf(directory.parentFile, directory)) {
                        val fd = Os.open(durableDirectory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
                        try {
                            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw IOException()
                            Os.fsync(fd)
                        } finally { Os.close(fd) }
                    }
                }
                journal.load() // Firmware validation before decoding/any repair action.
                val transaction = HotspotJoinTransaction(port, journal) {
                    cancellation.exists() || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted
                }
                when (action) {
                    "check" -> transaction.prepare()
                    "apply" -> transaction.apply(token)
                    "restore" -> transaction.restore(token)
                    else -> throw IllegalArgumentException()
                }
            } ?: HotspotJoinTransaction.Result(HotspotJoinTransaction.Status.BUSY)
        } finally { cancellation.delete() }
    }
}
