package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.GeneratedMfiMaterial
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import java.security.MessageDigest

/** Installs the private beta's experimental identity, with an on-device generated fallback. */
internal object DiPlayBootstrap {
    @Volatile private var ready = false

    @Synchronized fun ensure(context: Context) {
        if (ready) return
        val target = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (!target.exists()) {
            stageFromAssets(context, target)
        }
        var loaded = runCatching { LocalMfiAuthenticationClient.load(target) }
        if (loaded.isFailure) {
            // Beta material fallback: nothing usable was staged (an asset-free build or a
            // corrupt data directory), so generate a self-contained experimental identity
            // on the device instead of failing the whole bootstrap.
            target.deleteRecursively()
            runCatching { GeneratedMfiMaterial.ensure(target) }
                .onFailure { throw IllegalStateException("Local identity generation failed: ${it.message}", it) }
            loaded = runCatching { LocalMfiAuthenticationClient.load(target) }
            check(loaded.isSuccess) { "Could not install local authentication" }
        }
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
        ready = true
    }

    /** Stages identity.pk8 / certificate.p7b from the APK assets; false when they are absent. */
    private fun stageFromAssets(context: Context, target: File): Boolean {
        val staging = File(context.noBackupFilesDir, "offline-mfi-staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare local authentication" }
        staging.setReadable(false, false); staging.setReadable(true, true)
        staging.setExecutable(false, false); staging.setExecutable(true, true)
        var staged = true
        try {
            for (name in listOf("identity.pk8", "certificate.p7b")) {
                try {
                    val file = File(staging, name)
                    context.assets.open("offline-mfi/$name").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file.setReadable(false, false); file.setReadable(true, true)
                    file.setWritable(false, false); file.setWritable(true, true)
                } catch (failure: Exception) {
                    staged = false
                    break
                }
            }
            if (!staged) return false
            LocalMfiAuthenticationClient.load(staging)
            check(staging.renameTo(target)) { "Could not install local authentication" }
            return true
        } finally {
            // After a successful rename the directory no longer exists; on any failure the
            // partial staging content is wiped so the next attempt starts clean.
            staging.deleteRecursively()
        }
    }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "你的 iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }

    /** Beta: disconnect the phone's Bluetooth audio profiles when CarPlay takes over. */
    fun a2dpHandoff(context: Context) = prefs(context).getBoolean("a2dp_handoff", true)
    fun saveA2dpHandoff(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("a2dp_handoff", value).apply()
    }

    /** Beta: the userspace lwIP wired transport is the default on this v7a-only test line. */
    fun wiredLwip(context: Context) = prefs(context).getBoolean("wired_lwip", true)
    fun saveWiredLwip(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("wired_lwip", value).apply()
    }

    /** Beta: the fullscreen master switch; only usable while both per-bar switches are off. */
    fun fullscreenMaster(context: Context) = prefs(context).getBoolean("fullscreen_master", false)
    fun saveFullscreenMaster(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("fullscreen_master", value).apply()
    }
}
