package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/**
 * One-time ADB repairs for boot auto-start on firmwares that keep third-party receivers inside
 * background limits: exempt the app from background restrictions and add it to the idle
 * (doze) whitelist, so the system lets the BOOT_COMPLETED broadcast through. Nothing here
 * changes BYD's private autostart manager; that whitelist, when a firmware has one, stays a
 * manual step in the car's settings.
 */
object BydBootStartRepair {
    /** One line per attempted fix: the short name plus ✓ or the failure text. */
    class Result(val lines: List<String>) {
        val applied: Boolean get() = lines.any { it.endsWith("✓") }
    }

    /** Blocking, with the car allowed to show its ADB key approval dialog. Off the main thread. */
    fun apply(context: Context, packageName: String): Result {
        val lines = mutableListOf<String>()
        LocalAdb(AdbKeys.load(context)).use { adb ->
            when (adb.connect(mayAsk = true)) {
                LocalAdb.Access.READY -> Unit
                LocalAdb.Access.NOT_APPROVED -> return Result(listOf("ADB not approved"))
                LocalAdb.Access.UNREACHABLE -> return Result(listOf("ADB off"))
                LocalAdb.Access.UNSUPPORTED -> return Result(listOf("ADB pairing-only"))
            }
            for (op in listOf("RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND")) {
                adb.shell("appops set $packageName $op allow")
                val check = adb.shell("appops get $packageName $op")
                val allowed = check?.contains("allow") == true
                lines += "$op ${if (allowed) "allow ✓" else (check?.trim().takeUnless { it.isNullOrEmpty() } ?: "no answer")}"
            }
            adb.shell("cmd deviceidle whitelist +$packageName")
            val whitelist = adb.shell("dumpsys deviceidle whitelist")
            val listed = isWhitelisted(whitelist, packageName)
            lines += "doze whitelist ${if (listed) "✓" else "not listed"}"
        }
        return Result(lines)
    }

    internal fun isWhitelisted(output: String?, packageName: String): Boolean =
        output?.lineSequence()?.any { line ->
            val fields = line.trim().split(',')
            fields.size >= 3 && fields[0] in setOf("system", "system-excidle", "user") &&
                fields[1] == packageName
        } == true
}
