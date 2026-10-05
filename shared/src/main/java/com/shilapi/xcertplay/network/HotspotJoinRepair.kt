package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** One-shot, explicit settings actions only. Never called by wireless connection/startup paths. */
class HotspotJoinRepair {
    enum class Action { CHECK, APPLY, RESTORE }
    enum class Code { READY, ALREADY_PRESENT, APPLIED, RESTORED, UNSUPPORTED, DRIFT,
        FIRMWARE_CHANGED, HOTSPOT_ON, UNKNOWN_STATE, CANCELLED, FAILED_ROLLED_BACK,
        RECOVERY_REQUIRED, STORAGE_FAILED, BUSY, ADB_OFF, NOT_APPROVED, PAIRING_ONLY, UNKNOWN }
    data class Result(val code: Code, val token: String? = null, val rollback: Boolean = false)
    private val cancelled = AtomicBoolean()
    @Volatile private var adb: LocalAdb? = null
    @Volatile private var request: String? = null
    @Volatile private var app: Context? = null

    fun run(context: Context, action: Action, token: String? = null): Result {
        if (!operations.tryLock()) return Result(Code.BUSY)
        try {
            if (cancelled.get()) return Result(Code.CANCELLED)
            val application = context.applicationContext
            app = application
            val operation = UUID.randomUUID().toString()
            request = operation
            val client = LocalAdb(AdbKeys.load(application)).also { adb = it }
            client.use {
                when (it.connect(mayAsk = action == Action.CHECK)) {
                    LocalAdb.Access.NOT_APPROVED -> return Result(Code.NOT_APPROVED)
                    LocalAdb.Access.UNREACHABLE -> return Result(Code.ADB_OFF)
                    LocalAdb.Access.UNSUPPORTED -> return Result(Code.PAIRING_ONLY)
                    LocalAdb.Access.READY -> Unit
                }
                if (cancelled.get()) return Result(Code.CANCELLED)
                val output = it.shell(command(application.packageCodePath, application.packageName,
                    action.name.lowercase(), operation, token), 30_000)
                // A lost response does not prove cancellation/rollback. Check the durable journal next.
                return parse(output)
            }
        } catch (_: Exception) { return Result(Code.UNKNOWN) }
        finally { adb = null; request = null; operations.unlock() }
    }

    /** Best effort remote cancellation plus immediate local I/O cancellation; unknown outcomes need Check. */
    fun cancel() {
        cancelled.set(true)
        val operation = request; val application = app
        adb?.cancelPendingOperations()
        if (operation != null && application != null) Thread({
            runCatching {
                LocalAdb(AdbKeys.load(application)).use {
                    if (it.connect(mayAsk = false) == LocalAdb.Access.READY)
                        it.shell(command(application.packageCodePath, application.packageName, "cancel", operation, null))
                }
            }
        }, "diplay-hotspot-cancel").apply { isDaemon = true; start() }
    }

    companion object {
        private val operations = ReentrantLock()
        internal const val HEADER = "DIPLAY_HOTSPOT_JOIN_V1"
        internal fun validToken(value: String) = value.matches(Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
        internal fun command(apk: String, packageName: String, action: String, request: String, token: String?): String {
            require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")))
            require(action in setOf("check", "apply", "restore", "cancel") && validToken(request))
            require(token == null || validToken(token))
            require(action !in setOf("apply", "restore") || token != null)
            val quoted = "'" + apk.replace("'", "'\"'\"'") + "'"
            return "umask 077; CLASSPATH=$quoted app_process /system/bin ${HotspotJoinRepairMain::class.java.name} $packageName $action $request ${token ?: "-"}"
        }
        internal fun parse(output: String?): Result {
            val fields = output?.trim()?.split('|') ?: return Result(Code.UNKNOWN)
            if (fields.size != 4 || fields[0] != HEADER || fields[3] !in setOf("0", "1")) return Result(Code.UNKNOWN)
            val code = Code.entries.firstOrNull { it.name == fields[1] } ?: return Result(Code.UNKNOWN)
            val token = fields[2].takeIf { validToken(it) }
            if (fields[2] != "-" && token == null || code in setOf(Code.READY, Code.APPLIED) && token == null)
                return Result(Code.UNKNOWN)
            return Result(code, token, fields[3] == "1")
        }
    }
}
