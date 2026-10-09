package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit settings action; connection startup never grants these permissions. */
internal object UsbPermissionSetup {
    // Keep wheel-service rebinding and explicit USB setup from overwriting each other's service list.
    internal val accessibilityLock = Any()
    enum class Permission {
        ACCESSIBILITY, USAGE, OVERLAY;

        fun granted(context: Context): Boolean = runCatching {
            when (this) {
                ACCESSIBILITY -> UsbAutoConfirmService.isEnabled(context) &&
                    Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
                USAGE -> HomeScreenMonitor.hasAccess(context)
                OVERLAY -> Settings.canDrawOverlays(context)
            }
        }.getOrDefault(false)
    }

    fun snapshot(context: Context): Map<Permission, Boolean> = Permission.entries.associateWith { it.granted(context) }

    internal interface Client : Closeable {
        fun connect(): LocalAdb.Access
        fun shell(command: String): String?
        fun cancel()
    }

    private class AdbClient(context: Context) : Client {
        private val adb = LocalAdb(AdbKeys.load(context))
        override fun connect() = adb.connect(mayAsk = true)
        override fun shell(command: String) = synchronized(accessibilityLock) { adb.shell(command) }
        override fun cancel() = adb.cancelPendingOperations()
        override fun close() = adb.close()
    }

    data class Result(
        val access: LocalAdb.Access,
        val verified: Map<Permission, Boolean>,
        val commandsFailed: Set<Permission> = emptySet(),
    ) {
        val complete: Boolean get() = access == LocalAdb.Access.READY && commandsFailed.isEmpty() &&
            verified.size == Permission.entries.size && verified.values.all { it }
    }

    class Operation(
        private val context: Context,
        private val factory: () -> Client = { AdbClient(context) },
        private val granted: (Permission) -> Boolean = { it.granted(context) },
    ) {
        private val cancelled = AtomicBoolean(false)
        private val lock = Any()
        private var client: Client? = null
        val isCancelled: Boolean get() = cancelled.get()

        fun cancel() {
            cancelled.set(true)
            // Cancellation closes the socket without waiting for a synchronized ADB read.
            synchronized(lock) { client }?.cancel()
        }

        fun run(): Result {
            var access = LocalAdb.Access.UNREACHABLE
            val failed = mutableSetOf<Permission>()
            if (isCancelled) return Result(access, emptyMap())
            runCatching {
                factory().use { adb ->
                    synchronized(lock) {
                        client = adb
                        if (isCancelled) adb.cancel()
                    }
                    if (isCancelled) return@use
                    access = adb.connect()
                    if (access != LocalAdb.Access.READY || isCancelled) return@use
                    for (permission in Permission.entries) {
                        if (isCancelled) break
                        if (granted(permission)) continue
                        val command = command(permission, context.packageName)
                        if (isCancelled) break
                        val output = adb.shell(withExitStatus(command))
                        if (isCancelled) break
                        if (output?.lineSequence()?.lastOrNull() != "$EXIT_MARKER:0") failed += permission
                    }
                }
            }.onFailure { failed += Permission.entries }
            synchronized(lock) { client = null }
            val verified = if (isCancelled) emptyMap() else Permission.entries.associateWith {
                runCatching { granted(it) }.getOrDefault(false)
            }
            return Result(access, verified, failed)
        }
    }

    private const val EXIT_MARKER = "DIPLAY_PERMISSION_EXIT"

    private fun withExitStatus(command: String): String =
        "( $command ); result=\$?; printf '\\n$EXIT_MARKER:%s\\n' \"\$result\""

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Read and append in the device shell; neither path replaces the other enabled services. */
    internal fun command(permission: Permission, packageName: String): String {
        require(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(packageName))
        val pkg = quote(packageName)
        return when (permission) {
            Permission.USAGE -> "appops set $pkg GET_USAGE_STATS allow"
            Permission.OVERLAY -> "appops set $pkg SYSTEM_ALERT_WINDOW allow"
            Permission.ACCESSIBILITY -> {
                val service = quote("$packageName/com.shilapi.xcertplay.UsbAutoConfirmService")
                """current=${'$'}(settings get secure enabled_accessibility_services) || exit 1;
                    case "${'$'}current" in null|"") current="";; esac;
                    case "${'$'}current" in *[!a-zA-Z0-9_./:${'$'}]*) exit 1;; esac;
                    service=$service;
                    case ":${'$'}current:" in *":${'$'}service:"*) ;; *)
                        if [ -n "${'$'}current" ]; then current="${'$'}current:${'$'}service"; else current="${'$'}service"; fi;;
                    esac;
                    settings put secure enabled_accessibility_services "${'$'}current" &&
                    settings put secure accessibility_enabled 1""".trimIndent().replace("\n", " ")
            }
        }
    }

    fun manualCommand(packageName: String): String = Permission.entries.joinToString(" && ") {
        "adb shell ${quote(command(it, packageName))}"
    }
}
